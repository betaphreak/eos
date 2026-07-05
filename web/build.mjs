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

// province subset: everything visited + one neighbour ring, for map context
const sub = new Set(visited);
for (const pid of visited) { const p = byId.get(pid); if (p) p.neighbors.forEach(n => sub.add(n)); }

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

const bundle = {
  meta: {
    seed: +SEED, scenario,
    origin: { id: originId, name: origin.name, lat: +origin.lat.toFixed(3), lon: +origin.lon.toFixed(3), region: origin.region },
    dateStart: allDates[0], dateEnd: allDates[allDates.length - 1], maxDays,
  },
  provinces, journeys, map,
};

// the run's data as a plain script the page (index.html) loads alongside the
// terrain image asset — the image stays a binary file, never inlined into the data.
const terrainBytes = map.bytes; delete map.bytes;
const dataJs = `window.BUNDLE = ${JSON.stringify(bundle)};\n`;
fs.writeFileSync(path.join(WEB, 'data.js'), dataJs);

console.log(`Built web/data.js (${(dataJs.length / 1024).toFixed(0)} KB) + web/${map.src} (${(terrainBytes / 1024).toFixed(0)} KB) from seed ${SEED}`);
console.log(`  ${journeys.length} journeys · ${provinces.length} provinces · ${bundle.meta.dateStart} → ${bundle.meta.dateEnd}`);
console.log(`  terrain crop ${map.dw}×${map.dh}px`);
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
  const TINT = terrainTint();

  // downsample by box-averaging the tinted colours (index averaging is meaningless)
  const dw = Math.min(cropW, 1180);
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
function terrainTint() {
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
  return t;
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
