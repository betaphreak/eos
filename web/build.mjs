// Build the WorldMap's data from the committed map resources — run-independent.
//
//   node web/build.mjs [seed]        (seed only names the baked terrain asset; default 24601)
//
// Reads the committed province map (civstudio-engine/src/main/resources/map/provinces.json) + outlines
// (borders.json) + geographic hierarchy + tech tree, distils them into one
// JSON bundle written to web/data.js (which index.html loads), and bakes a dark-tinted crop of
// the real EU4 terrain raster (data/anbennar/terrain.bmp) into a real image asset at
// web/assets/terrain.png that the page references — the image is never inlined into the data.
// The live caravans come from the spectator server (the Caravans view), so NO output/<seed>
// run is needed.
//
// The baked art assets (terrain.png crop, terrain-tiles/river/sea/shore/foam/ice/
// trees/bonus-icons) are seed-independent — their content comes from the Civ4 art and
// the whole-world raster, not the run — so they carry stable names (no seed suffix);
// only data.js is per-seed. The page loads each by the exact filename the bundle
// records (BUNDLE.<asset>.src), so stable names need no page change.
import fs from 'node:fs';
import path from 'node:path';
import zlib from 'node:zlib';
import { fileURLToPath } from 'node:url';
import { decodeDds } from './dds.mjs';
import { loadGameFont, resourceCellRGBA, CELL as GF_CELL } from './gamefont.mjs';
import { bakeNifGroup } from '../tools/nifbake/render.mjs';
import sharp from 'sharp';

const WEB = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(WEB, '..');

// Baked art assets ship as WebP (see docs) rather than PNG: the ground-texture atlas alone drops
// ~2.7 MB → ~0.37 MB, and the whole eager image payload roughly quarters, with no visible loss.
// sharp's encoder is async, so bakes stay synchronous and just QUEUE their raw pixels here (the
// same contiguous (rgb, alpha) buffers the bakes build); flushImages() encodes the queue to WebP in one async
// pass before the bundle is written. Photographic layers (terrain raster, tile atlas, water tiles)
// use lossy quality; hard-edged sprites/icons use a high quality with full-quality alpha so their
// cut-out edges stay crisp. Browser support for WebP is universal, so no fallback is shipped.
const IMAGE_QUEUE = [];
// Interleave a contiguous rgb (w·h·3) + optional alpha (w·h) — the layout the bakes produce — into
// the RGB/RGBA buffer sharp's raw input wants.
function toRaw(w, h, rgb, alpha) {
  if (!alpha) return { raw: rgb, channels: 3 };
  const out = Buffer.allocUnsafe(w * h * 4);
  for (let i = 0; i < w * h; i++) {
    out[i * 4] = rgb[i * 3]; out[i * 4 + 1] = rgb[i * 3 + 1];
    out[i * 4 + 2] = rgb[i * 3 + 2]; out[i * 4 + 3] = alpha[i];
  }
  return { raw: out, channels: 4 };
}
// Queue an image for WebP encoding and return its `assets/<name>.webp` src.
function queueWebp(name, w, h, rgb, alpha, opts = {}) {
  const { raw, channels } = toRaw(w, h, rgb, alpha);
  IMAGE_QUEUE.push({ name, w, h, raw, channels, quality: opts.quality ?? 82 });
  return `assets/${name}.webp`;
}
// Queue a pre-interleaved RGBA buffer (w·h·4) — used by the nif sprite baker, which builds RGBA.
function queueWebpRGBA(name, w, h, rgba, opts = {}) {
  IMAGE_QUEUE.push({ name, w, h, raw: rgba, channels: 4, quality: opts.quality ?? 82 });
  return `assets/${name}.webp`;
}
// Encode every queued image to assets/<name>.webp; returns {name.webp: byteLength} for the size logs.
async function flushImages(assets) {
  fs.mkdirSync(assets, { recursive: true });
  const sizes = {};
  for (const im of IMAGE_QUEUE) {
    const buf = await sharp(im.raw, { raw: { width: im.w, height: im.h, channels: im.channels } })
      .webp({ quality: im.quality, alphaQuality: 100, effort: 5 })
      .toBuffer();
    const file = `${im.name}.webp`;
    fs.writeFileSync(path.join(assets, file), buf);
    sizes[file] = buf.length;
  }
  return sizes;
}
// The map is run-independent: the site's live caravans come from the server (see Caravans
// view / docs/client-server.md), so build.mjs no longer reads a recorded run — only the
// committed map/geo/terrain/tech resources. SEED still names the baked terrain assets.
const SEED = process.argv[2] || '24601';

const allProv = JSON.parse(fs.readFileSync(path.join(ROOT, 'civstudio-engine/src/main/resources/map/provinces.json'), 'utf8'));
const byId = new Map(allProv.map(p => [p.id, p]));

// land-like province types: dry surface LAND, the four underground Dwarovar types, and the
// seven special Anbennar surface terrains — all settleable, all shipped and rendered. See
// docs/underworld.md.
const LANDLIKE = new Set(["LAND",
  "CAVERN", "DWARVEN_HOLD", "DWARVEN_HOLD_SURFACE", "DWARVEN_ROAD",
  "ANCIENT_FOREST", "GLADEWAY", "FEY_GLADEWAY", "BLOODGROVES", "MUSHROOM_FOREST",
  "SHADOW_SWAMP", "GLACIER"]);

// WorldMap: ship every land-like province (the whole world, surface + underground), not
// just the caravan crop — the caravan run only supplies the optional Caravan-mode overlay.
const sub = new Set(allProv.filter(p => LANDLIKE.has(p.type)).map(p => p.id));

// coastal water provinces (SEA/LAKE) that generated a shelf field also ship, so their near-shore
// resource plots render (docs/coastlines.md Phase F). They carry NO ocean polygon — the border
// exporter skips oceans — so a plot-extent bbox (computed in packPlots) drives their culling
// instead. Deep-ocean provinces with no shelf have no grid and are left out.
const provinceDir = path.join(ROOT, 'civstudio-engine/src/main/resources/map/provinces');
const water = new Set(allProv
  .filter(p => (p.type === "SEA" || p.type === "LAKE") && fs.existsSync(path.join(provinceDir, `${p.id}.json.gz`)))
  .map(p => p.id));
const shipped = new Set([...sub, ...water]);   // every province the page ships (land + coastal water)

// canonical province outlines (source-pixel rings), attached to the displayed subset
const borders = JSON.parse(fs.readFileSync(path.join(ROOT, 'civstudio-engine/src/main/resources/map/borders.json'), 'utf8'));
const ringsById = new Map(borders.map(b => [b.id, b.rings]));

// The geographic-tier boundary polygons (continent / super-region / region) are no longer baked
// here: the server serves them straight from the engine jar's map/tierborders.json at GET
// /api/tiers (web/js/overlays/tiers.mjs), so there is no committed assets/tiers.json to copy.

// geographic hierarchy display names, keyed for per-province lookup and the label rollup.
// Continent names mirror Continent.java displayName() (the Anbennar landmass per EU4 raw key).
const CONTINENT_NAME = {
  europe: 'Cannor', asia: 'Haless', africa: 'Sarhal', north_america: 'Aelantir',
  south_america: 'Aelantir', serpentspine: 'Serpentspine', oceania: 'Hinuilands',
};
const superRegions = JSON.parse(fs.readFileSync(path.join(ROOT, 'civstudio-engine/src/main/resources/map/superregions.json'), 'utf8'));
const regionsMeta = JSON.parse(fs.readFileSync(path.join(ROOT, 'civstudio-engine/src/main/resources/map/regions.json'), 'utf8'));
const areasMeta = JSON.parse(fs.readFileSync(path.join(ROOT, 'civstudio-engine/src/main/resources/map/areas.json'), 'utf8'));
const srNameByRegion = {};   // region key -> super-region display name
const srKeyByRegion = {};    // region key -> super-region raw (Clausewitz) key
for (const s of superRegions) for (const rk of s.regions) { srNameByRegion[rk] = s.name; srKeyByRegion[rk] = s.key; }
const regionDisplayName = {};   // region key -> display name
for (const r of regionsMeta) regionDisplayName[r.key] = r.name;
const areaDisplayName = {};   // area key -> display name
for (const a of areasMeta) areaDisplayName[a.key] = a.name;

