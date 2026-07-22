// lore-lib.mjs — shared RAG helpers for the lore chatbot (P5): TEI embeddings, pgvector retrieval, and
// the Claude-grounded answer. Used by backfill-lore.mjs (bake CLI) and lore-service.mjs (served endpoint).
import { readFileSync } from "node:fs";
import { gunzipSync } from "node:zlib";
import { join } from "node:path";
import { Client } from "pg";
import { loadCivstudioChunks } from "./civstudio-chunks.mjs";

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

// C4 — agentic RAG: Claude drives its own retrieval via a search_lore tool (deciding what and how many
// times to search) instead of a fixed top-K stuff. Better for multi-part questions and cross-referencing.
// (A lookup_entity tool over the canonical tables — the hybrid join — is a prod-only add: locally wiki_chunk
// lives in its own DB without the country/culture tables. See docs/lore-chatbot-plan.md.)
export async function askAgent(c, question, maxTurns = 5) {
  const key = process.env.ANTHROPIC_API_KEY;
  if (!key) throw new Error("ANTHROPIC_API_KEY not set");
  const tools = [{
    name: "search_lore",
    description: "Search the Anbennar wiki lore for passages relevant to a query. Call it (repeatedly, with "
      + "focused queries) to gather what you need before answering; each result names its source article.",
    input_schema: { type: "object", properties: { query: { type: "string", description: "a focused search query" } }, required: ["query"] },
  }];
  const system = "You are an Anbennar lore assistant. Use the search_lore tool to find relevant wiki passages, "
    + "then answer the question grounded in what you found, citing article titles inline. Search each part of a "
    + "multi-part question. If the lore doesn't cover something, say so plainly. Be concise.";
  const messages = [{ role: "user", content: question }];
  const sources = new Map(); // title → wikiUrl, deduped across searches
  const call = async () => {
    const res = await fetch("https://api.anthropic.com/v1/messages", {
      method: "POST",
      headers: { "x-api-key": key, "anthropic-version": "2023-06-01", "content-type": "application/json" },
      body: JSON.stringify({ model: "claude-haiku-4-5", max_tokens: 1024, system, tools, messages }),
    });
    if (!res.ok) throw new Error(`Anthropic ${res.status}: ${(await res.text()).slice(0, 300)}`);
    return res.json();
  };
  for (let turn = 0; turn < maxTurns; turn++) {
    const j = await call();
    messages.push({ role: "assistant", content: j.content });
    if (j.stop_reason !== "tool_use") {
      const answer = (j.content || []).filter((b) => b.type === "text").map((b) => b.text).join("");
      return { answer, sources: [...sources].map(([title, wikiUrl]) => ({ title, wikiUrl })) };
    }
    const results = [];
    for (const b of j.content.filter((x) => x.type === "tool_use")) {
      if (b.name === "search_lore") {
        const rows = await retrieve(c, String(b.input.query || ""), 6);
        rows.forEach((r) => sources.set(r.title, r.wiki_url));
        results.push({ type: "tool_result", tool_use_id: b.id,
          content: rows.length ? rows.map((r) => `[${r.title} · ${r.section}]\n${r.text}`).join("\n\n") : "(no matching lore)" });
      } else {
        results.push({ type: "tool_result", tool_use_id: b.id, content: "(unknown tool)", is_error: true });
      }
    }
    messages.push({ role: "user", content: results });
  }
  return { answer: "(reached the search-step limit before answering)", sources: [...sources].map(([title, wikiUrl]) => ({ title, wikiUrl })) };
}

/** Embed + INSERT a chunk array into wiki_chunk (no truncate, no index rebuild). Chunk fields:
 *  chunkKey, wikiKey, title, entityRef, entityKey, wikiUrl, section, text. */
export async function insertChunks(c, chunks, log = console.log) {
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
}

async function ensureSchema(c) {
  await c.query("CREATE EXTENSION IF NOT EXISTS vector");
  await c.query(`CREATE TABLE IF NOT EXISTS wiki_chunk (
    id bigserial PRIMARY KEY, chunk_key text, wiki_key text, title text, entity_ref text, entity_key text,
    wiki_url text, section text, text text, embedding vector(${DIM}))`);
}

/** Embed the whole corpus — Anbennar wiki lore + the CivStudio guide — and (re)load wiki_chunk + its
 *  HNSW cosine index. The CivStudio rows ride along so a full rebuild always includes them. */
export async function backfill(c, log = console.log) {
  await ensureSchema(c);
  await c.query("TRUNCATE wiki_chunk RESTART IDENTITY");
  const wiki = JSON.parse(gunzipSync(readFileSync(CHUNKS)).toString("utf8"));
  const civ = loadCivstudioChunks();
  log(`[lore] embedding ${wiki.length} wiki + ${civ.length} CivStudio chunks via TEI (${TEI_URL})...`);
  await insertChunks(c, wiki, log);
  await insertChunks(c, civ, log);
  log("[lore] building HNSW index (cosine)...");
  await c.query("DROP INDEX IF EXISTS wiki_chunk_embedding_idx");
  await c.query("CREATE INDEX wiki_chunk_embedding_idx ON wiki_chunk USING hnsw (embedding vector_cosine_ops)");
  const n = await c.query("SELECT count(*) n FROM wiki_chunk");
  log(`[lore] done — wiki_chunk rows: ${n.rows[0].n}`);
}

/** Refresh ONLY the CivStudio guide rows (entity_ref='civstudio') in place — no wiki re-embed, no
 *  truncate, no index rebuild. For fast prod updates once the full corpus already exists. */
export async function appendCivstudio(c, log = console.log) {
  await ensureSchema(c);
  const civ = loadCivstudioChunks();
  await c.query("DELETE FROM wiki_chunk WHERE entity_ref = 'civstudio'");
  log(`[lore] refreshing ${civ.length} CivStudio guide chunks via TEI (${TEI_URL})...`);
  await insertChunks(c, civ, log);
  const n = await c.query("SELECT count(*) n FROM wiki_chunk WHERE entity_ref = 'civstudio'");
  log(`[lore] done — civstudio rows: ${n.rows[0].n}`);
}
