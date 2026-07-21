// Build the building-button icon sheet for the web tech-tree view.
//
//   node web/build-buildings.mjs
//
// Structurally the sibling of build-techs.mjs (shared helpers in icon-bake.mjs). Emits two
// committed, run-INDEPENDENT assets (the building set is static reference data):
//
//   web/assets/buildings/building-icons.webp   one sprite sheet of the real C2C building-button
//                              icons, decoded from the DDS art and packed 64×64 per building,
//                              50 columns. Missing-art buildings get no cell (colour-chip
//                              fallback in the tech-tree view).
//   civstudio-server/src/main/resources/buildings-meta.json   the art-coupled per-building
//                              metadata the server can't regenerate — each building's `icon`
//                              sprite rect (a cell in building-icons.webp), keyed by BUILDING_*
//                              id. The server (BuildingBundle, Phase 3) merges this onto the
//                              engine jar's generated/buildings.json and serves it at
//                              GET /api/buildings — the TechBundle/techs-meta pattern.
//
// The building GRAPH itself (ids, names, prereqs, category, cost) is NOT baked here — it ships
// in the engine jar as /buildings.json (BuildingInfoExporter, Phase 1). Only the icon rect,
// which is art-coupled and can't be derived server-side, is committed here.
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { resolveArt, prefetch } from './civ4.mjs';
import { iconPath, iconCell, packSheet } from './icon-bake.mjs';
import { bundleResource } from './content-source.mjs';
import sharp from 'sharp';

const WEB = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(WEB, '..');

const OUT_ICONS = path.join(WEB, 'assets', 'buildings', 'building-icons.webp');
const OUT_META = path.join(ROOT, 'civstudio-server/src/main/resources/buildings-meta.json');

const CELL = 64;   // Civ4 building buttons are 64×64
const COLS = 50;   // sprite-sheet grid width (storage layout only — unrelated to the display grid)

const buildings = bundleResource('/buildings.json');

// Warm the C2C art cache in parallel first, so the synchronous resolveArt below hits the disk
// cache instead of a per-file round trip.
await prefetch({ arts: buildings.map(b => iconPath(b.button)).filter(Boolean) });

const cells = [];             // decoded CELL×CELL RGBA buffers, in placement order
const meta = {};              // BUILDING_* id -> { icon: [x, y, CELL, CELL] }
let missing = 0;
for (const b of buildings) {
  const file = resolveArt(iconPath(b.button));
  const cell = file ? iconCell(file, CELL) : null;
  if (!cell) { missing++; continue; }
  const i = cells.length;
  const x = (i % COLS) * CELL, y = Math.floor(i / COLS) * CELL;
  meta[b.id] = { icon: [x, y, CELL, CELL] };
  cells.push(cell);
}

const { buffer: sheet, width: sheetW, height: sheetH } = packSheet(cells, CELL, COLS);
fs.mkdirSync(path.dirname(OUT_ICONS), { recursive: true });
await sharp(sheet, { raw: { width: sheetW, height: sheetH, channels: 4 } })
  .webp({ quality: 90, alphaQuality: 100, effort: 5 })
  .toFile(OUT_ICONS);
const iconBytes = fs.statSync(OUT_ICONS).size;

fs.mkdirSync(path.dirname(OUT_META), { recursive: true });
fs.writeFileSync(OUT_META, JSON.stringify(meta, null, 0) + '\n');

console.log(`Built ${path.relative(ROOT, OUT_META)} — ${Object.keys(meta).length}/${buildings.length} `
  + `buildings with an icon rect (${(fs.statSync(OUT_META).size / 1024).toFixed(0)} KB)`);
console.log(`Built web/assets/buildings/building-icons.webp — ${cells.length} icons `
  + `(${sheetW}×${sheetH}, ${(iconBytes / 1024).toFixed(0)} KB), ${missing} without art (colour-chip fallback)`);
