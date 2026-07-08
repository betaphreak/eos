"use strict";
"use strict";
const BUNDLE = window.BUNDLE;
const ROUTE_COLORS = ["#d9603b", "#3d9bd1", "#57b368", "#9b7bd4", "#d98cae", "#c9a227"];

// ---- data prep ----
const J = BUNDLE.journeys.map((j, i) => ({ ...j, color: ROUTE_COLORS[i % ROUTE_COLORS.length], idx: i }));
const P = BUNDLE.provinces;
const day = s => Date.UTC(+s.slice(0,4), +s.slice(5,7)-1, +s.slice(8,10)) / 864e5;
const t0 = day(BUNDLE.meta.dateStart), t1 = day(BUNDLE.meta.dateEnd);
const fmtDate = t => { const d = new Date(t*864e5); return d.toLocaleDateString("en-GB",{day:"numeric",month:"short",year:"numeric",timeZone:"UTC"}); };
const fmtInt = n => Math.round(n).toLocaleString("en-US");
J.forEach(j => j.keys.forEach(k => k.t = day(k.date)));
// projection: lon/lat -> the exact source pixel on terrain.bmp (the inverse of the
// maps ProvinceExporter used) -> the baked crop's fit rectangle -> screen, with a
// pan/zoom camera (cam.k scale, cam.x/cam.y translate) applied last. Using the same
// source-pixel formulas the build baked with keeps dots/rings pinned to the map.
const MAP = BUNDLE.map;
const sxSrc = lon => (lon + 180) / 360 * (MAP.W - 1);
const sySrc = lat => { const r = lat * Math.PI / 180; return (1 - Math.log(Math.tan(r / 2 + Math.PI / 4)) / Math.PI) / 2 * MAP.H; };
let VIEW = { w:0, h:0, dx:0, dy:0, dw:0, dh:0, dpr:1 };
const cam = { k: 1, x: 0, y: 0 };
function fitView(w, h) {
  const cw = MAP.x1 - MAP.x0, ch = MAP.y1 - MAP.y0;   // crop extent in source px
  const s = Math.min(w / cw, h / ch);                 // contain: whole crop visible at k=1
  VIEW.w = w; VIEW.h = h; VIEW.dw = cw * s; VIEW.dh = ch * s;
  VIEW.dx = (w - VIEW.dw) / 2; VIEW.dy = (h - VIEW.dh) / 2;
  S.baseVersion++;
}
// base (unzoomed) screen coords, then the camera
const baseXr = sp => VIEW.dx + (sp - MAP.x0) / (MAP.x1 - MAP.x0) * VIEW.dw;
const baseYr = sp => VIEW.dy + (sp - MAP.y0) / (MAP.y1 - MAP.y0) * VIEW.dh;
const pxr = sp => cam.x + cam.k * baseXr(sp);
const pyr = sp => cam.y + cam.k * baseYr(sp);
const px = lon => pxr(sxSrc(lon));
const py = lat => pyr(sySrc(lat));
// inverse of py: the latitude at a screen y (undo camera → crop rect → source pixel → the
// Mercator sySrc). Used to colour the ocean by climate band down the viewport.
const latAtScreenY = y => {
  const sp = MAP.y0 + (((y - cam.y) / cam.k - VIEW.dy) / VIEW.dh) * (MAP.y1 - MAP.y0);
  const t = (1 - 2 * sp / MAP.H) * Math.PI;
  return (2 * Math.atan(Math.exp(t)) - Math.PI / 2) * 180 / Math.PI;
};
const TCOL = BUNDLE.terrainColors || {};
const K_PLOT = 5;                 // camera scale at which plots begin to fade in
const K_TEX = 16;                 // camera scale at which flat tiles give way to real textures
const K_MAX = 256;                // deepest zoom (4× past the old 64× cap — magnifies the plot layer)
const TT = BUNDLE.terrainTiles;   // ground-texture atlas {src, tile, cols:{TERRAIN_*: column}} or null
const RIVER = BUNDLE.river;        // water tile {src, tile} for the river ribbon, or null (flat-fill fallback)
const SEA = BUNDLE.sea;            // greyscale ripple tile {src, tile} for the ocean layer, or null (gradient only)
const SHORE = BUNDLE.shore;        // greyscale shore-wave tile {src, tile} for the shallows, or null (flat shallows)
const FOAM_ART = BUNDLE.foam;      // real Civ4 wave-crest foam strip {src, w, h}, or null (procedural foam line)
const ICE_ART = BUNDLE.ice;        // real Civ4 pack-ice tile {src, tile}, or null (procedural pale floes)
const BONUS_ICONS = BUNDLE.bonusIcons;  // real Civ4 resource icons {src, cell, cols, index:{type:i}}, or null (procedural glyphs)
const TREES = BUNDLE.trees;        // real Civ4 foliage sprites {leafy,palm,swamp:{src,w,h,sprites}}, or null (procedural blobs)
const SEA_BANDS = BUNDLE.seaBands; // {trop, temp, polar, shore} climate sea + shallows colours
// political layer: filled lazily from web/political.js on first switch to Political mode
// (see panel.ensurePolitical). Kept as stable object refs so importers see the populated tables.
const COUNTRIES = {};   // owner tag -> {name, color}
const CULTURES = {};     // culture key -> {name, group, color}
const RELIGIONS = {};    // religion key -> {name, group, color}
const GEO_NAMES = BUNDLE.geoNames || {};   // raw-key -> display-name dictionaries for province crumbs
// resolve a province's geographic crumb tiers ([displayName, rawKey] each, or null) from its raw
// keys — the names live once in GEO_NAMES instead of being duplicated onto every province
function provGeo(p) {
  const reg = p.region, area = p.area, cont = p.continent;
  return {
    continent: cont ? [GEO_NAMES.continent?.[cont] || null, cont] : null,
    superRegion: reg ? [GEO_NAMES.superByRegion?.[reg] || null, GEO_NAMES.superKeyByRegion?.[reg] || null] : null,
    region: reg ? [GEO_NAMES.region?.[reg] || null, reg] : null,
    area: area ? [GEO_NAMES.area?.[area] || null, area] : null,
  };
}
// whether the active overlay is a political colouring (nation/culture/faith)
function isPolitical() {
  return S.overlay === "nation" || S.overlay === "culture" || S.overlay === "faith";
}
// the active political dimension for a province under the current overlay: its raw key + the
// {name, color} table entry, or a null entry when the overlay isn't political / the province has none
function polOf(p) {
  switch (S.overlay) {
    case "nation":  return { key: p.owner,    e: p.owner    && COUNTRIES[p.owner] };
    case "culture": return { key: p.culture,  e: p.culture  && CULTURES[p.culture] };
    case "faith":   return { key: p.religion, e: p.religion && RELIGIONS[p.religion] };
    default:        return { key: null, e: null };
  }
}
const LY = BUNDLE.terrainLayer || {};   // TERRAIN_* -> Civ4 LayerOrder (higher bleeds over lower)
const NB4 = [[1, 0], [-1, 0], [0, 1], [0, -1]];
const _rgb = {};                  // "#rrggbb" -> [r,g,b], memoised
function terrainRgb(type) {
  const h = TCOL[type]; if (!h) return [70, 74, 68];
  return _rgb[h] || (_rgb[h] = [parseInt(h.slice(1, 3), 16), parseInt(h.slice(3, 5), 16), parseInt(h.slice(5, 7), 16)]);
}
// a province's source-pixel bounding box (from its outline rings), cached; null for seas
function provSrcBox(p) {
  if (p._sbox !== undefined) return p._sbox;
  if (!p.rings) {
    // ring-less (sea/lake) provinces carry a plot-extent bbox instead (build.mjs packPlots)
    if (p.bbox) return p._sbox = { x0: p.bbox[0], y0: p.bbox[1], x1: p.bbox[2], y1: p.bbox[3] };
    return p._sbox = null;
  }
  let x0 = 1e9, y0 = 1e9, x1 = -1e9, y1 = -1e9;
  for (const ring of p.rings) for (const pt of ring) {
    if (pt[0] < x0) x0 = pt[0]; if (pt[0] > x1) x1 = pt[0];
    if (pt[1] < y0) y0 = pt[1]; if (pt[1] > y1) y1 = pt[1];
  }
  return p._sbox = { x0, y0, x1, y1 };
}
const PLOT_INDEX = BUNDLE.plotIndex || {};       // {provId: [byteOffset, len]} into plots.pack
const MAXD = BUNDLE.meta.maxDays;
const lerp = (a,b,t) => a + (b-a)*t;
function heatColor(days) {                      // amber (low) -> red (high), over dark terrain
  const t = Math.pow(days/MAXD, 0.5);
  return `rgba(${lerp(236,228,t)|0},${lerp(178,96,t)|0},${lerp(96,56,t)|0},${(0.12+0.6*t).toFixed(3)})`;
}
function provPath(p) {                           // Path2D of a province's rings, rebuilt on view change
  if (p._pv === S.viewVersion) return p._path;
  const path = new Path2D();
  for (const ring of p.rings) {
    ring.forEach((pt,i)=>{ const x=pxr(pt[0]), y=pyr(pt[1]); i?path.lineTo(x,y):path.moveTo(x,y); });
    path.closePath();
  }
  p._path = path; p._pv = S.viewVersion; return path;
}
const cv = document.getElementById("map"), ctx = cv.getContext("2d");
const stage = document.getElementById("stage");
const cssVar = n => getComputedStyle(document.documentElement).getPropertyValue(n).trim();
function journeyPos(j, t) {
  const ks = j.keys;
  if (t <= ks[0].t) return { lon: ks[0].lon, lat: ks[0].lat, k: ks[0], arrived: false, started: t >= t0 };
  const last = ks[ks.length-1];
  if (t >= last.t) return { lon: last.lon, lat: last.lat, k: last, arrived: true, started: true };
  let i = 0; while (i < ks.length-1 && ks[i+1].t <= t) i++;
  const a = ks[i], b = ks[i+1], f = (t - a.t) / Math.max(1, b.t - a.t);
  return { lon: a.lon + (b.lon-a.lon)*f, lat: a.lat + (b.lat-a.lat)*f, k: a, arrived: false, started: true };
}
// interpolate a scalar telemetry field along keyframes
function lerpField(j, t, field) {
  const ks = j.keys;
  if (t <= ks[0].t) return ks[0][field];
  const last = ks[ks.length-1]; if (t >= last.t) return last[field];
  let i=0; while (i<ks.length-1 && ks[i+1].t<=t) i++;
  const a=ks[i], b=ks[i+1], f=(t-a.t)/Math.max(1,b.t-a.t);
  return a[field] + (b[field]-a[field])*f;
}
const destSet = new Set(J.map(j=>j.destId));
function clampAxis(camv, base, dim, viewDim) {
  const size = cam.k * dim, pos = camv + cam.k * base;
  if (size <= viewDim) return (viewDim - size) / 2 - cam.k * base;   // centre, no pan on this axis
  // allow the map to be panned until its top/bottom edge reaches the viewport centre (margin =
  // viewDim/2), so a province at the very edge of the mapped latitudes can still be centred (e.g.
  // a deep link to a far-north coast). Beyond the edge the polar sea gradient fills the gap.
  const m = viewDim / 2;
  return Math.min(m, Math.max(viewDim - size - m, pos)) - cam.k * base;
}
// the world's on-screen width (one full 360° of longitude) at the current zoom — the
// horizontal wrap period of the cylindrical map
function worldW() { return cam.k * VIEW.dw; }
function clampPan() {
  const w = worldW();
  cam.x = ((cam.x % w) + w) % w;   // wrap east-west around the cylinder (seam is invisible: the
                                   // draw loop tiles world copies to fill the viewport)
  cam.y = clampAxis(cam.y, VIEW.dy, VIEW.dh, VIEW.h);   // but clamp north-south to the poles
}

