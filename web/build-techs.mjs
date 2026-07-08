// Build the tech-tree data + icon assets for the web view.
//
//   node web/build-techs.mjs
//
// Two committed, run-INDEPENDENT assets (the tech tree is static reference data, unlike
// build.mjs which needs an output/<seed> run):
//
//   web/assets/techs.pack     the engine's tech graph (src/main/resources/techs.json —
//                              produced by com.civstudio.tech.export.TechInfoConverter,
//                              trimmed to the Prehistoric→Renaissance techs eos models,
//                              carrying every field + the resolved English name/help/
//                              quote), enriched with a per-tech `icon` rect and gzipped.
//                              The page fetches it and gunzips in-browser via
//                              DecompressionStream (the plots.mjs pattern).
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
import zlib from 'node:zlib';
import { fileURLToPath } from 'node:url';
import { decodeDds } from './dds.mjs';
import { loadGameFont, symbolRGBA, recolorHue, CELL as GF_CELL } from './gamefont.mjs';
import sharp from 'sharp';

const WEB = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(WEB, '..');

const SRC = path.join(ROOT, 'src/main/resources/techs.json');
// gzip bytes, but named .pack (served as application/octet-stream, like plots.pack) — a
// .gz extension makes Azure SWA/CDN set Content-Encoding: gzip, which collides with the
// in-page DecompressionStream and hard-fails the fetch (ERR_CONTENT_DECODING_FAILED).
const OUT_PACK = path.join(WEB, 'assets', 'techs.pack');
const OUT_ICONS = path.join(WEB, 'assets', 'tech-icons.webp');

const CELL = 64;   // Civ4 tech buttons are 64×64
const COLS = 16;   // sprite-sheet grid width

// --- resolve an "Art/..." path to a real file, case-insensitively (build.mjs pattern):
// the web-baked art lives non-LFS under data/civ4/assets; the full UnpackedArt/art LFS
// tree (where the tech buttons were copied) is the fallback.
function resolveArt(artPath) {
  if (!artPath) return null;
  const rel = artPath.replace(/^Art\//i, '').split('/');
  return resolveUnder(path.join(ROOT, 'data', 'civ4', 'assets'), rel)
      || resolveUnder(path.join(ROOT, 'UnpackedArt', 'art'), rel);
}
function resolveUnder(base, rel) {
  let dir = base;
  for (const seg of rel) {
    let ents;
    try { ents = fs.readdirSync(dir); } catch { return null; }
    const hit = ents.find(e => e.toLowerCase() === seg.toLowerCase());
    if (!hit) return null;
    dir = path.join(dir, hit);
  }
  return dir;
}

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

// --- gzip the enriched graph (minified; the committed src techs.json stays pretty) ---
const minified = JSON.stringify(techs);
const gz = zlib.gzipSync(Buffer.from(minified, 'utf8'), { level: 9 });
fs.mkdirSync(path.dirname(OUT_PACK), { recursive: true });
fs.writeFileSync(OUT_PACK, gz);

console.log(`Built web/assets/techs.pack — ${techs.length} techs, `
  + `${(minified.length / 1024).toFixed(0)} KB JSON → ${(gz.length / 1024).toFixed(0)} KB gzipped`);
console.log(`Built web/assets/tech-icons.webp — ${cells.length} icons `
  + `(${sheetW}×${sheetH}, ${(iconBytes / 1024).toFixed(0)} KB), ${missing} without art (colour-chip fallback)`);
