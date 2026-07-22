// backfill-lore.mjs — CLI over lore-lib (P5 C1). Run from studio/ (for its `pg`):
//   node scripts/backfill-lore.mjs                 backfill: embed chunks → pgvector wiki_chunk + HNSW
//   node scripts/backfill-lore.mjs --civstudio     refresh ONLY the CivStudio guide rows (fast, in place)
//   node scripts/backfill-lore.mjs --query "…"     retrieval only (no key)
//   node scripts/backfill-lore.mjs --ask "…"       grounded Claude answer (needs ANTHROPIC_API_KEY)
// DB target: LORE_PG_* (local Docker pgvector) else DATABASE_* (prod civstudio-postgres). See lore-lib.mjs.
import "dotenv/config";
import { pg, retrieve, askAgent, backfill, appendCivstudio } from "./lore-lib.mjs";

const arg = (f) => { const i = process.argv.indexOf(f); return i >= 0 ? process.argv[i + 1] : null; };
const has = (f) => process.argv.includes(f);
const askQ = arg("--ask"), queryQ = arg("--query");

const c = pg();
await c.connect();
try {
  if (askQ) {
    const { answer, sources } = await askAgent(c, askQ);
    console.log("\n" + answer + "\n\nSources:");
    sources.forEach((s, i) => console.log(`  [${i + 1}] ${s.title} — ${s.wikiUrl}`));
  } else if (queryQ) {
    for (const r of await retrieve(c, queryQ))
      console.log(`[${r.sim.toFixed(3)}] ${r.title} · ${r.section} — ${r.text.slice(0, 100).replace(/\n/g, " ")}…`);
  } else if (has("--civstudio")) {
    await appendCivstudio(c);
  } else {
    await backfill(c);
  }
} catch (e) {
  console.error(e.message);
  process.exitCode = 1;
} finally {
  await c.end();
}