// political reference tables (optional resources; the political map mode colours
// province polygons by their owner tag, and joins culture/religion for the sidebar)
const readJsonOpt = f => { try { return JSON.parse(fs.readFileSync(path.join(ROOT, f), 'utf8')); } catch { return []; } };
const countryByTag = Object.fromEntries(readJsonOpt('civstudio-engine/src/main/resources/map/countries.json').map(c => [c.tag, { name: c.name, color: c.color }]));
const cultureByKey = Object.fromEntries(readJsonOpt('civstudio-engine/src/main/resources/map/cultures.json').map(c => [c.key, { name: c.name, group: c.group, color: c.color }]));
const religionByKey = Object.fromEntries(readJsonOpt('civstudio-engine/src/main/resources/map/religions.json').map(r => [r.key, { name: r.name, group: r.group, color: r.color }]));

// ---- EU4-style label baseline (phase b): the curved spine a province name is laid along ----
// Approximates the polygon's medial axis: scanline-rasterise the interior, take the shape's
// principal axis (PCA), then slice the interior perpendicular to that axis and take each slice's
// mid-line — the sequence of slice midpoints is a curve that bends with the shape. Returns
// { t: <thickness px>, p: [[x,y],…] } in SOURCE pixels (a few smoothed control points), or null
// for a ring-less / too-thin province (the client then falls back to the straight principal axis).
function labelBaseline(rings) {
  if (!rings || !rings.length) return null;
  let x0 = 1e9, y0 = 1e9, x1 = -1e9, y1 = -1e9;
  const edges = [];
  for (const ring of rings) for (let i = 0; i < ring.length; i++) {
    const a = ring[i], b = ring[(i + 1) % ring.length];
    edges.push([a[0], a[1], b[0], b[1]]);
    if (a[0] < x0) x0 = a[0]; if (a[0] > x1) x1 = a[0]; if (a[1] < y0) y0 = a[1]; if (a[1] > y1) y1 = a[1];
  }
  const W = x1 - x0, H = y1 - y0;
  if (W < 3 || H < 3) return null;
  const step = Math.max(1, Math.round(Math.max(W, H) / 110));   // ~110 samples across the long side
  // scanline fill → interior sample points (even-odd rule)
  const pts = [];
  for (let y = y0 + step / 2; y < y1; y += step) {
    const xs = [];
    for (const [ax, ay, bx, by] of edges)
      if ((ay <= y) !== (by <= y)) xs.push(ax + (y - ay) / (by - ay) * (bx - ax));
    xs.sort((a, b) => a - b);
    for (let i = 0; i + 1 < xs.length; i += 2)
      for (let x = xs[i] + step / 2; x < xs[i + 1]; x += step) pts.push([x, y]);
  }
  if (pts.length < 8) return null;
  // PCA over the interior samples → principal (long) direction u, perpendicular v
  let n = pts.length, cx = 0, cy = 0;
  for (const [x, y] of pts) { cx += x; cy += y; }
  cx /= n; cy /= n;
  let sxx = 0, syy = 0, sxy = 0;
  for (const [x, y] of pts) { const dx = x - cx, dy = y - cy; sxx += dx * dx; syy += dy * dy; sxy += dx * dy; }
  const ang = 0.5 * Math.atan2(2 * sxy, sxx - syy);
  const ux = Math.cos(ang), uy = Math.sin(ang), vx = -uy, vy = ux;
  // bin the interior by axis coordinate t; each bin's mean perpendicular s is a spine point, its
  // s-range is the local width (→ thickness)
  let tmin = 1e9, tmax = -1e9;
  const T = pts.map(([x, y]) => { const t = (x - cx) * ux + (y - cy) * uy; if (t < tmin) tmin = t; if (t > tmax) tmax = t; return t; });
  const K = 8, span = (tmax - tmin) || 1;
  const sum = new Array(K).fill(0), cnt = new Array(K).fill(0), smin = new Array(K).fill(1e9), smax = new Array(K).fill(-1e9);
  for (let i = 0; i < n; i++) {
    const k = Math.max(0, Math.min(K - 1, Math.floor((T[i] - tmin) / span * K)));
    const s = (pts[i][0] - cx) * vx + (pts[i][1] - cy) * vy;
    sum[k] += s; cnt[k]++; if (s < smin[k]) smin[k] = s; if (s > smax[k]) smax[k] = s;
  }
  const mean = [], widths = [];
  for (let k = 0; k < K; k++) if (cnt[k]) { mean[k] = sum[k] / cnt[k]; widths.push(smax[k] - smin[k]); } else mean[k] = null;
  if (widths.length < 2) return null;
  // smooth the spine (moving average over the filled bins), then emit a control point per filled bin
  const out = [];
  for (let k = 0; k < K; k++) {
    if (mean[k] == null) continue;
    let acc = 0, m = 0;
    for (let d = -1; d <= 1; d++) if (mean[k + d] != null) { acc += mean[k + d]; m++; }
    const s = acc / m, t = tmin + (k + 0.5) / K * span;
    out.push([Math.round(cx + t * ux + s * vx), Math.round(cy + t * uy + s * vy)]);
  }
  if (out.length < 2) return null;
  widths.sort((a, b) => a - b);
  const thick = widths[widths.length >> 1];   // thickness = median slice width
  // only ship a curved baseline when the spine actually bends: a straight/convex province is served
  // identically by the client's own principal-axis fallback, so shipping it would just bloat data.js.
  // Keep it when the max deviation of the spine from its end-to-end chord is ≥ ~a fifth of the width.
  const a = out[0], b = out[out.length - 1], cl = Math.hypot(b[0] - a[0], b[1] - a[1]) || 1;
  let maxDev = 0;
  for (let i = 1; i < out.length - 1; i++) {
    const d = Math.abs((b[0] - a[0]) * (a[1] - out[i][1]) - (a[0] - out[i][0]) * (b[1] - a[1])) / cl;
    if (d > maxDev) maxDev = d;
  }
  if (maxDev < thick * 0.22) return null;
  return { t: Math.round(thick), p: out };
}

const provinces = [...shipped].map(id => byId.get(id)).filter(Boolean).map(p => ({
  id: p.id, name: p.name, lat: +p.lat.toFixed(3), lon: +p.lon.toFixed(3),
  plots: p.plots, waterPlots: p.waterPlots || 0, type: p.type,
  // geography as raw Clausewitz keys only; display names are resolved client-side from the shipped
  // `geoNames` dictionaries (interning them here duplicated ~850 KB of names across all provinces)
  region: p.region || null, area: p.area || null, continent: p.continent || null,
  winter: p.winter || null,
  nb: p.neighbors.filter(n => shipped.has(n)),
  rings: ringsById.get(p.id) || null,    // outline in source pixels (null for sea/lake → bbox culls, packPlots)
  lab: labelBaseline(ringsById.get(p.id)),   // curved label baseline (medial spine); null → client uses the straight axis
}));

// ---- bake the dark terrain background from the real EU4 raster ----
// provinces.json derived each province's coordinates from terrain.bmp pixels:
//   lon = (cx - xmin) / (xmax - xmin) * 360 - 180   (linear in pixel x; xmin≈0, xmax≈W-1)
//   lat = mercatorLatitude(pixel y)                 (web-mercator in pixel y)
// so lon/lat invert back to the exact source pixel, and a crop aligns 1:1 with
// the province dots as long as the page projects with the same two formulas.
const map = bakeTerrain(provinces);

