// lore-lib.mjs — shared RAG helpers for the lore chatbot (P5): TEI embeddings, pgvector retrieval, and
// the Claude-grounded answer. Used by backfill-lore.mjs (bake CLI) and lore-service.mjs (served endpoint).
import { readFileSync } from "node:fs";
import { gunzipSync } from "node:zlib";
import { join } from "node:path";
import { Client } from "pg";

export const DIM = 384;
const TEI_BATCH = 32; // TEI default max-client-batch-size
export const TEI_URL = process.env.LORE_TEI_URL || "http://localhost:8085/embed";
const CHUNKS = process.env.LORE_CHUNKS
  || join(process.cwd(), "..", "civstudio-engine", "target", "generated", "wiki", "wiki-chunk.json.gz");

// pgvector DB: LORE_PG_* (local Docker) else DATABASE_* (the Strapi DB — prod civstudio-postgres, which
// has pgvector allow-listed, so wiki_chunk shares the DB with the canonical tables for the hybrid join).
export const pg = () => new Client({
  host: process.env.LORE_PG_HOST || process.env.DATABASE_HOST,
  port: process.env.LORE_PG_PORT || process.env.DATABASE_PORT || 5432,
  database: process.env.LORE_PG_DB || process.env.DATABASE_NAME,
  user: process.env.LORE_PG_USER || process.env.DATABASE_USERNAME,
  password: process.env.LORE_PG_PASSWORD || process.env.DATABASE_PASSWORD,
  ssl: (process.env.LORE_PG_SSL || process.env.DATABASE_SSL) === "true" ? { rejectUnauthorized: false } : undefined,
});

const vec = (a) => "[" + a.join(",") + "]";

/** Embed 1..32 texts via TEI → array of DIM-float vectors. */
export async function embed(texts) {
  const res = await fetch(TEI_URL, { method: "POST", headers: { "content-type": "application/json" },
    body: JSON.stringify({ inputs: texts, truncate: true }) });
  if (!res.ok) throw new Error(`TEI ${res.status}: ${(await res.text()).slice(0, 200)}`);
  return res.json();
}
// A passage carries its own title/section context into the embedding (helps recall on short chunks).
const embedText = (x) => `${x.title} — ${x.section}\n${x.text}`;

/** Top-k passages by cosine similarity, with provenance. */
export async function retrieve(c, question, k = 8) {
  const [qe] = await embed([question]);
  const r = await c.query(
    `SELECT title, section, entity_ref, entity_key, wiki_url, text,
            1 - (embedding <=> $1::vector) AS sim
     FROM wiki_chunk ORDER BY embedding <=> $1::vector LIMIT $2`, [vec(qe), k]);
  return r.rows.map((row) => ({ ...row, sim: Number(row.sim) }));
}

/** Full RAG answer: retrieve → ground claude-haiku-4-5 on the passages → { answer, sources }. */
export async function askClaude(c, question, k = 8) {
  const key = process.env.ANTHROPIC_API_KEY;
  if (!key) throw new Error("ANTHROPIC_API_KEY not set");
  const rows = await retrieve(c, question, k);
  const context = rows.map((r, i) => `[${i + 1}] "${r.title}" (${r.section})\n${r.text}`).join("\n\n---\n\n");
  const res = await fetch("https://api.anthropic.com/v1/messages", {
    method: "POST",
    headers: { "x-api-key": key, "anthropic-version": "2023-06-01", "content-type": "application/json" },
    body: JSON.stringify({
      model: "claude-haiku-4-5", max_tokens: 1024,
      system: "You are an Anbennar lore assistant. Answer using ONLY the numbered lore excerpts provided, "
        + "citing sources inline as [n]. If the excerpts don't contain the answer, say so plainly. Be concise.",
      messages: [{ role: "user", content: `Question: ${question}\n\nLore excerpts:\n\n${context}` }],
    }),
  });
  if (!res.ok) throw new Error(`Anthropic ${res.status}: ${(await res.text()).slice(0, 300)}`);
  const j = await res.json();
  const answer = (j.content || []).filter((b) => b.type === "text").map((b) => b.text).join("");
  const sources = rows.map((r) => ({ title: r.title, section: r.section, wikiUrl: r.wiki_url,
    entityRef: r.entity_ref, entityKey: r.entity_key }));
  return { answer, sources };
}

/** Embed every chunk and (re)load the pgvector wiki_chunk table + HNSW cosine index. */
export async function backfill(c, log = console.log) {
  await c.query("CREATE EXTENSION IF NOT EXISTS vector");
  await c.query(`CREATE TABLE IF NOT EXISTS wiki_chunk (
    id bigserial PRIMARY KEY, chunk_key text, wiki_key text, title text, entity_ref text, entity_key text,
    wiki_url text, section text, text text, embedding vector(${DIM}))`);
  await c.query("TRUNCATE wiki_chunk RESTART IDENTITY");
  const chunks = JSON.parse(gunzipSync(readFileSync(CHUNKS)).toString("utf8"));
  log(`[lore] embedding ${chunks.length} chunks via TEI (${TEI_URL})...`);
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
    if ((i + TEI_BATCH) % 2048 < TEI_BATCH) log(`  ${Math.min(i + TEI_BATCH, chunks.length)}/${chunks.length}`);
  }
  log("[lore] building HNSW index (cosine)...");
  await c.query("DROP INDEX IF EXISTS wiki_chunk_embedding_idx");
  await c.query("CREATE INDEX wiki_chunk_embedding_idx ON wiki_chunk USING hnsw (embedding vector_cosine_ops)");
  const n = await c.query("SELECT count(*) n FROM wiki_chunk");
  log(`[lore] done — wiki_chunk rows: ${n.rows[0].n}`);
}
