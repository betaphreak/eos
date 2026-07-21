# Plan: the Anbennar lore chatbot (RAG) on anbennar.civstudio.com

**Status:** **PLAN — future (P5 of the wiki lore import)** (2026-07-21). A grounded Q&A assistant that
answers Anbennar lore questions from the imported wiki lore **and** the structured Strapi facts. This is
**not built yet** and depends on the lore corpus being seeded (see
[`docs/wiki-lore-import-plan.md`](wiki-lore-import-plan.md) P1–P2). This doc specs the architecture so the
near-term import choices (sectioned markdown, per-chunk provenance, wiki→entity correlation) pay off here
without a rewrite. Companion to [`docs/mcp-server.md`](mcp-server.md) (the Spring AI surface this reuses)
and [`docs/studio-datamodel-rebuild-plan.md`](studio-datamodel-rebuild-plan.md).

## The organising idea

The best RAG solution for this data is **the least new infrastructure**: the corpus is tiny (~2,511
articles → ~15–30k section-chunks), so it does **not** warrant a dedicated vector DB, a reranker, or a
Python RAG framework (LangChain/LlamaIndex). Every piece already exists in the stack:

- **Vector store** = **pgvector** in the Strapi Postgres we already run. Keeps the lore in the *same*
  database as the canonical `country`/`province`/`culture` rows, so one query can blend prose lore with
  structured facts (the hybrid retrieval below) — a standalone vector DB cannot.
- **Embeddings** = an open **HuggingFace** model served via **Text Embeddings Inference (TEI)**. This is
  the *one* place we look outside Claude — **Anthropic has no embeddings API** (its model line is
  chat-only). TEI is one HTTP endpoint both the Node seeder and the Java server call, guaranteeing the
  same vector space on the write and read paths.
- **Generation** = **Claude** via the Anthropic Java SDK / **Spring AI**, which already runs in
  `civstudio-server` (the MCP server). `claude-haiku-4-5` ($1/$5 per Mtok, 200K ctx) is the sweet spot for
  lore Q&A; `claude-sonnet-5` for deeper reasoning. **Not** a HuggingFace chat model.
- **Attribution** = Claude's native document **citations** (`citations: {enabled: true}`) — answers come
  back with per-chunk source attribution, which satisfies the CC BY-SA obligation *and* makes them
  verifiable.

HuggingFace's role is precisely the embedding model — nothing more. No RAG framework, no HF chat model.

## The retrieval substrate — pgvector

pgvector adds a `vector` column type, distance operators, and ANN indexes to Postgres. Similarity search
becomes plain SQL.

### Schema — a derived index table (not a Strapi content type)

```sql
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE wiki_chunk (
  id           bigserial PRIMARY KEY,
  wiki_key     text NOT NULL,      -- the source wiki-article
  entity_type  text,               -- 'country' | 'province' | 'culture' | ...
  entity_key   text,               -- correlated tag/key (e.g. 'A04') → the join to canonical data
  section      text,               -- the ==Heading== this passage came from
  wiki_url     text NOT NULL,      -- provenance → CC BY-SA credit + Claude citation
  content      text NOT NULL,      -- the cleaned-markdown passage
  embedding    vector(384)         -- bge-small = 384 dims (must match the chosen model)
);
CREATE INDEX ON wiki_chunk USING hnsw (embedding vector_cosine_ops);
```

`vector(384)` is fixed to the embedding model's output width; HNSW is the modern default and is instant
at this corpus size. Normalize embeddings and use cosine (`vector_cosine_ops` + the `<=>` operator).

**Strapi caveat — manage this table outside the content-type layer.** Strapi's Document Service and
`gen-schemas.mjs` don't understand a `vector` column, so `wiki_chunk` is an **infrastructure table**
created by a migration and written/queried through the underlying knex/`pg` connection (or directly from
the Java server, which also holds the Postgres connection) — **not** a `wiki-*` collection type. The
`wiki-*` content types stay the authored/seeded lore; `wiki_chunk` is the *derived* retrieval index built
from them (rebuilt on a lore-`contentVersion` bump, keeping reproducibility intact).

### Write path (bake/seed time)

The chunker splits each article's cleaned markdown on `==section==` headers (P0 already preserves them),
sends each passage to the TEI endpoint, and inserts the row with its 384-float vector, carrying
`wiki_url` + the correlated `entity_key` through.

### Read path (query time)

The server embeds the **user's question** through the *same* TEI endpoint, then:

```sql
SELECT content, wiki_url, entity_type, entity_key
FROM   wiki_chunk
ORDER  BY embedding <=> $1     -- $1 = the question embedding; cosine distance
LIMIT  6;
```

Top-K semantically nearest passages in milliseconds — the HNSW index avoids a full scan.

### Hybrid retrieval — the payoff of correlation

Because chunks and canonical rows share one database, SQL can constrain the vector search with structured
facts in a single query — e.g. "only lore from countries in Cannor":

