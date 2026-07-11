// Build the tech-tree data + icon assets for the web view.
//
//   node web/build-techs.mjs
//
// Two committed, run-INDEPENDENT assets (the tech tree is static reference data, unlike
// build.mjs which needs an output/<seed> run):
//
//   civstudio-engine/src/main/resources/techs-meta.json
//                              the art-coupled per-tech metadata the server can't regenerate —
//                              each tech's `icon` sprite rect (a cell in tech-icons.webp) and its
//                              curated `beaker` colour — keyed by tech Type. The tech GRAPH itself
//                              is NOT baked here anymore: the server serves it straight from the
//                              engine jar's techs.json, merged with this meta, at GET /api/techs
//                              (gzipped, gunzipped in-page via DecompressionStream — the plots.mjs
//                              pattern). This ships in the engine jar, so the server is the single
//                              source of the graph again (the data.js → /api/bundle rationale).
//   web/assets/tech-icons.webp one sprite sheet of the real Civ4 tech-button icons,
//                              decoded from the DDS art and packed CELL×CELL per tech.
//
// Each tech's `Button` field is either a single DDS path or the Civ4 atlas form
// ",<individual>.dds,<atlas>.dds,col,row" — we take the standalone <individual> icon in
// both cases (so no atlas slicing), decode it from the vendored art, and pack it. The
// ~47 techs whose individual icon is a vanilla-BTS file C2C doesn't ship get no `icon`
// field; the page falls back to an advisor-colour chip for them.
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { decodeDds } from './dds.mjs';
import { loadGameFont, symbolRGBA, recolorHue, CELL as GF_CELL } from './gamefont.mjs';
import { resolveArt as civ4ResolveArt, prefetch as civ4Prefetch } from './civ4.mjs';
import sharp from 'sharp';

const WEB = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(WEB, '..');

const SRC = path.join(ROOT, 'civstudio-engine/src/main/resources/techs.json');
// the art-coupled per-tech metadata (icon rects + beaker colour), merged onto techs.json by the
// server (com.civstudio.server.web.TechBundle) at GET /api/techs. Lives in the engine resources
// so it ships in the server jar alongside its techs.json source.
const OUT_META = path.join(ROOT, 'civstudio-engine/src/main/resources/techs-meta.json');
const OUT_ICONS = path.join(WEB, 'assets', 'tech-icons.webp');

const CELL = 64;   // Civ4 tech buttons are 64×64
const COLS = 16;   // sprite-sheet grid width

// resolve an "Art/..." path to a real file, case-insensitively — the tech-button .dds are fetched
// on demand from the C2C source (UnpackedArt/art) and cached; see civ4.mjs / docs/civ4-files.md.
const resolveArt = civ4ResolveArt;

// the standalone individual-icon path from a Button field (before any atlas reference)
function iconPath(button) {
  if (!button) return null;
  const parts = button.split(',');
  return (parts.length >= 5 ? parts[1] : button).trim();
}

// decode a DDS icon to a CELL×CELL RGBA cell (box-average when the source is larger,
// nearest-ish when smaller); null if it can't be read
function iconCell(file) {
  let img;
  try { img = decodeDds(fs.readFileSync(file)); } catch { return null; }
  const { width: w, height: h, rgba } = img;
  if (w === CELL && h === CELL) return rgba;
  const out = new Uint8Array(CELL * CELL * 4);
  const bx = w / CELL, by = h / CELL;
  for (let j = 0; j < CELL; j++)
    for (let i = 0; i < CELL; i++) {
      let r = 0, g = 0, b = 0, a = 0, n = 0;
      const y0 = Math.floor(j * by), y1 = Math.max(y0 + 1, Math.floor((j + 1) * by));
      const x0 = Math.floor(i * bx), x1 = Math.max(x0 + 1, Math.floor((i + 1) * bx));
      for (let y = y0; y < y1 && y < h; y++)
        for (let x = x0; x < x1 && x < w; x++) {
          const o = (y * w + x) * 4;
          r += rgba[o]; g += rgba[o + 1]; b += rgba[o + 2]; a += rgba[o + 3]; n++;
        }
      const d = (j * CELL + i) * 4;
      out[d] = r / n; out[d + 1] = g / n; out[d + 2] = b / n; out[d + 3] = a / n;
    }
  return out;
}

const techs = JSON.parse(fs.readFileSync(SRC, 'utf8'));

