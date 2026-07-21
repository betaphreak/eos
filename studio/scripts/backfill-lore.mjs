// backfill-lore.mjs — P5 C1 lore-RAG backfill (docs/lore-chatbot-plan.md). Embeds the wiki lore chunks
// via a self-hosted TEI (bge-small, 384-dim) and loads them into the pgvector `wiki_chunk` table that the
// Spring AI retrieval layer queries. A bake step (sibling of seed.js), NOT part of the served image.
//
// Pipeline: WikiChunkExporter (Java) writes wiki-chunk.json.gz  →  this script embeds + loads it.
//   mvn -pl civstudio-engine compile exec:exec -Dsim.main=com.civstudio.wiki.export.WikiChunkExporter
//   node scripts/backfill-lore.mjs
//
// DB target: LORE_PG_* if set (local dev → the Docker pgvector container, since the local Strapi Postgres
// lacks the extension), else the DATABASE_* Strapi DB (prod → civstudio-postgres, which now has pgvector
// allow-listed). wiki_chunk lives in the SAME DB as the canonical tables so the hybrid join works on prod.
// TEI endpoint: LORE_TEI_URL (default the local container). Query with --query "…" for an ad-hoc retrieval.
//
// Run from studio/ (for its `pg`): node scripts/backfill-lore.mjs [--query "question"]

import { readFileSync } from "node:fs";
import { gunzipSync } from "node:zlib";
import { join } from "node:path";
import { Client } from "pg";
import "dotenv/config";

const DIM = 384;
const TEI_BATCH = 32; // TEI's default max-client-batch-size
const TEI_URL = process.env.LORE_TEI_URL || "http://localhost:8085/embed";
const CHUNKS = process.env.LORE_CHUNKS
  || join(process.cwd(), "..", "civstudio-engine", "target", "generated", "wiki", "wiki-chunk.json.gz");

const pg = () => new Client({
  host: process.env.LORE_PG_HOST || process.env.DATABASE_HOST,
  port: process.env.LORE_PG_PORT || process.env.DATABASE_PORT || 5432,
  database: process.env.LORE_PG_DB || process.env.DATABASE_NAME,
  user: process.env.LORE_PG_USER || process.env.DATABASE_USERNAME,
  password: process.env.LORE_PG_PASSWORD || process.env.DATABASE_PASSWORD,
  ssl: (process.env.LORE_PG_SSL || process.env.DATABASE_SSL) === "true" ? { rejectUnauthorized: false } : undefined,
});
const vec = (a) => "[" + a.join(",") + "]";

async function embed(texts) {
  const res = await fetch(TEI_URL, { method: "POST", headers: { "content-type": "application/json" },
    body: JSON.stringify({ inputs: texts, truncate: true }) });
  if (!res.ok) throw new Error(`TEI ${res.status}: ${(await res.text()).slice(0, 200)}`);
  return res.json();
}
// A passage carries its own title/section context into the embedding (helps recall on short chunks).
const embedText = (x) => `${x.title} — ${x.section}\n${x.text}`;

async function retrieve(c, question, k = 5) {
  const [qe] = await embed([question]);
  const r = await c.query(
    `SELECT title, section, entity_ref, entity_key, wiki_url, left(text, 100) AS snip,
            1 - (embedding <=> $1::vector) AS sim
     FROM wiki_chunk ORDER BY embedding <=> $1::vector LIMIT $2`, [vec(qe), k]);
  return r.rows;
}

async function backfill(c) {
  await c.query("CREATE EXTENSION IF NOT EXISTS vector");
  await c.query(`CREATE TABLE IF NOT EXISTS wiki_chunk (
    id bigserial PRIMARY KEY, chunk_key text, wiki_key text, title text, entity_ref text, entity_key text,
    wiki_url text, section text, text text, embedding vector(${DIM}))`);
  await c.query("TRUNCATE wiki_chunk RESTART IDENTITY");

  const chunks = JSON.parse(gunzipSync(readFileSync(CHUNKS)).toString("utf8"));
  console.log(`[lore] embedding ${chunks.length} chunks via TEI (${TEI_URL})...`);
  for (let i = 0; i < chunks.length; i += TEI_BATCH) {
    const slice = chunks.slice(i, i + TEI_BATCH);
    const embs = await embed(slice.map(embedText));
    const params = [], vals = [];
    slice.forEach((x, j) => {
      const b = j * 9;
      params.push(x.chunkKey, x.wikiKey, x.title, x.entityRef ?? null, x.entityKey ?? null,
        x.wikiUrl ?? null, x.section, x.text, vec(embs[j]));
      vals.push(`($${b+1},$${b+2},$${b+3},$${b+4},$${b+5},$${b+6},$${b+7},$${b+8},$${b+9}::vector)`);
    });
    await c.query(`INSERT INTO wiki_chunk
      (chunk_key,wiki_key,title,entity_ref,entity_key,wiki_url,section,text,embedding) VALUES ${vals.join(",")}`, params);
    if ((i + TEI_BATCH) % 2048 < TEI_BATCH) console.log(`  ${Math.min(i + TEI_BATCH, chunks.length)}/${chunks.length}`);
  }
  console.log("[lore] building HNSW index (cosine)...");
  await c.query("DROP INDEX IF EXISTS wiki_chunk_embedding_idx");
  await c.query("CREATE INDEX wiki_chunk_embedding_idx ON wiki_chunk USING hnsw (embedding vector_cosine_ops)");
  const n = await c.query("SELECT count(*) n FROM wiki_chunk");
  console.log(`[lore] done — wiki_chunk rows: ${n.rows[0].n}`);
}

const c = pg();
await c.connect();
const qi = process.argv.indexOf("--query");
if (qi >= 0 && process.argv[qi + 1]) {
  for (const row of await retrieve(c, process.argv[qi + 1]))
    console.log(`[${Number(row.sim).toFixed(3)}] ${row.title} · ${row.section} — ${row.snip.replace(/\n/g, " ")}…`);
} else {
  await backfill(c);
}
await c.end();
