// civstudio-chunks.mjs — the player-facing CivStudio knowledge set as wiki_chunk-shaped rows (P5).
// The wiki_chunk corpus is otherwise 100% Anbennar wiki lore; these rows teach the Loremaster about
// CivStudio itself (what the game is + how its systems work), tagged entity_ref='civstudio' so they can
// be refreshed/deleted independently of the wiki corpus. Source is a COMMITTED, reproducible artifact
// (civstudio-engine/src/main/resources/wiki/civstudio-guide.json) baked from the curated docs — so a
// full backfill always includes them and any machine reproduces the same rows.
import { readFileSync } from "node:fs";
import { join } from "node:path";

const GUIDE = process.env.LORE_CIVSTUDIO_GUIDE
  || join(process.cwd(), "..", "civstudio-engine", "src", "main", "resources", "wiki", "civstudio-guide.json");

/** Load the committed CivStudio guide → chunk rows for insertChunks(). Absent = [] (skip, like the wiki gz). */
export function loadCivstudioChunks() {
  let raw;
  try { raw = readFileSync(GUIDE, "utf8"); } catch { return []; }
  const items = JSON.parse(raw);
  return items.map((x, i) => ({
    chunkKey: `civstudio-${String(i).padStart(4, "0")}`,
    wikiKey: "civstudio-guide",
    title: x.title,
    entityRef: "civstudio",
    entityKey: x.entityKey ?? null,
    wikiUrl: x.wikiUrl || "https://civstudio.com",
    section: x.section,
    text: x.text,
  }));
}
