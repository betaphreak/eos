// Build the caravan-migration dashboard's data from a recorded run.
//
//   node web/build.mjs [seed]        (default seed: 24601, ParallelCaravansTest)
//
// Reads output/<seed>/by-caravan/*-CaravanMarch.csv and the committed province
// map (src/main/resources/map/provinces.json) + outlines (borders.json), distils
// them into one JSON bundle written to web/data.js (which index.html loads), and
// bakes a dark-tinted crop of the real EU4 terrain raster (data/anbennar/terrain.bmp)
// into a real image asset at web/assets/terrain-<seed>.png that the page references —
// the image is never inlined into the data. The output/ run must exist first —
// generate it by running the caravan scenario (e.g. ParallelCaravansTest) for that seed.
import fs from 'node:fs';
import path from 'node:path';
import zlib from 'node:zlib';
import { fileURLToPath } from 'node:url';
import { decodeDds } from './dds.mjs';

const WEB = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(WEB, '..');
const SEED = process.argv[2] || '24601';
const DIR = path.join(ROOT, 'output', SEED, 'by-caravan');

if (!fs.existsSync(DIR)) {
  console.error(`No caravan journals at ${path.relative(ROOT, DIR)} — run the scenario for seed ${SEED} first.`);
  process.exit(1);
}

const allProv = JSON.parse(fs.readFileSync(path.join(ROOT, 'src/main/resources/map/provinces.json'), 'utf8'));
const byId = new Map(allProv.map(p => [p.id, p]));
const nameOf = p => (p ? p.name : '?');
const idOf = s => { const m = s.match(/\((\d+)\)\s*$/); return m ? +m[1] : null; };

const files = fs.readdirSync(DIR).filter(f => /-CaravanMarch\.csv$/.test(f)).sort();
const journeys = [];
const visited = new Set();
const traffic = new Map();     // province id -> caravan-days spent there (across all bands)

