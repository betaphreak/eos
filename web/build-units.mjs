// Build the unit-button + unit-combat-category icon sheets for the web tech-tree view.
//
//   node web/build-units.mjs
//
// The units sibling of build-buildings.mjs (shared helpers in icon-bake.mjs). Emits two sprite
// sheets and two per-id metadata files — all committed, run-INDEPENDENT (the unit set is static
// reference data, docs/c2c-unit-import.md):
//
//   web/assets/units/unit-icons.webp          the real C2C unit-button icons, decoded from the
//                              DDS art and packed 64×64 per unit, 50 columns. Missing-art units
//                              get no cell (colour-chip fallback in the tech-tree view).
//   web/assets/units/unit-combat-icons.webp    the ~28 functional UnitCombat CATEGORY icons
//                              (Art/.../categories/*.dds) — the grouping icons the tech-tree unit
//                              row uses, packed the same way, keyed by UNITCOMBAT_* id.
//   civstudio-server/src/main/resources/units-meta.json         per-unit `icon` sprite rect (a
//                              cell in unit-icons.webp), keyed by UNIT_* id.
//   civstudio-server/src/main/resources/unit-combats-meta.json  per-class `icon` sprite rect (a
//                              cell in unit-combat-icons.webp), keyed by UNITCOMBAT_* id.
//
// The server (UnitBundle, Phase 3) merges each meta onto the engine jar's generated units.json /
// unit-combats.json and serves it at GET /api/units — the TechBundle/techs-meta pattern. Only the
// icon rect (art-coupled, not derivable server-side) is committed here; the unit GRAPH ships in
// the engine jar (UnitInfoExporter, Phase 1).
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { resolveArt, prefetch } from './civ4.mjs';
import { iconPath, iconCell, packSheet } from './icon-bake.mjs';
import { bundleResource } from './content-source.mjs';
import sharp from 'sharp';

const WEB = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(WEB, '..');

const OUT_UNIT_ICONS = path.join(WEB, 'assets', 'units', 'unit-icons.webp');
const OUT_COMBAT_ICONS = path.join(WEB, 'assets', 'units', 'unit-combat-icons.webp');
const OUT_UNIT_META = path.join(ROOT, 'civstudio-server/src/main/resources/units-meta.json');
const OUT_COMBAT_META = path.join(ROOT, 'civstudio-server/src/main/resources/unit-combats-meta.json');

const CELL = 64;   // Civ4 unit buttons are 64×64
const COLS = 50;   // sprite-sheet grid width (storage layout only — unrelated to the display grid)

const units = bundleResource('/units.json');
const combats = bundleResource('/unit-combats.json');

// Warm the C2C art cache in parallel first, so the synchronous resolveArt below hits the disk
// cache instead of a per-file round trip.
await prefetch({
  arts: [...units.map(u => iconPath(u.button)),
         ...combats.map(c => iconPath(c.categoryButton))].filter(Boolean),
});

// Decode each item's button into a CELL×CELL cell, packing placement order into `meta[id].icon`;
// items whose art can't be read are counted as `missing` and get no cell (colour-chip fallback).
function bakeCells(items, buttonKey) {
  const cells = [];
  const meta = {};
  let missing = 0;
  for (const it of items) {
    const file = resolveArt(iconPath(it[buttonKey]));
    const cell = file ? iconCell(file, CELL) : null;
    if (!cell) { missing++; continue; }
    const i = cells.length;
    const x = (i % COLS) * CELL, y = Math.floor(i / COLS) * CELL;
    meta[it.id] = { icon: [x, y, CELL, CELL] };
    cells.push(cell);
  }
  return { cells, meta, missing };
}

async function writeSheet(cells, outFile) {
  const { buffer: sheet, width, height } = packSheet(cells, CELL, COLS);
  fs.mkdirSync(path.dirname(outFile), { recursive: true });
  await sharp(sheet, { raw: { width, height, channels: 4 } })
    .webp({ quality: 90, alphaQuality: 100, effort: 5 })
    .toFile(outFile);
  return { width, height, bytes: fs.statSync(outFile).size };
}

function writeMeta(meta, outFile) {
  fs.mkdirSync(path.dirname(outFile), { recursive: true });
  fs.writeFileSync(outFile, JSON.stringify(meta, null, 0) + '\n');
}

const u = bakeCells(units, 'button');
const c = bakeCells(combats, 'categoryButton');

const uSheet = await writeSheet(u.cells, OUT_UNIT_ICONS);
const cSheet = await writeSheet(c.cells, OUT_COMBAT_ICONS);
writeMeta(u.meta, OUT_UNIT_META);
writeMeta(c.meta, OUT_COMBAT_META);

console.log(`Built ${path.relative(ROOT, OUT_UNIT_META)} — ${Object.keys(u.meta).length}/${units.length} `
  + `units with an icon rect (${(fs.statSync(OUT_UNIT_META).size / 1024).toFixed(0)} KB)`);
console.log(`Built web/assets/units/unit-icons.webp — ${u.cells.length} icons `
  + `(${uSheet.width}×${uSheet.height}, ${(uSheet.bytes / 1024).toFixed(0)} KB), `
  + `${u.missing} without art (colour-chip fallback)`);
console.log(`Built ${path.relative(ROOT, OUT_COMBAT_META)} — ${Object.keys(c.meta).length}/${combats.length} `
  + `UnitCombat classes with a category icon`);
console.log(`Built web/assets/units/unit-combat-icons.webp — ${c.cells.length} icons `
  + `(${cSheet.width}×${cSheet.height}, ${(cSheet.bytes / 1024).toFixed(0)} KB), `
  + `${c.missing} without art (colour-chip fallback)`);