// ---- shared mutable state (was top-level lets; folded into one object so the modules
// can read/write it across the ES-module boundary) ----
export const S = {
  baseVersion: 0,        // bumped on real projection/camera change (pan/zoom/resize)
  viewVersion: 0,        // per-world-copy cache key derived from baseVersion in draw()
  showHeat: true,
  showCost: false,
  pov: "god",            // camera POV: "god" (free look) | "timeline" | "replay" (a seed's run)
  replaySeed: "",        // the seed typed into the Replay textbox
  // the map plane (exclusive base) and the overlay (one at a time), from the URL hash for deep links
  plane: /underworld/.test(location.hash) ? "underworld" : "overworld",
  overlay: /caravan/.test(location.hash) ? "caravan"
    : /nation|political/.test(location.hash) ? "nation"
    : /culture/.test(location.hash) ? "culture"
    : /faith|religion/.test(location.hash) ? "faith" : "none",
  polHi: null,           // a nation/culture/faith key to spotlight on the map (legend/search hover)
  camBeforeFocus: null,  // camera snapshot to unwind with Esc after a focus (search / legend jump)
  hoverProv: null,
  dragging: false,       // mid-pan (drawPlots skips textures while panning)
  selected: null,        // journey idx or null
  selectedProv: null,    // province whose full detail fills the sidebar, or null
  curT: 0,               // set to t0 at boot
};
S.curT = t0;

export { J, P, day, t0, t1, fmtDate, fmtInt, MAP, sxSrc, sySrc, VIEW, cam, fitView, baseXr, baseYr, pxr, pyr, px, py, TCOL, K_PLOT, K_TEX, K_MAX, TT, RIVER, SEA, SHORE, FOAM_ART, ICE_ART, BONUS_ICONS, TREES, SEA_BANDS, COUNTRIES, CULTURES, RELIGIONS, provGeo, polOf, isPolitical, latAtScreenY, LY, NB4, terrainRgb, provSrcBox, PLOT_INDEX, MAXD, lerp, heatColor, provPath, cv, ctx, stage, cssVar, journeyPos, lerpField, destSet, clampAxis, clampPan, worldW, BUNDLE };