for (const file of files) {
  const dest = file.replace(/^[^-]+-/, '').replace(/-CaravanMarch\.csv$/, '');
  const rows = fs.readFileSync(path.join(DIR, file), 'utf8').split(/\r?\n/).filter(Boolean);
  const col = Object.fromEntries(rows[0].split(',').map((h, i) => [h, i]));
  const data = rows.slice(1).map(r => r.split(','));

  const keys = [];
  let lastProv = null;
  data.forEach((f, i) => {
    const pid = idOf(f[col.Province]);
    visited.add(pid);
    traffic.set(pid, (traffic.get(pid) || 0) + 1);   // one row = one caravan-day in that province
    if (pid !== lastProv || i === 0 || i === data.length - 1) {
      const p = byId.get(pid);
      keys.push({
        date: f[col.Date], pid, prov: nameOf(p),
        lat: p ? +p.lat.toFixed(3) : null, lon: p ? +p.lon.toFixed(3) : null,
        band: +f[col.BandSize] || 0, larder: Math.round(+f[col.Larder] || 0),
        cargo: +f[col.Cargo] || 0, daylight: +f[col.DaylightH] || 0,
        camp: (f[col.Camp] || '').trim(),
      });
      lastProv = pid;
    }
  });
  const last = data[data.length - 1];
  journeys.push({
    dest, destId: idOf(last[col.Province]),
    startDate: data[0][col.Date], endDate: last[col.Date], days: data.length,
    provinceCount: new Set(data.map(f => idOf(f[col.Province]))).size,
    cargoFinal: +last[col.Cargo] || 0,
    carryingFinal: (last[col.Carrying] || '').replace(/"/g, '').trim(),
    larderFinal: Math.round(+last[col.Larder] || 0),
    bandFinal: +last[col.BandSize] || 0,
    keys,
  });
}

// WorldMap: ship every LAND province (the whole world), not just the caravan crop —
// the caravan run only supplies the optional Caravan-mode overlay (routes/heat).
const sub = new Set(allProv.filter(p => p.type === "LAND").map(p => p.id));

// canonical province outlines (source-pixel rings), attached to the displayed subset
const borders = JSON.parse(fs.readFileSync(path.join(ROOT, 'src/main/resources/map/borders.json'), 'utf8'));
const ringsById = new Map(borders.map(b => [b.id, b.rings]));

const provinces = [...sub].map(id => byId.get(id)).filter(Boolean).map(p => ({
  id: p.id, name: p.name, lat: +p.lat.toFixed(3), lon: +p.lon.toFixed(3),
  plots: p.plots, type: p.type, region: p.region,
  nb: p.neighbors.filter(n => sub.has(n)),
  days: traffic.get(p.id) || 0,          // caravan-days spent here (0 for context provinces)
  rings: ringsById.get(p.id) || null,    // outline in source pixels (null for sea/lake)
}));
const maxDays = Math.max(1, ...provinces.map(p => p.days));

// origin = the shared first province of the journeys
const originId = journeys[0].keys[0].pid;
const origin = byId.get(originId);
const scenario = journeys.length > 1 ? 'ParallelCaravansTest' : 'DhenijansarToWexkeepTest';
const allDates = journeys.flatMap(j => [j.startDate, j.endDate]).sort();

// ---- bake the dark terrain background from the real EU4 raster ----
// provinces.json derived each province's coordinates from terrain.bmp pixels:
//   lon = (cx - xmin) / (xmax - xmin) * 360 - 180   (linear in pixel x; xmin≈0, xmax≈W-1)
//   lat = mercatorLatitude(pixel y)                 (web-mercator in pixel y)
// so lon/lat invert back to the exact source pixel, and a crop aligns 1:1 with
// the province dots as long as the page projects with the same two formulas.
const map = bakeTerrain(provinces);

// per-plot terrain zoom layer (a base WorldMap layer the Caravan View draws over):
// ship each displayed province's canonical plot grid as a lazy-loadable JS file, and
// expose the terrain display colours the page tints plots with (docs §10). Slice B
// also bakes a real ground-texture atlas the page draws per plot at deep zoom.
const terrainColors = terrainDisplayColors(terrainRealColors());
const terrainLayer = terrainLayerOrders();   // TERRAIN_* -> Civ4 LayerOrder (drives edge blending)
const terrainTiles = bakeTerrainTiles(terrainColors);
const plotsShipped = shipPlots(provinces);

const bundle = {
  meta: {
    seed: +SEED, scenario,
    origin: { id: originId, name: origin.name, lat: +origin.lat.toFixed(3), lon: +origin.lon.toFixed(3), region: origin.region },
    dateStart: allDates[0], dateEnd: allDates[allDates.length - 1], maxDays,
  },
  provinces, journeys, map, terrainColors, terrainLayer, terrainTiles,
};

// the run's data as a plain script the page (index.html) loads alongside the
// terrain image asset — the image stays a binary file, never inlined into the data.
const terrainBytes = map.bytes; delete map.bytes;
const dataJs = `window.BUNDLE = ${JSON.stringify(bundle)};\n`;
fs.writeFileSync(path.join(WEB, 'data.js'), dataJs);

console.log(`Built web/data.js (${(dataJs.length / 1024).toFixed(0)} KB) + web/${map.src} (${(terrainBytes / 1024).toFixed(0)} KB) from seed ${SEED}`);
console.log(`  ${journeys.length} journeys · ${provinces.length} provinces · ${bundle.meta.dateStart} → ${bundle.meta.dateEnd}`);
console.log(`  terrain crop ${map.dw}×${map.dh}px`);
console.log(`  plots: ${plotsShipped} provinces shipped to web/assets/plots/ (lazy per-plot terrain zoom)`);
console.log(`  terrain tiles: ${terrainTiles ? terrainTiles.src + ' (' + Object.keys(terrainTiles.cols).length + ' textures)' : 'skipped (no terrain-art.json / LFS textures)'}`);
for (const j of journeys) console.log(`  ${('→ ' + j.dest).padEnd(26)} ${j.provinceCount} prov · ${(j.days / 365.25).toFixed(1)}y · cargo ${j.cargoFinal}`);

// ---------------------------------------------------------------------------
// terrain baking
// ---------------------------------------------------------------------------
function bakeTerrain(provs) {
  const BMP = path.join(ROOT, 'data/anbennar/terrain.bmp');
  const W = 5632, H = 2048;                       // the EU4 province raster size
  // lon/lat -> source pixel (the inverse of ProvinceExporter's forward maps)
  const sx = lon => (lon + 180) / 360 * (W - 1);
  const sy = lat => { const r = lat * Math.PI / 180; return (1 - Math.log(Math.tan(r / 2 + Math.PI / 4)) / Math.PI) / 2 * H; };

  // crop to the displayed provinces + a margin, in source pixels
  let x0 = 1e9, x1 = -1e9, y0 = 1e9, y1 = -1e9;
  for (const p of provs) {
    const x = sx(p.lon), y = sy(p.lat);
    x0 = Math.min(x0, x); x1 = Math.max(x1, x); y0 = Math.min(y0, y); y1 = Math.max(y1, y);
  }
  const mx = (x1 - x0) * 0.06 + 40, my = (y1 - y0) * 0.08 + 40;
  x0 = Math.max(0, Math.floor(x0 - mx)); x1 = Math.min(W - 1, Math.ceil(x1 + mx));
  y0 = Math.max(0, Math.floor(y0 - my)); y1 = Math.min(H - 1, Math.ceil(y1 + my));
  const cropW = x1 - x0 + 1, cropH = y1 - y0 + 1;

  const buf = fs.readFileSync(BMP);
  const dataOff = buf.readUInt32LE(10);
  const idxAt = (x, y) => buf[dataOff + (H - 1 - y) * W + x];  // 8-bit, bottom-up, W is 4-aligned
  const TINT = terrainTint(terrainRealColors());

  // downsample by box-averaging the tinted colours (index averaging is meaningless);
  // the whole-world crop needs more pixels than the old caravan crop to stay legible
  const dw = Math.min(cropW, 2816);
  const scale = cropW / dw;
  const dh = Math.round(cropH / scale);
  const rgb = Buffer.alloc(dw * dh * 3);
  for (let j = 0; j < dh; j++) {
    const by0 = y0 + Math.floor(j * scale), by1 = Math.max(by0 + 1, y0 + Math.floor((j + 1) * scale));
    for (let i = 0; i < dw; i++) {
      const bx0 = x0 + Math.floor(i * scale), bx1 = Math.max(bx0 + 1, x0 + Math.floor((i + 1) * scale));
      let r = 0, g = 0, b = 0, n = 0;
      for (let yy = by0; yy < by1 && yy <= y1; yy++)
        for (let xx = bx0; xx < bx1 && xx <= x1; xx++) {
          const t = TINT[idxAt(xx, yy)]; r += t[0]; g += t[1]; b += t[2]; n++;
        }
      const o = (j * dw + i) * 3;
      rgb[o] = r / n | 0; rgb[o + 1] = g / n | 0; rgb[o + 2] = b / n | 0;
    }
  }

  // write the terrain crop as a real image asset (not inlined into the data)
  const png = encodePng(dw, dh, rgb);
  const assets = path.join(WEB, 'assets');
  fs.mkdirSync(assets, { recursive: true });
  const file = `terrain-${SEED}.png`;
  fs.writeFileSync(path.join(assets, file), png);

  return {
    src: `assets/${file}`, bytes: png.length,
    // the crop's extent in source-pixel space; the page re-derives sx/sy and
    // places dots at (sx-x0)/(x1-x0), (sy-y0)/(y1-y0) over this same image.
    x0, y0, x1, y1, W, H, dw, dh,
  };
}

// EU4 terrain.bmp palette index -> a dark, in-palette colour. The BMP's own
// palette is semantic (indices are terrain categories, not real RGB), so the
// classification mirrors MapTerrainCodec: water, flat land, hill, peak, snow,
// desert, marsh, jungle — each a muted tone that fits the dashboard's dark theme.
//
// When `real` (a Map of TERRAIN_* -> real Civ4 texture colour, from terrain-art.json
// + the .dds textures) is present, the land categories take the real terrain's HUE
// but keep the hand-tuned tint's LUMINANCE — so the map stays exactly as dark, now
// coloured by real Civ4 art rather than hand-picked values (docs §10). Absent (LFS
// art not pulled), it falls back to the hand-tuned tints unchanged.
function terrainTint(real) {
  const SEA = [18, 31, 51], SHALLOW = [27, 45, 68];
  const LAND = [42, 52, 68], GRASS = [41, 55, 60], PLAIN = [52, 58, 62];
  const DESERT = [67, 61, 50], SCRUB = [58, 58, 50], MARSH = [37, 53, 57];
  const HILL = [52, 63, 84], PEAK = [88, 96, 114], SNOW = [140, 150, 172], JUNGLE = [37, 60, 53];
  const t = new Array(256).fill(LAND);
  const set = (c, ...ix) => ix.forEach(i => t[i] = c);
  set(SEA, 15, 17);                       // ocean / inland_ocean
  set(SHALLOW, 35);                       // coastline
  set(GRASS, 0, 5, 10, 11, 12, 14, 255);  // grasslands / farmlands / forest / woods
  set(HILL, 1, 8, 23, 24);                // hills / highlands / dry_highlands
  set(PLAIN, 4, 20);                      // plains / savannah
  set(DESERT, 3, 7, 19);                  // desert / desert_low / coastal_desert
  set([64, 60, 74], 2);                   // desert_mountain (peak-ish, warm)
  set(PEAK, 6);                           // mountain
  set(SNOW, 16);                          // permanent snow
  set(MARSH, 9, 13);                      // marsh / shadow_swamp
  set(SCRUB, 22);                         // drylands
  set(JUNGLE, 254);                       // jungle

  // recolour the land categories from real Civ4 terrain art (hue only; theme kept)
  if (real) {
    const use = (terrain, ...ix) => {
      const c = real.get(terrain);
      if (c) ix.forEach(i => { t[i] = hueAtLuminance(t[i], c); });
    };
    use('TERRAIN_GRASSLAND', 0, 5, 10, 11, 12, 14, 255);
    use('TERRAIN_PLAINS', 4, 20);
    use('TERRAIN_DESERT', 3, 7, 19);
    use('TERRAIN_SCRUB', 22);
    use('TERRAIN_MARSH', 9, 13);
    use('TERRAIN_LUSH', 254);             // jungle
    use('TERRAIN_PERMAFROST', 16);        // permanent snow
    // the default land fill takes the grassland hue too
    const gl = real.get('TERRAIN_GRASSLAND');
    if (gl) for (let i = 0; i < 256; i++) if (t[i] === LAND) t[i] = hueAtLuminance(LAND, gl);
  }
  return t;
}

// rec.601 luminance of an [r,g,b] (function decl: hoisted, so the top-level
// bakeTerrain() call can reach it — see the crc32 note below)
function luma(c) { return 0.299 * c[0] + 0.587 * c[1] + 0.114 * c[2]; }

// `real`'s hue rescaled to `base`'s luminance — authentic colour, theme brightness
function hueAtLuminance(base, real) {
  const s = luma(base) / Math.max(1, luma(real));
  return [Math.min(255, real[0] * s) | 0, Math.min(255, real[1] * s) | 0, Math.min(255, real[2] * s) | 0];
}

// Real per-terrain colours from terrain-art.json + the Civ4 .dds textures (offline
// LFS source). Each terrain's colour is its base blend texture modulated by its
// detail texture (base*detail/255 — the Civ4 layering, which recovers the hue the
// near-neutral blend textures carry only via their detail). Returns a Map keyed by
// TERRAIN_*, or null if the manifest or textures are unavailable (LFS not pulled),
// so the bake degrades to the hand-tuned tints without failing.
function terrainRealColors() {
  const manifest = path.join(ROOT, 'src/main/resources/map/terrain-art.json');
  if (!fs.existsSync(manifest)) { console.log('  terrain-art: manifest absent — using hand-tuned tints'); return null; }
  let arr;
  try { arr = JSON.parse(fs.readFileSync(manifest, 'utf8')); } catch { return null; }
  const map = new Map();
  for (const e of arr) {
    const base = avgDds(e.path), detail = avgDds(e.detail);
    if (!base) continue;
    const c = detail ? [0, 1, 2].map(k => Math.min(255, base[k] * detail[k] / 255) | 0) : base;
    map.set(e.terrain, c);
  }
  if (!map.size) { console.log('  terrain-art: no textures decoded (LFS not pulled?) — using hand-tuned tints'); return null; }
  console.log(`  terrain-art: recoloured ${map.size} land terrains from real Civ4 textures`);
  return map;
}

// average RGB of a Civ4 .dds texture resolved under UnpackedArt/art (case-insensitive);
// null if the file or its format can't be read (caller falls back)
function avgDds(artPath) {
  const file = resolveArt(artPath);
  if (!file) return null;
  let img;
  try { img = decodeDds(fs.readFileSync(file)); } catch { return null; }
  let r = 0, g = 0, b = 0; const n = img.width * img.height;
  for (let i = 0; i < n; i++) { r += img.rgba[i * 4]; g += img.rgba[i * 4 + 1]; b += img.rgba[i * 4 + 2]; }
  return [r / n | 0, g / n | 0, b / n | 0];
}

// resolve an "Art/Terrain/.../X.dds" path to a file under UnpackedArt/art,
// case-insensitively (the XML paths and on-disk names differ in case); null if absent
function resolveArt(artPath) {
  if (!artPath) return null;
  const rel = artPath.replace(/^Art\//i, '').split('/');
  let dir = path.join(ROOT, 'UnpackedArt', 'art');
  for (const seg of rel) {
    let ents;
    try { ents = fs.readdirSync(dir); } catch { return null; }
    const hit = ents.find(e => e.toLowerCase() === seg.toLowerCase());
    if (!hit) return null;
    dir = path.join(dir, hit);
  }
  return dir;
}

// ---------------------------------------------------------------------------
// per-plot terrain zoom layer
// ---------------------------------------------------------------------------

// the terrain display colours the plot layer tints with — the same real Civ4
// blend×detail averages the background bake uses (terrainRealColors), as hex. The
// fallback (those averages, measured once) keeps the plot layer colourful even when
// the LFS textures aren't pulled, so terrain-art.json + textures are optional here.
// (The table is inside the function so this hoisted call at module load doesn't hit
// a const in its temporal dead zone.)
// TERRAIN_* -> Civ4 LayerOrder from terrain-art.json: higher layers paint over lower,
// so the plot renderer feathers a higher-layer terrain over its lower neighbours at
// shared edges (docs §6.1). Empty if the manifest is absent (renderer keeps hard edges).
function terrainLayerOrders() {
  const mp = path.join(ROOT, 'src/main/resources/map/terrain-art.json');
  if (!fs.existsSync(mp)) return {};
  try {
    const a = JSON.parse(fs.readFileSync(mp, 'utf8'));
    const o = {};
    for (const e of a) o[e.terrain] = e.layerOrder;
    return o;
  } catch { return {}; }
}

function terrainDisplayColors(real) {
  const fallback = {
    TERRAIN_GRASSLAND: [81, 91, 33], TERRAIN_LUSH: [37, 74, 11], TERRAIN_PLAINS: [103, 88, 45],
    TERRAIN_SCRUB: [100, 91, 62], TERRAIN_MARSH: [65, 72, 36], TERRAIN_MUDDY: [90, 79, 51],
    TERRAIN_ROCKY: [68, 64, 62], TERRAIN_BADLAND: [89, 75, 55], TERRAIN_JAGGED: [110, 106, 100],
    TERRAIN_BARREN: [56, 48, 37], TERRAIN_DESERT: [126, 83, 40], TERRAIN_DUNES: [161, 119, 66],
    TERRAIN_SALT_FLATS: [129, 127, 123], TERRAIN_TAIGA: [101, 99, 49], TERRAIN_TUNDRA: [116, 102, 88],
    TERRAIN_PERMAFROST: [122, 132, 138],
  };
  const hex = c => '#' + [0, 1, 2].map(k => Math.max(0, Math.min(255, c[k] | 0)).toString(16).padStart(2, '0')).join('');
  // the plot zoom is a detail dive, so lift the blend×detail averages into a vibrant,
  // map-like range rather than the dark-theme tint the background bake uses
  const LIFT = 2.35;
  const lift = c => [c[0] * LIFT, c[1] * LIFT, c[2] * LIFT];
  const out = {};
  for (const k in fallback) out[k] = hex(fallback[k]);        // colourful default (already lifted)
  if (real) for (const [k, v] of real) out[k] = hex(lift(v)); // real textures override
  return out;
}

// Slice B — bake a real ground-texture atlas: for each curated terrain, take its Civ4
// DETAIL texture (a large seamless tiling ground texture, unlike the blend maps which
// are semi tile-sheets), downsample to a 48×48 tile and recolour it so its mean equals
// the terrain's display colour — real texture in the right hue, cohesive with the flat
// colours. Packed as one horizontal strip PNG the page draws per plot at deep zoom.
// Returns {src, tile, cols:{TERRAIN_*: column}}, or null if the manifest/textures are
// absent (the page then keeps the flat-colour plot tiles).
function bakeTerrainTiles(colorsHex) {
  const manifestPath = path.join(ROOT, 'src/main/resources/map/terrain-art.json');
  if (!fs.existsSync(manifestPath)) return null;
  let manifest;
  try { manifest = JSON.parse(fs.readFileSync(manifestPath, 'utf8')); } catch { return null; }
  const T = 256, W = manifest.length * T, H = T;   // high-res tiles from the 1024px detail source
  const rgb = Buffer.alloc(W * H * 3);
  const cols = {};
  let idx = 0, decoded = 0;
  const hexRgb = h => [parseInt(h.slice(1, 3), 16), parseInt(h.slice(3, 5), 16), parseInt(h.slice(5, 7), 16)];
  for (const e of manifest) {
    const target = hexRgb(colorsHex[e.terrain] || '#465046');
    const tile = detailTile(e.detail, target, T);
    if (tile) decoded++;
    const t = tile || solidTile(target, T);
    for (let y = 0; y < T; y++)
      for (let x = 0; x < T; x++) {
        const s = (y * T + x) * 3, d = (y * W + idx * T + x) * 3;
        rgb[d] = t[s]; rgb[d + 1] = t[s + 1]; rgb[d + 2] = t[s + 2];
      }
    cols[e.terrain] = idx++;
  }
  if (!decoded) return null;   // no textures decoded (LFS not pulled) → keep flat colours
  const png = encodePng(W, H, rgb);
  const assets = path.join(WEB, 'assets');
  fs.mkdirSync(assets, { recursive: true });
  const file = `terrain-tiles-${SEED}.png`;
  fs.writeFileSync(path.join(assets, file), png);
  return { src: `assets/${file}`, tile: T, cols };
}
// downsample a detail .dds to a T×T RGB tile, then recolour so its mean = target
function detailTile(artPath, target, T) {
  const file = resolveArt(artPath);
  if (!file) return null;
  let img;
  try { img = decodeDds(fs.readFileSync(file)); } catch { return null; }
  const bx = img.width / T, by = img.height / T;
  const tmp = new Float64Array(T * T * 3);
  let mr = 0, mg = 0, mb = 0;
  for (let j = 0; j < T; j++)
    for (let i = 0; i < T; i++) {
      let r = 0, g = 0, b = 0, n = 0;
      for (let y = Math.floor(j * by); y < Math.floor((j + 1) * by); y++)
        for (let x = Math.floor(i * bx); x < Math.floor((i + 1) * bx); x++) {
          const o = (y * img.width + x) * 4; r += img.rgba[o]; g += img.rgba[o + 1]; b += img.rgba[o + 2]; n++;
        }
      const o = (j * T + i) * 3; tmp[o] = r / n; tmp[o + 1] = g / n; tmp[o + 2] = b / n;
      mr += tmp[o]; mg += tmp[o + 1]; mb += tmp[o + 2];
    }
  const N = T * T;
  const sr = target[0] / Math.max(1, mr / N), sg = target[1] / Math.max(1, mg / N), sb = target[2] / Math.max(1, mb / N);
  const out = Buffer.alloc(N * 3);
  for (let k = 0; k < N; k++) {
    out[k * 3] = Math.min(255, tmp[k * 3] * sr) | 0;
    out[k * 3 + 1] = Math.min(255, tmp[k * 3 + 1] * sg) | 0;
    out[k * 3 + 2] = Math.min(255, tmp[k * 3 + 2] * sb) | 0;
  }
  return out;
}
// a flat T×T RGB tile of one colour (fallback when a detail texture is unavailable)
function solidTile(rgbArr, T) {
  const out = Buffer.alloc(T * T * 3);
  for (let k = 0; k < T * T; k++) { out[k * 3] = rgbArr[0]; out[k * 3 + 1] = rgbArr[1]; out[k * 3 + 2] = rgbArr[2]; }
  return out;
}

// copy each displayed province's canonical plot grid (map/provinces/<id>.json.gz,
// gzipped JSON) into web/assets/plots/<id>.js as a lazy-loadable assignment. Emitted
// as a JS file (not the raw .gz) so the page can load it via a <script> tag — which
// works off file:// where fetch() of a local resource is blocked (an HTTP deploy still
// gzips the .js on the wire). Sets p.hasPlots; the dir is gitignored (regenerable).
function shipPlots(provs) {
  const srcDir = path.join(ROOT, 'src/main/resources/map/provinces');
  const outDir = path.join(WEB, 'assets', 'plots');
  fs.rmSync(outDir, { recursive: true, force: true });
  fs.mkdirSync(outDir, { recursive: true });
  let n = 0;
  for (const p of provs) {
    const gz = path.join(srcDir, `${p.id}.json.gz`);
    if (!fs.existsSync(gz)) { p.hasPlots = false; continue; }
    const json = zlib.gunzipSync(fs.readFileSync(gz)).toString('utf8');
    fs.writeFileSync(path.join(outDir, `${p.id}.js`),
      `window.__plots=window.__plots||{};window.__plots[${p.id}]=${json};\n`);
    p.hasPlots = true; n++;
  }
  return n;
}

// Minimal truecolour PNG encoder (Node has zlib but no image codec).
function encodePng(w, h, rgb) {
  const stride = w * 3;
  const raw = Buffer.alloc((stride + 1) * h);
  for (let y = 0; y < h; y++) { raw[y * (stride + 1)] = 0; rgb.copy(raw, y * (stride + 1) + 1, y * stride, y * stride + stride); }
  const idat = zlib.deflateSync(raw, { level: 9 });
  const sig = Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]);
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(w, 0); ihdr.writeUInt32BE(h, 4); ihdr[8] = 8; ihdr[9] = 2; // 8-bit, truecolour RGB
  return Buffer.concat([sig, chunk('IHDR', ihdr), chunk('IDAT', idat), chunk('IEND', Buffer.alloc(0))]);
}
function chunk(type, data) {
  const len = Buffer.alloc(4); len.writeUInt32BE(data.length, 0);
  const body = Buffer.concat([Buffer.from(type, 'ascii'), data]);
  const crc = Buffer.alloc(4); crc.writeUInt32BE(crc32(body) >>> 0, 0);
  return Buffer.concat([len, body, crc]);
}
var CRC_TABLE;   // var: hoisted so top-level bakeTerrain() can call crc32 before this line
function crc32(buf) {
  if (!CRC_TABLE) {
    CRC_TABLE = new Uint32Array(256);
    for (let n = 0; n < 256; n++) { let c = n; for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1; CRC_TABLE[n] = c >>> 0; }
  }
  let c = 0xffffffff;
  for (let i = 0; i < buf.length; i++) c = CRC_TABLE[(c ^ buf[i]) & 0xff] ^ (c >>> 8);
  return (c ^ 0xffffffff) >>> 0;
}