// interim: which techs cost GREEN (naval) beakers instead of the blue default. C2C has no
// naval flavour, so this is a curated set until eos models a research currency per tech —
// edit freely. Everything else defaults to blue (the human science tree).
const NAVAL = new Set([
  "TECH_TRAP_FISHING", "TECH_SPEARFISHING", "TECH_FISHING", "TECH_RAFT_BUILDING",
  "TECH_BOAT_BUILDING", "TECH_SAILING", "TECH_SEAFARING", "TECH_NAVAL_WARFARE",
  "TECH_SHIP_BUILDING", "TECH_COMMERCIAL_WHALING", "TECH_CARTOGRAPHY", "TECH_COMPASS",
  "TECH_ASTROLABE", "TECH_NAVIGATION", "TECH_NAVAL_CANNON", "TECH_NAVAL_TACTICS",
]);
for (const t of techs) if (NAVAL.has(t.Type)) t.beaker = "green";

// --- bake the icon sprite sheet, tagging each tech with its cell rect --------------
// Warm the C2C art cache in parallel first (the tech-button .dds + the GameFont atlas) so the
// synchronous resolveArt/loadGameFont below hit the disk cache instead of a per-file round trip.
await civ4Prefetch({
  arts: techs.map(t => iconPath(t.Button)).filter(Boolean),
  files: ['res/Fonts/GameFont_120.tga'],
});
const cells = [];   // decoded CELL×CELL RGBA buffers, in placement order
let missing = 0;
for (const t of techs) {
  const file = resolveArt(iconPath(t.Button));
  const cell = file ? iconCell(file) : null;
  if (!cell) { missing++; continue; }
  const i = cells.length;
  const x = (i % COLS) * CELL, y = Math.floor(i / COLS) * CELL;
  t.icon = [x, y, CELL, CELL];
  cells.push(cell);
}

const rows = Math.max(1, Math.ceil(cells.length / COLS));
const sheetW = COLS * CELL, sheetH = rows * CELL;
const sheet = Buffer.alloc(sheetW * sheetH * 4);
cells.forEach((cell, i) => {
  const ox = (i % COLS) * CELL, oy = Math.floor(i / COLS) * CELL;
  for (let j = 0; j < CELL; j++) {
    const srcRow = j * CELL * 4;
    const dstRow = ((oy + j) * sheetW + ox) * 4;
    cell.copy ? cell.copy(sheet, dstRow, srcRow, srcRow + CELL * 4)
              : sheet.set(cell.subarray(srcRow, srcRow + CELL * 4), dstRow);
  }
});

await sharp(sheet, { raw: { width: sheetW, height: sheetH, channels: 4 } })
  .webp({ quality: 90, alphaQuality: 100, effort: 5 })
  .toFile(OUT_ICONS);
const iconBytes = fs.statSync(OUT_ICONS).size;

// --- mint the beaker cost icons from the one GameFont beaker glyph -------------------
// CivStudio's research currencies are the same beaker recoloured: blue (science, the
// human tree's default), green (naval research), red (converted from hammers), yellow
// (race-specific). The green GameFont beaker's liquid is recoloured to each hue; the
// glass is left grey.
const beaker = symbolRGBA(loadGameFont(ROOT), 'BEAKER');
if (beaker)
  for (const [name, hue] of [["tech-beaker", 210], ["tech-beaker-green", 135],
                             ["tech-beaker-red", 2], ["tech-beaker-yellow", 48]])
    await sharp(recolorHue(beaker, hue), { raw: { width: GF_CELL, height: GF_CELL, channels: 4 } })
      .webp({ quality: 90, alphaQuality: 100, effort: 5 })
      .toFile(path.join(WEB, 'assets', `${name}.webp`));

// --- emit the art-coupled per-tech metadata (icon rects + beaker colour) -------------
// Only the two fields the server can't derive from techs.json; keyed by tech Type. The server
// merges these onto techs.json and gzips the result on demand (TechBundle) — so the drift-prone
// graph stays single-sourced in the engine jar and only this stable art metadata is committed.
const meta = {};
for (const t of techs) {
  const m = {};
  if (t.icon) m.icon = t.icon;
  if (t.beaker) m.beaker = t.beaker;
  if (Object.keys(m).length) meta[t.Type] = m;
}
fs.writeFileSync(OUT_META, JSON.stringify(meta, null, 0) + '\n');

console.log(`Built ${path.relative(ROOT, OUT_META)} — ${Object.keys(meta).length}/${techs.length} techs `
  + `with icon/beaker metadata (${(fs.statSync(OUT_META).size / 1024).toFixed(0)} KB)`);
console.log(`Built web/assets/tech-icons.webp — ${cells.length} icons `
  + `(${sheetW}×${sheetH}, ${(iconBytes / 1024).toFixed(0)} KB), ${missing} without art (colour-chip fallback)`);