// per-plot terrain zoom layer (a base WorldMap layer the Caravan View draws over):
// pack every displayed province's canonical plot grid into one range-fetched
// plots.pack (index inlined below), and expose the terrain display colours the
// page tints plots with (docs §10). Slice B also bakes a real ground-texture
// atlas the page draws per plot at deep zoom.
const terrainColors = terrainDisplayColors(terrainRealColors());
const terrainLayer = terrainLayerOrders();   // TERRAIN_* -> Civ4 LayerOrder (drives edge blending)
const terrainTiles = bakeTerrainTiles(terrainColors);
const river = bakeRiverTile();               // {src, tile} water tile, or null (flat-fill fallback)
const sea = bakeSeaTile();                   // {src, tile} greyscale ripple tile, or null (gradient-only fallback)
const shore = bakeShoreTile();               // {src, tile} greyscale shore-wave tile for the shallows, or null
const foam = bakeFoamTile();                 // {src, w, h} real Civ4 wave-crest foam strip, or null (procedural foam line)
const ice = bakeIceTile();                   // {src, tile} real Civ4 pack-ice tile, or null (procedural pale floes)
const bonusIcons = bakeBonusIcons();         // {src, cell, cols, index:{type:i}} real Civ4 resource icons, or null
const trees = bakeFeatureSprites();          // {leafy,palm,swamp:{src,w,h,sprites}} real foliage cutouts, or null
const seaBands = bakeSeaBands();             // {trop, temp, polar, shore} climate sea + shore colours
const plotPack = packPlots(provinces);

// encode every queued art asset to WebP (one async pass now the bakes have run); imgSizes feeds the
// size logs below. The bundle records each asset's .webp src, so the page loads them unchanged.
const imgSizes = await flushImages(path.join(WEB, 'assets'));

// ---- geographic label tiers (continent -> super-region -> region) ----------
// Roll the committed hierarchy up into per-tier label records {name, lat, lon, w}, where
// (lat, lon) is the plot-weighted centroid of the tier's land provinces and w its total
// plots (label priority). The page reveals a coarser/finer tier per zoom band. The name
// maps (CONTINENT_NAME / srNameByRegion / regionDisplayName) are defined above, next to the
// per-province enrichment that shares them; both Americas map to Aelantir and merge by name.

// plot-weighted centroid of the land provinces a nameFn buckets together
function rollupTier(nameFn) {
  const acc = new Map();
  for (const id of sub) {
    const p = byId.get(id);
    if (!p || !LANDLIKE.has(p.type)) continue;
    const name = nameFn(p);
    if (!name) continue;
    const w = p.plots || 1;
    const a = acc.get(name) || (acc.set(name, { name, sx: 0, sy: 0, w: 0 }).get(name));
    a.sx += p.lon * w; a.sy += p.lat * w; a.w += w;
  }
  return [...acc.values()]
    .map(a => ({ name: a.name, lon: +(a.sx / a.w).toFixed(3), lat: +(a.sy / a.w).toFixed(3), w: a.w }))
    .sort((x, y) => y.w - x.w);   // largest first = label priority
}
const geo = {
  continents: rollupTier(p => CONTINENT_NAME[p.continent]),
  superRegions: rollupTier(p => srNameByRegion[p.region]),
  regions: rollupTier(p => regionDisplayName[p.region] || null),
};

// geography display-name dictionaries, trimmed to the tiers the shipped provinces reference.
// Provinces carry only raw keys; the client resolves crumb names through these (see core.provGeo).
const usedRegions = new Set(provinces.map(p => p.region).filter(Boolean));
const usedAreas = new Set(provinces.map(p => p.area).filter(Boolean));
const usedContinents = new Set(provinces.map(p => p.continent).filter(Boolean));
const pickKeys = (src, keys) => Object.fromEntries([...keys].filter(k => src[k] != null).map(k => [k, src[k]]));
const geoNames = {
  continent: pickKeys(CONTINENT_NAME, usedContinents),
  region: pickKeys(regionDisplayName, usedRegions),
  area: pickKeys(areaDisplayName, usedAreas),
  superByRegion: pickKeys(srNameByRegion, usedRegions),      // region key -> super-region display name
  superKeyByRegion: pickKeys(srKeyByRegion, usedRegions),    // region key -> super-region raw key
};

// the political layer is split into web/political.js, fetched lazily on first switch to Political
// mode — World/Caravan never pay for it. Tables trimmed to the tags/keys owned provinces reference;
// controller shipped only when it differs from owner (occupation), else the client defaults it.
const shippedRaw = [...shipped].map(id => byId.get(id)).filter(Boolean);
const pickBy = (src, keys) => { const o = {}; for (const k of keys) if (k && src[k] && !o[k]) o[k] = src[k]; return o; };
const political = {
  countries: pickBy(countryByTag, shippedRaw.map(p => p.owner)),
  cultures: pickBy(cultureByKey, shippedRaw.map(p => p.culture)),
  religions: pickBy(religionByKey, shippedRaw.map(p => p.religion)),
  provinces: shippedRaw.filter(p => p.owner || p.culture || p.religion).map(p => ({
    id: p.id, o: p.owner || null,
    ct: (p.controller && p.controller !== p.owner) ? p.controller : null,
    c: p.culture || null, r: p.religion || null,
  })),
};
fs.writeFileSync(path.join(WEB, 'political.js'), `window.POLITICAL = ${JSON.stringify(political)};\n`);

// committed Anbennar loading-screen art (baked locally by web/bake-loading.mjs — System.Drawing JPEG);
// the page shows one at random 1:1 (viewport crops) while the map loads. Absent → the page skips it.
const loadingDir = path.join(WEB, 'assets');
const loading = fs.existsSync(loadingDir)
  ? fs.readdirSync(loadingDir).filter(f => /^loading-\d+\.jpg$/.test(f))
      .sort((a, b) => parseInt(a.match(/\d+/)) - parseInt(b.match(/\d+/))).map(f => `assets/${f}`)
  : [];

// EU4 special adjacencies (straits/canals/lake crossings/Dwarovar tunnels) between provinces that
// are not visually adjacent. Short ones draw as red dotted connection lines; ones too far to draw a
// sensible line are flagged teleport=1 and the viewer marks each endpoint instead (a "teleporter",
// like the cave-entrance markers). Compact as [from, to, type, teleport]; both endpoints must ship.
const provLL = new Map(provinces.map(p => [p.id, p]));
const gcKm = (a, b) => {
  const la1 = a.lat * Math.PI / 180, la2 = b.lat * Math.PI / 180;
  const dLa = la2 - la1, dLo = (b.lon - a.lon) * Math.PI / 180;
  const h = Math.sin(dLa / 2) ** 2 + Math.cos(la1) * Math.cos(la2) * Math.sin(dLo / 2) ** 2;
  return 2 * 6371 * Math.asin(Math.min(1, Math.sqrt(h)));
};
const TELEPORT_KM = 800;   // beyond this a straight connection line would sprawl across the map
const adjacencies = (readJsonOpt('civstudio-engine/src/main/resources/map/adjacencies.json') || [])
  .filter(a => shipped.has(a.from) && shipped.has(a.to))
  .map(a => {
    const pa = provLL.get(a.from), pb = provLL.get(a.to);
    const teleport = pa && pb && gcKm(pa, pb) > TELEPORT_KM ? 1 : 0;
    return [a.from, a.to, a.type || '', teleport];
  });