```sql
SELECT c.content, c.wiki_url
FROM   wiki_chunk c
JOIN   countries co ON co.tag = c.entity_key
JOIN   provinces p  ON p.owner = co.id
JOIN   regions r    ON p.region = r.id AND r.key = 'cannor'
ORDER  BY c.embedding <=> $1
LIMIT  6;
```

This is why the wiki→entity relations (import P2) matter, and why pgvector-in-Postgres beats a standalone
vector store.

## Generation — Claude via Spring AI

The top-K rows become `document` content blocks with `citations: {enabled: true}`; Claude answers grounded
in them and returns which chunk each sentence came from, carrying `wiki_url` into the answer. SQL is the
"R"; Claude is the "G". At this corpus size some queries can even skip the vector step and pass the top
candidates directly.

**Two capabilities we already have make this stronger than plain vector-RAG:**

1. **Native citations** — attribution and verifiability for free, from the same `wiki_url` field the
   licence already requires.
2. **The MCP server** — it already exposes the live sim/structured data as LLM tools. A Claude
   tool-calling agent can vector-search the lore **and** call MCP tools for structured/live facts
   ("who owns Anbenncóst right now"), grounding answers in both prose lore and canonical data.

## Serving surface

A chat endpoint on `civstudio-server` (sibling of the MCP endpoint) behind `anbennar.civstudio.com`;
thin web chat UI in `web/`. Reuses the existing auth. Reproducibility still rides `contentVersion`
(embeddings rebuilt on a lore-version bump).

## Phases (when P5 begins)

- **C1 — substrate. ✅ SHIPPED + PROVEN (retrieval works end-to-end).**
  - **Chunker** (`WikiChunker`/`WikiChunkExporter`): section-chunks the committed `wiki-article.json.gz`
    into **12,698 passages** (avg 651 chars, hard-capped at `MAX_CHARS`=1200), each with provenance
    (`wikiKey`/`entityRef`/`entityKey`/`wikiUrl`). Chunks are build-scratch, re-derived at embed time.
  - **Embeddings — decided:** self-host **bge-small-en-v1.5 (384-dim) via HuggingFace TEI** (no key,
    on-infra). Query and backfill both hit one `/embed` HTTP endpoint.
  - **Backfill** (`studio/scripts/backfill-lore.mjs`): embeds the chunks via TEI → loads `wiki_chunk`
    (`vector(384)` + HNSW cosine index). DB target is `LORE_PG_*` (local) else the `DATABASE_*` Strapi DB
    (prod) — `wiki_chunk` lives in the **same** DB as the canonical tables so the hybrid join works.
    `--query "…"` does an ad-hoc retrieval.
  - **pgvector — enabled:** `azure.extensions=VECTOR` set on **civstudio-postgres** (prod). Local Strapi
    Postgres lacks the extension, so dev uses Docker on Rancher: `civ-pgv` (pgvector/pgvector:pg16 :5433)
    + `civ-tei` (TEI cpu, bge-small, :8085).
  - **Proven:** 12,698 chunks embedded + HNSW-indexed; queries return the right passages —
    *"Who founded the Empire of Anbennar?"* → **0.850** "Empire of Anbennar · Founding — established 1221
    at the Grand Summit of Aranthíl". *Remaining for prod:* deploy TEI as an Azure Container App +
    `CREATE EXTENSION vector` on civstudio-postgres + run the backfill (a workflow, like seed-studio.yml).
- **C2 — retrieval API.** Server-side embed-question → pgvector top-K (+ hybrid variants over canonical
  rows). No LLM yet — validate retrieval quality on a question set.
- **C3 — generation.** Spring AI + Claude (`claude-haiku-4-5`) with document citations; a `/api/lore/ask`
  endpoint returning answer + cited `wiki_url`s.
- **C4 — tool-calling.** Let the agent also call MCP tools so structured/live facts blend with lore.
- **C5 — web chat UI** on anbennar.civstudio.com.

## Decisions to make at C1

- **Self-hosted embeddings (HF via TEI) vs hosted (Voyage/OpenAI).** Self-host is free at this scale and
  keeps everything on-infra — the recommendation; hosted is zero-ops. Whichever, pin the model (the
  vector space is part of reproducibility — re-embedding on a model change is a full rebuild).
- **Embedding model** — `bge-small-en-v1.5` (384-dim, fast) is plenty for a small English corpus;
  `nomic-embed-text` if longer per-chunk context helps.
- **Generation model** — `claude-haiku-4-5` default; `claude-sonnet-5` if lore reasoning needs it.

## Data source & licence

The chatbot redistributes Anbennar Fandom lore (**CC BY-SA**, [`docs/wiki-lore-import-plan.md`](wiki-lore-import-plan.md)).
Every answer **must** surface attribution: retain `wiki_url` on every `wiki_chunk`, and render the cited
source links in the chat UI. Claude's document citations make this the default path rather than an add-on.
