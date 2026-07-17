// Bake the Anbennar country-flag atlas for the web political overlay.
//
//   node web/build-flags.mjs
//
// Emits two committed, run-INDEPENDENT assets (the flag set is static reference art, the sibling of
// build-buildings.mjs / bakeTradeGoodIcons):
//
//   web/assets/flags/flag-atlas.webp   one sprite sheet of every gfx/flags/<TAG>.tga, decoded
//                             (tga.mjs — sharp/libvips can't read TGA), box-downsampled to CELL px
//                             and packed COLS wide.
//   web/flags.js              window.FLAGS = { src, cell, cols, count, index:{ TAG: cellIndex } } —
//                             the per-tag cell index (x/y derived as (i%cols)*cell, floor(i/cols)*cell,
//                             the window.TRADEGOODS.icons shape) the political overlay blits from.
//
// The flag art is fetched on demand from the Anbennar GitLab source (anbennar.mjs, pinned by
// anbennar-source.lock) exactly like the trade-good strip — never vendored. All 1527 flags are baked,
// whether or not the tag owns a shipped province, so any owner resolves.
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { list as anbList, prefetch as anbPrefetch, get as anbGet } from './anbennar.mjs';
import { decodeTga } from './tga.mjs';
import { packSheet } from './icon-bake.mjs';
import sharp from 'sharp';

const WEB = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(WEB, '..');
const OUT_ICONS = path.join(WEB, 'assets', 'flags', 'flag-atlas.webp');
const OUT_MANIFEST = path.join(WEB, 'flags.js');

const CELL = 48;    // flags are 128×128 in source; 48px is plenty for a label chip / legend swatch
const COLS = 40;    // storage-grid width (layout only — the overlay derives x/y from the cell index)

// Box-average an arbitrary w×h RGBA buffer down to a CELL×CELL cell (the icon-bake.mjs resampler,
// inlined so it works off a decoded TGA buffer rather than a DDS file). Pass-through when already CELL².
function downsample(rgba, w, h) {
  if (w === CELL && h === CELL) return Buffer.from(rgba);
  const out = Buffer.alloc(CELL * CELL * 4);
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

// The country tags with a flag: gfx/flags/<TAG>.tga → TAG. Sorted for a stable, diff-friendly atlas.
const files = (await anbList('gfx/flags'))
  .filter(p => /\.tga$/i.test(p))
  .sort();
const tags = files.map(p => path.basename(p, path.extname(p)));
console.log(`Found ${tags.length} flag files under gfx/flags`);

// Warm the cache in parallel so the synchronous anbGet below hits disk (no-op when the .anbennar-cache
// junction already holds them).
await anbPrefetch(files);

const cells = [];         // CELL×CELL RGBA buffers, in placement order
const index = {};         // TAG -> cell index
let missing = 0;
for (const tag of tags) {
  const file = anbGet(`gfx/flags/${tag}.tga`);
  if (!file) { missing++; continue; }
  let img;
  try { img = decodeTga(fs.readFileSync(file)); }   // colour-mapped / 16bpp oddballs throw → skip
  catch { missing++; continue; }
  index[tag] = cells.length;
  cells.push(downsample(img.rgba, img.width, img.height));
}

const { buffer: sheet, width: W, height: H } = packSheet(cells, CELL, COLS);
fs.mkdirSync(path.dirname(OUT_ICONS), { recursive: true });
await sharp(sheet, { raw: { width: W, height: H, channels: 4 } })
  .webp({ quality: 88, alphaQuality: 100, effort: 5 })
  .toFile(OUT_ICONS);
const iconBytes = fs.statSync(OUT_ICONS).size;

const manifest = { src: 'assets/flags/flag-atlas.webp', cell: CELL, cols: COLS, count: cells.length, index };
fs.writeFileSync(OUT_MANIFEST, `window.FLAGS = ${JSON.stringify(manifest)};\n`);

console.log(`Built web/assets/flags/flag-atlas.webp — ${cells.length} flags `
  + `(${W}×${H}, ${(iconBytes / 1024).toFixed(0)} KB), ${missing} skipped`);
console.log(`Built ${path.relative(ROOT, OUT_MANIFEST)} — ${(fs.statSync(OUT_MANIFEST).size / 1024).toFixed(0)} KB`);