// The map/geo backbone (provinces + rings + lab, geo, geoNames, adjacencies — the ~2.2 MB bulk)
// is now assembled and served by the Java spectator server from the same committed map resources
// (com.civstudio.server.web.WorldBundle -> GET /api/bundle); the browser fetches window.BUNDLE at
// boot instead of loading a committed data.js. build.mjs owns only the ASSET side: it still bakes
// every binary asset (below) and writes this small manifest describing them — the baked-file
// descriptors, the plots.pack byte index, and the ring-less provinces' cull boxes — which the
// server can't regenerate (the Civ4 art + plot grids are absent from the server image). The
// server merges this manifest into the engine-derived bundle. See docs/client-server.md and
// web/README.md. (`provinces`/`geo`/`geoNames`/`adjacencies` are still computed above — the
// terrain bake and the size logs read them — but are no longer written here.)
const bboxes = {};                    // ring-less (sea/lake) provinces' plot-extent cull box (source px)
for (const p of provinces) if (p.bbox) bboxes[p.id] = p.bbox;
const manifest = {
  seed: +SEED,
  map, terrainColors, terrainLayer, terrainTiles, river, sea, shore, foam, ice, bonusIcons, trees, seaBands,
  loading,                            // committed loading-screen art (assets/loading-*.jpg), or []
  plotIndex: plotPack.index,          // {provId: [byteOffset, len]} into assets/plots.pack
  bboxes,                             // {provId: [x0,y0,x1,y1]} for ring-less provinces (server can't derive)
};
const manifestPath = path.join(ROOT, 'civstudio-engine/src/main/resources/map/web-asset-manifest.json');
fs.writeFileSync(manifestPath, JSON.stringify(manifest));

