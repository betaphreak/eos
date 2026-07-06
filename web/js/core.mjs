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
  S.viewVersion++;
}
// base (unzoomed) screen coords, then the camera
const baseXr = sp => VIEW.dx + (sp - MAP.x0) / (MAP.x1 - MAP.x0) * VIEW.dw;
const baseYr = sp => VIEW.dy + (sp - MAP.y0) / (MAP.y1 - MAP.y0) * VIEW.dh;
const pxr = sp => cam.x + cam.k * baseXr(sp);
const pyr = sp => cam.y + cam.k * baseYr(sp);
const px = lon => pxr(sxSrc(lon));
const py = lat => pyr(sySrc(lat));
const TCOL = BUNDLE.terrainColors || {};
const K_PLOT = 5;                 // camera scale at which plots begin to fade in
const K_TEX = 16;                 // camera scale at which flat tiles give way to real textures
const TT = BUNDLE.terrainTiles;   // ground-texture atlas {src, tile, cols:{TERRAIN_*: column}} or null
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
  if (!p.rings) return p._sbox = null;
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
  return Math.min(0, Math.max(viewDim - size, pos)) - cam.k * base;
}
function clampPan() {
  cam.x = clampAxis(cam.x, VIEW.dx, VIEW.dw, VIEW.w);
  cam.y = clampAxis(cam.y, VIEW.dy, VIEW.dh, VIEW.h);
}

// ---- shared mutable state (was top-level lets; folded into one object so the modules
// can read/write it across the ES-module boundary) ----
export const S = {
  viewVersion: 0,        // bumped on projection/camera change → invalidates cached paths
  showHeat: true,
  showCost: false,
  mode: /caravan/.test(location.hash) ? "caravan" : "world",
  hoverProv: null,
  dragging: false,       // mid-pan (drawPlots skips textures while panning)
  selected: null,        // journey idx or null
  selectedProv: null,    // province whose full detail fills the sidebar, or null
  curT: 0,               // set to t0 at boot
};
S.curT = t0;

export { J, P, day, t0, t1, fmtDate, fmtInt, MAP, sxSrc, sySrc, VIEW, cam, fitView, baseXr, baseYr, pxr, pyr, px, py, TCOL, K_PLOT, K_TEX, TT, LY, NB4, terrainRgb, provSrcBox, PLOT_INDEX, MAXD, lerp, heatColor, provPath, cv, ctx, stage, cssVar, journeyPos, lerpField, destSet, clampAxis, clampPan, BUNDLE };