const terrainBytes = imgSizes[map.src.replace('assets/', '')] || 0;
const manifestKb = (JSON.stringify(manifest).length / 1024).toFixed(0);
const politicalKb = (fs.statSync(path.join(WEB, 'political.js')).size / 1024).toFixed(0);
console.log(`Built civstudio-engine/src/main/resources/map/web-asset-manifest.json (${manifestKb} KB, merged + served by the engine at /api/bundle) + web/political.js (${politicalKb} KB, lazy) + web/${map.src} (${(terrainBytes / 1024).toFixed(0)} KB) from seed ${SEED}`);
console.log(`  ${provinces.length} provinces (run-independent — live caravans come from the server)`);
console.log(`  terrain crop ${map.dw}×${map.dh}px`);
console.log(`  geo labels: ${geo.continents.length} continents · ${geo.superRegions.length} super-regions · ${geo.regions.length} regions`);
console.log(`  plots: ${plotPack.count} provinces packed into web/assets/plots.pack (${(plotPack.bytes / 1048576).toFixed(1)} MB, range-fetched per-plot terrain zoom)`);
console.log(`  terrain tiles: ${terrainTiles ? terrainTiles.src + ' (' + Object.keys(terrainTiles.cols).length + ' textures)' : 'skipped (no terrain-art.json / LFS textures)'}`);
console.log(`  river tile: ${river ? river.src : 'skipped (no allriverssmall.dds / LFS)'}`);
console.log(`  sea tile: ${sea ? sea.src : 'skipped (no seadetail.dds / LFS)'} · bands trop/temp/polar ${JSON.stringify([seaBands.trop, seaBands.temp, seaBands.polar])}`);
console.log(`  foam strip: ${foam ? `${foam.src} (${foam.w}x${foam.h})` : 'skipped (no wave_crest.dds / LFS)'}`);
console.log(`  ice tile: ${ice ? ice.src : 'skipped (no icepack_1024.dds / LFS)'}`);

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
  // latitude cooling: terrain.bmp ignores latitude (it paints the far north green), so tint the
  // high-latitude land pixels toward a pale tundra tone by the C2C temperature model — mirrors
  // geo/LatitudeClimate (temp = 40 - 0.6·|lat|, cool below 12°, full below -6°). Per-pixel here, so
  // latitude-only; the per-plot terrain layer additionally folds in each province's winter severity.
  const COLD_TINT = [120, 128, 135];
  const latOfSrcY = sp => { const t = (1 - 2 * sp / H) * Math.PI; return (2 * Math.atan(Math.exp(t)) - Math.PI / 2) * 180 / Math.PI; };
  const coldBlendAt = lat => { const temp = 40 - 54 * Math.min(1, Math.abs(lat) / 90); return (temp >= 12 ? 0 : temp <= -6 ? 1 : (12 - temp) / 18) * 0.7; };

  // downsample by box-averaging the tinted colours (index averaging is meaningless);
  // the whole-world crop needs more pixels than the old caravan crop to stay legible
  const dw = Math.min(cropW, 2816);
  const scale = cropW / dw;
  const dh = Math.round(cropH / scale);
  // ocean / inland_ocean pixels are baked TRANSPARENT so the animated water layer shows
  // through them (main.mjs draws it under this raster); land stays opaque. Coast (35) is
  // kept opaque so its shore tint survives at world zoom. Colour averages LAND sub-pixels
  // only (sea tint never dilutes the land), and alpha is the land fraction — so a downsampled
  // coast pixel is a soft, partly-transparent land edge over the water rather than a hard line.
  const WATER = new Set([15, 17]);
  const rgb = Buffer.alloc(dw * dh * 3);
  const alpha = Buffer.alloc(dw * dh);
  const sea = new Uint8Array(dw * dh);      // 1 = a pure-ocean pixel (no land sub-pixel) — depth pass fills it
  for (let j = 0; j < dh; j++) {
    const by0 = y0 + Math.floor(j * scale), by1 = Math.max(by0 + 1, y0 + Math.floor((j + 1) * scale));
    const cf = coldBlendAt(latOfSrcY((by0 + by1) / 2));   // latitude cooling blend for this row
    for (let i = 0; i < dw; i++) {
      const bx0 = x0 + Math.floor(i * scale), bx1 = Math.max(bx0 + 1, x0 + Math.floor((i + 1) * scale));
      let r = 0, g = 0, b = 0, nl = 0, ntot = 0;
      for (let yy = by0; yy < by1 && yy <= y1; yy++)
        for (let xx = bx0; xx < bx1 && xx <= x1; xx++) {
          ntot++;
          const idx = idxAt(xx, yy);
          if (WATER.has(idx)) continue;      // sea sub-pixel: excluded from colour, lowers alpha
          const t = TINT[idx]; r += t[0]; g += t[1]; b += t[2]; nl++;
        }
      const k = j * dw + i, o = k * 3;
      if (nl > 0) {
        let cr = r / nl, cg = g / nl, cb = b / nl;
        if (cf > 0) { cr = cr * (1 - cf) + COLD_TINT[0] * cf; cg = cg * (1 - cf) + COLD_TINT[1] * cf; cb = cb * (1 - cf) + COLD_TINT[2] * cf; }
        rgb[o] = cr | 0; rgb[o + 1] = cg | 0; rgb[o + 2] = cb | 0; alpha[k] = Math.round(nl / ntot * 255);
      } else sea[k] = 1;                     // pure ocean: rgb/alpha stay 0 until the depth pass below
    }
  }

  // depth banding: darken open ocean by distance from land (a bathymetry proxy — the heightmap
  // has no sea-level datum here). A distance transform over the ocean gives each sea pixel its
  // distance to the nearest coast; a smoothstep shelf→deep ramp becomes the alpha of a dark
  // seadeep tint painted into the (otherwise transparent) sea pixels, so the climate gradient
  // shows on the shelf and deep water reads dark. See docs/coastlines.md Phase C.
  const dist = distanceToLand(sea, dw, dh);
  const DEEP = seaDeepColor();               // dark deep-water tint (seadeepblend hue, dark theme)
  const DMAX = 26, MAXA = 168;               // shelf width in crop px; peak darkening alpha
  for (let k = 0; k < dw * dh; k++) {
    if (!sea[k]) continue;
    let t = Math.min(1, dist[k] / DMAX); t = t * t * (3 - 2 * t);   // smoothstep: 0 at the coast → 1 in the deep
    alpha[k] = Math.round(t * MAXA);
    const o = k * 3; rgb[o] = DEEP[0]; rgb[o + 1] = DEEP[1]; rgb[o + 2] = DEEP[2];
  }

  // the terrain crop is a real image asset (not inlined into the data); RGBA so the sea is
  // transparent. Lossy WebP with full-quality alpha: the raster is the blurred base under the crisp
  // per-plot terrain and shown small at world view, so lossy RGB is invisible while alphaQuality 100
  // keeps the coastline cut-out sharp.
  const src = queueWebp('terrain', dw, dh, rgb, alpha, { quality: 80 });

  return {
    src,
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

// rec.601 luminance of an [r,g,b] (function decl: hoisted, so the top-level bakeTerrain() call
// above it can reach it)
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
  const manifest = path.join(ROOT, 'civstudio-engine/src/main/resources/map/terrain-art.json');
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

// average RGB of a Civ4 .dds texture resolved via resolveArt (data/civ4/assets, case-insensitive);
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

// resolve an "Art/Terrain/.../X.dds" path to a real file, case-insensitively (the XML paths and
// on-disk names differ in case); null if absent. The web-baked textures live committed & non-LFS
// under data/civ4/assets (moved out of the LFS art tree so the build needs no `git lfs pull`);
// Civ4 art resolves from the committed, non-LFS data/civ4/assets tree (the old UnpackedArt/art
// LFS fallback was removed with the Git-LFS art, 2026-07).
function resolveArt(artPath) {
  if (!artPath) return null;
  const rel = artPath.replace(/^Art\//i, '').split('/');
  return resolveUnder(path.join(ROOT, 'data', 'civ4', 'assets'), rel);
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
  const mp = path.join(ROOT, 'civstudio-engine/src/main/resources/map/terrain-art.json');
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
    // authored (source-less) terrains: a dark warm cavern floor, plus the special Anbennar
    // surface terrains — fungal violet, deep old-growth, verdant/teal fey, crimson blood-grove,
    // shadowed marsh, pale glacier ice
    TERRAIN_CAVERN: [58, 45, 37], TERRAIN_MUSHROOM_FOREST: [84, 60, 98],
    TERRAIN_ANCIENT_FOREST: [30, 52, 22], TERRAIN_GLADEWAY: [56, 116, 52],
    TERRAIN_FEY_GLADEWAY: [42, 112, 98], TERRAIN_BLOODGROVES: [96, 36, 34],
    TERRAIN_SHADOW_SWAMP: [48, 46, 60], TERRAIN_GLACIER: [180, 198, 210],
    // water (coastal-shelf plots only — deep ocean has no plots and stays the animated base
    // gradient). COAST is the bright shallow shelf, SEA the darker shelf edge, so the terrain
    // key alone gives a coast→sea depth ramp; polar reads greyer, tropical more turquoise.
    TERRAIN_COAST: [92, 156, 178], TERRAIN_COAST_POLAR: [120, 158, 172], TERRAIN_COAST_TROPICAL: [102, 178, 190],
    TERRAIN_SEA: [52, 104, 140], TERRAIN_SEA_POLAR: [64, 96, 120], TERRAIN_SEA_TROPICAL: [46, 120, 150],
    TERRAIN_LAKE: [54, 118, 128], TERRAIN_LAKE_SHORE: [86, 150, 150],
  };
  // the synthetic terrains repurpose an existing ground texture (rocky/lush), so their
  // MEASURED average (via terrainRealColors) is the wrong hue — a cavern must read dark and
  // warm, not rocky grey. Force these to their authored colour, overriding the real average.
  const AUTHORED = ['TERRAIN_CAVERN', 'TERRAIN_MUSHROOM_FOREST', 'TERRAIN_ANCIENT_FOREST',
    'TERRAIN_GLADEWAY', 'TERRAIN_FEY_GLADEWAY', 'TERRAIN_BLOODGROVES', 'TERRAIN_SHADOW_SWAMP',
    'TERRAIN_GLACIER',
    'TERRAIN_COAST', 'TERRAIN_COAST_POLAR', 'TERRAIN_COAST_TROPICAL', 'TERRAIN_SEA',
    'TERRAIN_SEA_POLAR', 'TERRAIN_SEA_TROPICAL', 'TERRAIN_LAKE', 'TERRAIN_LAKE_SHORE'];
  const hex = c => '#' + [0, 1, 2].map(k => Math.max(0, Math.min(255, c[k] | 0)).toString(16).padStart(2, '0')).join('');
  // the plot zoom is a detail dive, so lift the blend×detail averages into a vibrant,
  // map-like range rather than the dark-theme tint the background bake uses
  const LIFT = 2.35;
  const lift = c => [c[0] * LIFT, c[1] * LIFT, c[2] * LIFT];
  const out = {};
  for (const k in fallback) out[k] = hex(fallback[k]);        // colourful default (already lifted)
  if (real) for (const [k, v] of real) out[k] = hex(lift(v)); // real textures override
  for (const k of AUTHORED) out[k] = hex(fallback[k]);        // …but authored terrains keep their hue
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
  const manifestPath = path.join(ROOT, 'civstudio-engine/src/main/resources/map/terrain-art.json');
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
  // the ground-texture atlas — the biggest asset. Its 256px columns align to WebP's block grid, so
  // slicing per-terrain tiles (extractTiles) stays clean; lossy quality is invisible on ground.
  const src = queueWebp('terrain-tiles', W, H, rgb, null, { quality: 82 });
  return { src, tile: T, cols };
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

// Slice C — bake a small WATER tile from the Civ4 river texture (routes/rivers/
// allriverssmall.dds) for the plot river ribbon (docs/river-rendering.md §2, Phase 1B).
// Unlike the terrain tiles (recoloured to a flat mean), this preserves the texture's wavy
// water STRANDS — which live in the DXT5 *alpha* channel (the RGB is a near-flat water
// colour) — by modulating the map's river-blue by per-texel strand coverage: darker water
// between the strands, bright ripples on them. So the ribbon reads as flowing water rather
// than a flat fill. Returns {src, tile}, or null when the art is absent (LFS not pulled /
// file://) — the renderer then keeps the flat-fill fallback.
function bakeRiverTile() {
  const RIVER_RGB = [74, 124, 170];   // cohesive with the map's river blue
  const T = 64;
  const artFile = resolveArt('Art/Terrain/Routes/Rivers/allriverssmall.dds');
  if (!artFile) return null;
  let img; try { img = decodeDds(fs.readFileSync(artFile)); } catch { return null; }
  const bx = img.width / T, by = img.height / T;
  const rgb = Buffer.alloc(T * T * 3);
  for (let j = 0; j < T; j++)
    for (let i = 0; i < T; i++) {
      let a = 0, n = 0;
      for (let y = Math.floor(j * by); y < Math.floor((j + 1) * by); y++)
        for (let x = Math.floor(i * bx); x < Math.floor((i + 1) * bx); x++) { a += img.rgba[(y * img.width + x) * 4 + 3]; n++; }
      const strand = a / n / 255;         // 0..1 ripple coverage, from the water texture's alpha
      const k = 0.6 + 1.5 * strand;       // dark water between strands → bright ripples on them
      const o = (j * T + i) * 3;
      rgb[o] = Math.min(255, RIVER_RGB[0] * k) | 0;
      rgb[o + 1] = Math.min(255, RIVER_RGB[1] * k) | 0;
      rgb[o + 2] = Math.min(255, RIVER_RGB[2] * k) | 0;
    }
  return { src: queueWebp('river', T, T, rgb, null, { quality: 85 }), tile: T };
}

// Bake a seamless GREYSCALE ripple tile from the real Civ4 sea texture (textures/water/
// seadetail.dds) — the wave pattern only, centred on mid-grey (128). The ocean's COLOUR comes
// from the climate latitude gradient (bakeSeaBands / the web renderer); this tile is drawn over
// it with `soft-light`, so grey=128 leaves the colour untouched while darker/lighter texels
// deepen/brighten it into ripples. (seadetail carries its pattern in RGB, so we read luminance,
// unlike the river ribbon whose ripples are in the DXT5 alpha.) Returns {src, tile}, or null
// when the art is absent (LFS not pulled / file://) — the renderer then draws the flat gradient.
function bakeSeaTile() { return bakeRippleTile('Art/Terrain/textures/water/seadetail.dds', `sea`, 1.1); }

// The shore shallows carry the same treatment (docs/coastlines.md Phase D): a neutral-mean
// greyscale ripple from the Civ4 shore wave texture (textures/water/shoredetail.dds), drawn
// over the shallow band with `soft-light` so it ripples the shore hue without recolouring it.
// A touch more contrast than the open sea so the near-shore chop reads. Null → flat shallows.
function bakeShoreTile() { return bakeRippleTile('Art/Terrain/textures/water/shoredetail.dds', `shore`, 1.3); }

// Bake a seamless COLOUR ice tile from the real Civ4 pack-ice texture (features/icepack/icepack_1024.dds).
// The texture's upper ~65% is a clean cracked-ice surface (the lower strip is a fringe of edge icicles we
// skip); we crop that clean region and downsample it to a square tile, keeping colour so the web can
// texture the shelf ice floes with real art instead of flat white squares (docs/coastlines.md Phase G).
// Returns {src, tile} or null (art absent → drawSeaIce keeps its procedural pale floes).
function bakeIceTile() {
  const artFile = resolveArt('Art/Terrain/features/icepack/icepack_1024.dds');
  if (!artFile) return null;
  let img; try { img = decodeDds(fs.readFileSync(artFile)); } catch { return null; }
  const T = 256;
  const CROP = Math.floor(img.height * 0.64);   // clean cracked-ice region (skip the bottom fringe strip)
  const bx = CROP / T, by = CROP / T;           // sample a square crop of the clean region → square tile
  const rgb = Buffer.alloc(T * T * 3);
  for (let j = 0; j < T; j++)
    for (let i = 0; i < T; i++) {
      let r = 0, g = 0, b = 0, n = 0;
      for (let y = Math.floor(j * by); y < Math.floor((j + 1) * by); y++)
        for (let x = Math.floor(i * bx); x < Math.floor((i + 1) * bx); x++) {
          const o = (y * img.width + x) * 4; r += img.rgba[o]; g += img.rgba[o + 1]; b += img.rgba[o + 2]; n++;
        }
      const d = j * T + i; rgb[d * 3] = r / n | 0; rgb[d * 3 + 1] = g / n | 0; rgb[d * 3 + 2] = b / n | 0;
    }
  const assets = path.join(WEB, 'assets');
  fs.mkdirSync(assets, { recursive: true });
  return { src: queueWebp('ice', T, T, rgb, null, { quality: 85 }), tile: T };
}

// Bake real Civ4 foliage sprites so the plot layer can stamp actual trees/palms/reeds instead of the
// procedural blobs (docs/features-art.md). The Civ4 `trees_*.dds` files are irregular billboard sheets
// (individual cutouts on a transparent background, UV-mapped by their .nif). We extract the cutouts by
// CONNECTED-COMPONENT labelling of the alpha (no .nif needed): flood every opaque island, keep the
// tree-like ones (moderate size, foliage fill fraction, green-dominant so bark/snow/autumn variants are
// skipped), and pack the chosen cutouts into one horizontal RGBA strip. Returns {group:{src,w,h,sprites:
// [[x,y,w,h]...]}} keyed by feature group, or null when the art is absent (procedural blobs stay).
function bakeFeatureSprites() {
  // billboard-imposter (*_1024.dds) atlases the connected-component extractor handles
  const groups = {
    leafy:  'Art/Terrain/features/treeleafy/trees_1024.dds',   // FOREST / JUNGLE
    palm:   'Art/Terrain/features/savanna/palms_1024.dds',     // SAVANNA / OASIS
    swamp:  'Art/Terrain/features/swamp/trees1.dds',           // SWAMP
    bamboo: 'Art/Terrain/features/bamboo/bambooattachments.dds', // BAMBOO (leaf-cluster atlas)
  };
  const out = {};
  for (const [name, art] of Object.entries(groups)) {
    const g = bakeSpriteGroup(art, name);
    if (g) out[name] = g;
  }
  // Cactus and very-tall-grass are 3D-model-only (no billboard atlas), so render their
  // Civ4 .nif models to sprite sheets via tools/nifbake (see docs/features-art.md).
  const nif = (rel, tex, name, opts) => {
    const nifPath = resolveArt('Art/Terrain/features/' + rel), texPath = resolveArt('Art/Terrain/features/' + tex);
    if (!nifPath || !texPath) return;
    try {
      const g = bakeNifGroup([{ nif: nifPath, tex: texPath }], name, path.join(WEB, 'assets'), opts.size, opts);
      if (g) out[name] = g;
    } catch (e) { console.log(`  ${name}: nif render skipped (${e.message})`); }
  };
  // emit: route the nif atlas (interleaved RGBA) through the WebP queue as trees-<name>.webp, like
  // the billboard groups, so every foliage sprite ships WebP too
  const emit = (n, w, h, rgba) => queueWebpRGBA(`trees-${n}`, w, h, rgba, { quality: 90 });
  nif('kaktus/kaktus2.nif', 'kaktus/cactus01.dds', 'cactus', { size: 220, emit });
  nif('sword_grass/wheat.nif', 'sword_grass/sword_grass.dds', 'grass', { size: 200, flat: 'low', emit });
  return Object.keys(out).length ? out : null;
}
function bakeSpriteGroup(artPath, name) {
  const file = resolveArt(artPath);
  if (!file) return null;
  let img; try { img = decodeDds(fs.readFileSync(file)); } catch { return null; }
  const { width: W, height: H, rgba } = img;
  const A = 48;                                  // alpha threshold: a pixel is "solid" foliage
  const lab = new Uint8Array(W * H);             // visited flags
  const comps = [], stack = [];
  for (let y0 = 0; y0 < H; y0++) for (let x0 = 0; x0 < W; x0++) {
    const start = y0 * W + x0;
    if (lab[start] || rgba[start * 4 + 3] < A) continue;
    let minx = x0, maxx = x0, miny = y0, maxy = y0, cnt = 0, sr = 0, sg = 0, sb = 0;
    lab[start] = 1; stack.length = 0; stack.push(start);
    while (stack.length) {
      const p = stack.pop(), px = p % W, py = (p / W) | 0;
      cnt++; sr += rgba[p * 4]; sg += rgba[p * 4 + 1]; sb += rgba[p * 4 + 2];
      if (px < minx) minx = px; if (px > maxx) maxx = px; if (py < miny) miny = py; if (py > maxy) maxy = py;
      if (px > 0     && !lab[p - 1] && rgba[(p - 1) * 4 + 3] >= A) { lab[p - 1] = 1; stack.push(p - 1); }
      if (px < W - 1 && !lab[p + 1] && rgba[(p + 1) * 4 + 3] >= A) { lab[p + 1] = 1; stack.push(p + 1); }
      if (py > 0     && !lab[p - W] && rgba[(p - W) * 4 + 3] >= A) { lab[p - W] = 1; stack.push(p - W); }
      if (py < H - 1 && !lab[p + W] && rgba[(p + W) * 4 + 3] >= A) { lab[p + W] = 1; stack.push(p + W); }
    }
    const bw = maxx - minx + 1, bh = maxy - miny + 1;
    comps.push({ minx, miny, bw, bh, fill: cnt / (bw * bh), mr: sr / cnt, mg: sg / cnt, mb: sb / cnt, area: cnt });
  }
  const green = c => c.mg >= c.mr * 0.9 && c.mg >= c.mb * 0.95 && (c.mr + c.mg + c.mb) / 3 < 185;
  const shape = c => c.bw >= 22 && c.bw <= 190 && c.bh >= 22 && c.bh <= 210 && c.fill >= 0.1 && c.fill <= 0.85
    && c.bw / c.bh < 2.2 && c.bh / c.bw < 3.2;
  let cand = comps.filter(c => shape(c) && green(c));
  if (cand.length < 3) cand = comps.filter(shape);           // relax colour if the sheet isn't green-dominant
  cand.sort((a, b) => b.area - a.area);
  const chosen = cand.slice(0, 10);
  if (!chosen.length) return null;
  const GAP = 1, maxH = Math.max(...chosen.map(c => c.bh));
  let totW = 0; for (const c of chosen) totW += c.bw + GAP;
  const rgb = Buffer.alloc(totW * maxH * 3), alpha = Buffer.alloc(totW * maxH);
  const sprites = []; let ox = 0;
  for (const c of chosen) {
    for (let y = 0; y < c.bh; y++) for (let x = 0; x < c.bw; x++) {
      const so = ((c.miny + y) * W + (c.minx + x)) * 4, d = y * totW + (ox + x);
      rgb[d * 3] = rgba[so]; rgb[d * 3 + 1] = rgba[so + 1]; rgb[d * 3 + 2] = rgba[so + 2]; alpha[d] = rgba[so + 3];
    }
    sprites.push([ox, 0, c.bw, c.bh]);
    ox += c.bw + GAP;
  }
  const assets = path.join(WEB, 'assets');
  fs.mkdirSync(assets, { recursive: true });
  const src = queueWebp(`trees-${name}`, totW, maxH, rgb, alpha, { quality: 90 });
  return { src, w: totW, h: maxH, sprites };
}

// Bake the real Civ4 shoreline foam (docs/coastlines.md Phase G) from waves/wave_crest.dds — a
// horizontal foam-crest strip (white crest at the top, alpha fading to clear below). We keep it as
// an RGBA PNG so the web can lay a true wave crest along every shoreline edge (fade reaching into
// the water), replacing the old procedural white foam line. The strip is cropped vertically to the
// rows that actually carry foam (the bottom of the source is fully transparent dead space) and left
// full width (it tiles east-west along the shore). Returns {src, w, h} or null (art absent → the
// procedural foam line stays). The crest RGB is clamped bright so the foam reads clean white.
function bakeFoamTile() {
  const artFile = resolveArt('Art/Terrain/waves/wave_crest.dds');
  if (!artFile) return null;
  let img; try { img = decodeDds(fs.readFileSync(artFile)); } catch { return null; }
  const W = img.width, H = img.height;
  // crop to the last row whose mean alpha is meaningful (the crest lives in the top band)
  let hi = 0;
  for (let y = 0; y < H; y++) { let a = 0; for (let x = 0; x < W; x++) a += img.rgba[(y * W + x) * 4 + 3]; if (a / W > 8) hi = y; }
  const CH = Math.min(H, hi + 2);
  const rgb = Buffer.alloc(W * CH * 3), alpha = Buffer.alloc(W * CH);
  for (let y = 0; y < CH; y++)
    for (let x = 0; x < W; x++) {
      const so = (y * W + x) * 4, d = y * W + x;
      const L = Math.max(img.rgba[so], img.rgba[so + 1], img.rgba[so + 2]);   // toward the crest's brightest channel
      const g = Math.min(255, 190 + ((L * 65) / 255 | 0));                    // clamp bright: clean white foam
      rgb[d * 3] = g; rgb[d * 3 + 1] = g; rgb[d * 3 + 2] = g;
      alpha[d] = img.rgba[so + 3];
    }
  const assets = path.join(WEB, 'assets');
  fs.mkdirSync(assets, { recursive: true });
  return { src: queueWebp('foam', W, CH, rgb, alpha, { quality: 90 }), w: W, h: CH };
}

// Slice the real Civ4 resource icons out of GameFont_120.tga into one atlas + a {bonusType: cellIndex}
// manifest, so the web draws a true per-resource symbol on each resourced plot instead of the
// procedural category glyph (docs/bonus-sprite-bake.md). GameFont_120 is the higher-resolution font
// (25px cells vs the base GameFont.tga's 21px — crisper icons at deep zoom); its resource block is a
// fixed 25-column grid of 25px cells starting at (0,497); a bonus's cell is its FontButtonIndex
// (CIV4ArtDefines_Bonus.xml), reached through its ArtDefineTag (CIV4BonusInfos.xml). Returns null if
// any source is absent (the renderer keeps the procedural glyphs); a bonus with a negative index
// (no unique font icon) or an out-of-grid cell is left out and also falls back to the glyph.
function bakeBonusIcons() {
  const binfo = path.join(ROOT, 'data/civ4/CIV4BonusInfos.xml');
  const adef = path.join(ROOT, 'data/civ4/CIV4ArtDefines_Bonus.xml');
  const gf = loadGameFont(ROOT);   // shared GameFont reader (gamefont.mjs)
  if (!gf || !fs.existsSync(binfo) || !fs.existsSync(adef)) return null;
  // BONUS_* → ArtDefineTag, then ArtDefineTag → FontButtonIndex (regex on the raw XML — Civ4's
  // default namespace lives only on the root, so the child tags read literally)
  const tagOf = {};
  for (const m of fs.readFileSync(binfo, 'utf8').matchAll(/<BonusInfo>[\s\S]*?<Type>(BONUS_[A-Z0-9_]+)<\/Type>[\s\S]*?<\/BonusInfo>/g)) {
    const a = m[0].match(/<ArtDefineTag>([^<]+)<\/ArtDefineTag>/); if (a) tagOf[m[1]] = a[1].trim();
  }
  const fbiOf = {};
  for (const m of fs.readFileSync(adef, 'utf8').matchAll(/<BonusArtInfo>[\s\S]*?<Type>(ART_DEF_BONUS_[A-Z0-9_]+)<\/Type>[\s\S]*?<\/BonusArtInfo>/g)) {
    const f = m[0].match(/<FontButtonIndex>(-?\d+)<\/FontButtonIndex>/); if (f) fbiOf[m[1]] = +f[1];
  }
  const bonuses = JSON.parse(fs.readFileSync(path.join(ROOT, 'civstudio-engine/src/main/resources/bonuses.json'), 'utf8')).map(b => b.type);
  const picks = [];   // [type, rgbaCell] — the shared reader returns each cell's pixels
  for (const t of bonuses) {
    const cell = resourceCellRGBA(gf, fbiOf[tagOf[t]]);   // null → no unique icon / out of grid
    if (cell) picks.push([t, cell]);
  }
  if (!picks.length) return null;
  const cols = 16, rows = Math.ceil(picks.length / cols);
  const aw = cols * GF_CELL, ah = rows * GF_CELL;
  const rgba = Buffer.alloc(aw * ah * 4);
  const index = {};
  picks.forEach(([t, cell], i) => {
    index[t] = i;
    const dx = (i % cols) * GF_CELL, dy = Math.floor(i / cols) * GF_CELL;
    for (let y = 0; y < GF_CELL; y++) {
      const so = y * GF_CELL * 4, d = ((dy + y) * aw + dx) * 4;
      cell.copy(rgba, d, so, so + GF_CELL * 4);
    }
  });
  const src = queueWebpRGBA('bonus-icons', aw, ah, rgba, { quality: 90 });
  console.log(`  bonus icons: ${src} (${picks.length} GameFont resource symbols)`);
  return { src, cell: GF_CELL, cols, count: picks.length, index };
}

// Bake a seamless GREYSCALE ripple tile from a Civ4 water detail texture — the wave pattern
// only, centred on mid-grey (128) so a `soft-light` overlay leaves the base colour untouched
// while darker/lighter texels deepen/brighten it. `contrast` scales the deviation from the
// mean. Returns {src, tile}, or null when the art is absent (LFS not pulled / file://).
function bakeRippleTile(artRel, name, contrast) {
  const T = 128;   // larger tile → the repeat is far less obvious than the old 64px grid
  const artFile = resolveArt(artRel);
  if (!artFile) return null;
  let img; try { img = decodeDds(fs.readFileSync(artFile)); } catch { return null; }
  const bx = img.width / T, by = img.height / T;
  const lum = new Float64Array(T * T); let mean = 0;
  for (let j = 0; j < T; j++)
    for (let i = 0; i < T; i++) {
      let r = 0, g = 0, b = 0, n = 0;
      for (let y = Math.floor(j * by); y < Math.floor((j + 1) * by); y++)
        for (let x = Math.floor(i * bx); x < Math.floor((i + 1) * bx); x++) {
          const o = (y * img.width + x) * 4; r += img.rgba[o]; g += img.rgba[o + 1]; b += img.rgba[o + 2]; n++;
        }
      const L = (0.299 * r + 0.587 * g + 0.114 * b) / n;
      lum[j * T + i] = L; mean += L;
    }
  mean /= T * T;
  const rgb = Buffer.alloc(T * T * 3);
  for (let k = 0; k < T * T; k++) {
    const g = Math.max(0, Math.min(255, 128 + (lum[k] - mean) * contrast)) | 0;   // soft neutral-mean ripple
    rgb[k * 3] = g; rgb[k * 3 + 1] = g; rgb[k * 3 + 2] = g;
  }
  return { src: queueWebp(name, T, T, rgb, null, { quality: 85 }), tile: T };
}

// The ocean's climate band colours: tropical / temperate / polar sea, keyed by |latitude| in
// the web renderer's vertical gradient. Each takes the authentic HUE of the matching Civ4 sea
// blend texture (seatrop/sea/seapol) rescaled to a hand-tuned dark-theme LUMINANCE (tropical
// brightest/tealest, polar dimmest/greyest), mirroring how the land terrains are recoloured.
// Falls back to the dark anchors when the art is absent (LFS not pulled).
function bakeSeaBands() {
  const band = (art, anchor) => { const c = avgDds(art); return c ? hueAtLuminance(anchor, c) : anchor; };
  return {
    trop:  band('Art/Terrain/textures/water/seatropblend.dds', [26, 56, 76]),
    temp:  band('Art/Terrain/textures/water/seablend.dds',     [20, 42, 68]),
    polar: band('Art/Terrain/textures/water/seapolblend.dds',  [32, 42, 54]),
    // the shallows tint: the tropical sea HUE at a bright coastal-teal luminance — the shore is a
    // brighter, lighter version of the open water. (shoreblend itself is a neutral sandy blend
    // with no usable water hue, like the land blends.) See docs/coastlines.md Phase D.
    shore: band('Art/Terrain/textures/water/seatropblend.dds', [116, 178, 196]),
  };
}

// The deep-ocean tint (for the depth-banding pass): the authentic hue of the Civ4 seadeep blend
// at a very dark theme luminance, so open water reads far darker than the shelf. Dark fallback.
function seaDeepColor() {
  const c = avgDds('Art/Terrain/textures/water/seadeepblend.dds');
  return c ? hueAtLuminance([10, 20, 34], c) : [11, 21, 35];
}

// Two-pass chamfer distance transform: for each ocean cell (`sea[k]===1`), the approximate
// Euclidean distance in pixels to the nearest non-ocean (land/coast) cell; 0 on land. Cheap
// (two linear sweeps), enough for a smooth shelf→deep ramp. No E-W wrap (a crop-edge effect
// only, where open ocean is deep anyway).
function distanceToLand(sea, w, h) {
  const INF = 1e9, d = new Float64Array(w * h);
  for (let k = 0; k < w * h; k++) d[k] = sea[k] ? INF : 0;
  const D = 1, Q = Math.SQRT2;
  for (let y = 0; y < h; y++)
    for (let x = 0; x < w; x++) {
      const k = y * w + x; let v = d[k];
      if (x > 0)          v = Math.min(v, d[k - 1] + D);
      if (y > 0)          v = Math.min(v, d[k - w] + D);
      if (x > 0 && y > 0) v = Math.min(v, d[k - w - 1] + Q);
      if (x < w - 1 && y > 0) v = Math.min(v, d[k - w + 1] + Q);
      d[k] = v;
    }
  for (let y = h - 1; y >= 0; y--)
    for (let x = w - 1; x >= 0; x--) {
      const k = y * w + x; let v = d[k];
      if (x < w - 1)              v = Math.min(v, d[k + 1] + D);
      if (y < h - 1)              v = Math.min(v, d[k + w] + D);
      if (x < w - 1 && y < h - 1) v = Math.min(v, d[k + w + 1] + Q);
      if (x > 0 && y < h - 1)     v = Math.min(v, d[k + w - 1] + Q);
      d[k] = v;
    }
  return d;
}

// Pack every displayed province's canonical plot grid (map/provinces/<id>.json.gz,
// each a complete standalone gzip member) into ONE web/assets/plots.pack by
// concatenating the raw .gz bytes as-is (no gunzip/re-encode), and return a
// byte-offset index {id: [offset, len]} the page inlines and range-fetches from.
// One ~30 MB file replaces thousands of loose per-province .js files; the page
// range-fetches a single province's slice and gunzips it in the browser. The
// pack is gitignored (regenerable from the committed grids). Sets p.hasPlots.
function packPlots(provs) {
  const srcDir = path.join(ROOT, 'civstudio-engine/src/main/resources/map/provinces');
  const outDir = path.join(WEB, 'assets');
  // drop the old loose-file layout if a previous build left it behind
  fs.rmSync(path.join(outDir, 'plots'), { recursive: true, force: true });
  fs.mkdirSync(outDir, { recursive: true });
  const chunks = [];
  const index = {};
  let offset = 0;
  for (const p of provs) {
    const gz = path.join(srcDir, `${p.id}.json.gz`);
    if (!fs.existsSync(gz)) { p.hasPlots = false; continue; }
    const buf = fs.readFileSync(gz);          // raw gzip bytes, used verbatim
    chunks.push(buf);
    index[p.id] = [offset, buf.length];
    offset += buf.length;
    p.hasPlots = true;
    // ring-less provinces (sea/lake) have no polygon for provSrcBox to measure, so give them a
    // plot-extent bbox (source px) for viewport culling — parse the grid once to bound it
    if (!p.rings) p.bbox = plotBBox(buf);
  }
  fs.writeFileSync(path.join(outDir, 'plots.pack'), Buffer.concat(chunks));
  return { index, count: Object.keys(index).length, bytes: offset };
}

// the source-pixel bounding box [x0,y0,x1,y1] of a gzipped plot grid, or null if empty —
// the ring-less (sea/lake) provinces' cull extent, since they ship no outline
function plotBBox(gzBuf) {
  const arr = JSON.parse(zlib.gunzipSync(gzBuf).toString());
  if (!arr.length) return null;
  let x0 = 1e9, y0 = 1e9, x1 = -1e9, y1 = -1e9;
  for (const q of arr) { if (q.x < x0) x0 = q.x; if (q.x > x1) x1 = q.x; if (q.y < y0) y0 = q.y; if (q.y > y1) y1 = q.y; }
  return [x0, y0, x1, y1];
}

