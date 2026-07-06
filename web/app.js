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
let viewVersion = 0;   // bumped whenever projection or camera changes, to invalidate cached paths
const cam = { k: 1, x: 0, y: 0 };
function fitView(w, h) {
  const cw = MAP.x1 - MAP.x0, ch = MAP.y1 - MAP.y0;   // crop extent in source px
  const s = Math.min(w / cw, h / ch);                 // contain: whole crop visible at k=1
  VIEW.w = w; VIEW.h = h; VIEW.dw = cw * s; VIEW.dh = ch * s;
  VIEW.dx = (w - VIEW.dw) / 2; VIEW.dy = (h - VIEW.dh) / 2;
  viewVersion++;
}
// base (unzoomed) screen coords, then the camera
const baseXr = sp => VIEW.dx + (sp - MAP.x0) / (MAP.x1 - MAP.x0) * VIEW.dw;
const baseYr = sp => VIEW.dy + (sp - MAP.y0) / (MAP.y1 - MAP.y0) * VIEW.dh;
const pxr = sp => cam.x + cam.k * baseXr(sp);
const pyr = sp => cam.y + cam.k * baseYr(sp);
const px = lon => pxr(sxSrc(lon));
const py = lat => pyr(sySrc(lat));

// the baked dark terrain raster (a real image asset), drawn under everything
const mapImg = new Image();
let mapReady = false;
mapImg.onload = () => { mapReady = true; draw(); };
mapImg.src = MAP.src;

// ---- per-plot terrain zoom layer (a base WorldMap layer; the Caravan View overlays
// its routes/heat on top) ----
// Past K_PLOT the blurry continent raster gives way to crisp per-plot Civ4 terrain:
// each province's canonical plot grid (a slice of assets/plots.pack, range-fetched on
// demand) is rasterised once to an offscreen canvas at 1px/plot, then blitted scaled.
const TCOL = BUNDLE.terrainColors || {};
const K_PLOT = 5;                 // camera scale at which plots begin to fade in
const K_TEX = 16;                 // camera scale at which flat tiles give way to real textures
const TT = BUNDLE.terrainTiles;   // ground-texture atlas {src, tile, cols:{TERRAIN_*: column}} or null
const LY = BUNDLE.terrainLayer || {};   // TERRAIN_* -> Civ4 LayerOrder (higher bleeds over lower)
const NB4 = [[1, 0], [-1, 0], [0, 1], [0, -1]];
let ttImg = null, ttReady = false, ttTiles = null;
if (TT) { ttImg = new Image(); ttImg.onload = () => { extractTiles(); ttReady = true; draw(); }; ttImg.src = TT.src; }
// split the atlas strip into a per-terrain tile canvas, so each can be a repeating
// pattern (continuous ground texture across plots, no per-plot tile seam)
function extractTiles() {
  ttTiles = {};
  for (const terr in TT.cols) {
    const tc = document.createElement("canvas"); tc.width = TT.tile; tc.height = TT.tile;
    tc.getContext("2d").drawImage(ttImg, TT.cols[terr] * TT.tile, 0, TT.tile, TT.tile, 0, 0, TT.tile, TT.tile);
    ttTiles[terr] = tc;
  }
}
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
// lazy-load a province's plot grid: Range-fetch its slice of assets/plots.pack (each
// slice is a standalone gzip member — the province's canonical .json.gz verbatim),
// gunzip it in the browser, then rasterise and redraw. Any failure leaves the province
// as the blurred raster (graceful degradation; e.g. file:// blocks fetch entirely).
async function loadPlots(p) {
  if (p._loading || p._plots) return;
  const slice = PLOT_INDEX[p.id];
  if (!slice) return;                            // no plots for this province
  p._loading = true;
  const [off, len] = slice;
  try {
    const res = await fetch("assets/plots.pack", { headers: { Range: `bytes=${off}-${off + len - 1}` } });
    let buf = await res.arrayBuffer();
    if (res.status === 200) buf = buf.slice(off, off + len);   // server ignored Range → slice ourselves
    const stream = new Response(buf).body.pipeThrough(new DecompressionStream("gzip"));
    const arr = JSON.parse(await new Response(stream).text());
    p._loading = false;
    if (arr) { p._plots = arr; draw(); if (selectedProv === p) renderRail(); }   // fill the detail panel too
  } catch (e) {
    p._loading = false; p.hasPlots = false;
  }
}
// rasterise a province's plots to a 1px/plot offscreen canvas: terrain colour, relief
// shading (hill lighter, peak toward rock-grey), a light feature tint, and river blend
function buildPlotCanvas(p, plots) {
  let x0 = 1e9, y0 = 1e9, x1 = -1e9, y1 = -1e9;
  for (const q of plots) { if (q.x < x0) x0 = q.x; if (q.x > x1) x1 = q.x; if (q.y < y0) y0 = q.y; if (q.y > y1) y1 = q.y; }
  const w = x1 - x0 + 1, h = y1 - y0 + 1;
  const oc = document.createElement("canvas"); oc.width = w; oc.height = h;
  const octx = oc.getContext("2d"), im = octx.createImageData(w, h), d = im.data;
  for (const q of plots) {
    const c = terrainRgb(q.terrain); let r = c[0], g = c[1], b = c[2];
    const f = q.feature;
    if (f) {
      if (/FOREST|JUNGLE|WOOD/.test(f)) { r = r * 0.7 | 0; g = g * 0.82 + 16 | 0; b = b * 0.6 | 0; }
      else if (/SWAMP|MARSH|BOG/.test(f)) { r = r * 0.82 | 0; g = g * 0.86 | 0; b = b * 0.82 | 0; }
    }
    if (q.plotType === "HILL") { r = Math.min(255, r * 1.14 + 8) | 0; g = Math.min(255, g * 1.14 + 8) | 0; b = Math.min(255, b * 1.14 + 8) | 0; }
    else if (q.plotType === "PEAK") { r = (r + 150) / 2 | 0; g = (g + 152) / 2 | 0; b = (b + 158) / 2 | 0; }
    if (q.river) { r = r * 0.45 + 33 | 0; g = g * 0.45 + 61 | 0; b = b * 0.45 + 91 | 0; }
    const o = ((q.y - y0) * w + (q.x - x0)) * 4;
    d[o] = r; d[o + 1] = g; d[o + 2] = b; d[o + 3] = 255;
  }
  octx.putImageData(im, 0, 0);
  p._pcanvas = oc; p._pbox = { x0, y0, w, h };
}

// ---- movement-cost overlay: per-plot elevation traversal difficulty ----
// Mirrors the engine's elevation-aware corridor cost (ProvincePlotPool.slopeFactor): the
// flat plot cost is scaled by Tobler's hiking function exp(3.5·(|slope+0.05|−0.05)), with
// slope = Δelevation·0.06, clamped to [~0.84, 8]. On this static map we colour each plot by
// the STEEPEST step touching it — the max |Δelevation| to a 4-neighbour, taken as a climb —
// i.e. the difficulty a caravan pays to cross it. Flat ground (factor ≈ 1) stays clear;
// ridges and steep faces glow amber→red, showing where corridors slow and bend. Constants
// track ProvincePlotPool exactly, so the overlay is faithful to the routing it explains.
const COST_K = 0.06, COST_TK = 3.5, COST_OFF = 0.05, COST_CAP = 8;
function costFactor(maxDelta) {
  const s = maxDelta * COST_K;
  return Math.min(COST_CAP, Math.exp(COST_TK * (Math.abs(s + COST_OFF) - COST_OFF)));
}
// green → yellow → orange → red ramp (matches the #costKey legend gradient), keyed on a log
// scale over the factor's [1, cap] range so the mid climbs are legible, not crushed near red.
const COST_STOPS = [[0, 90, 190, 70], [0.35, 230, 210, 40], [0.65, 240, 140, 30], [1, 214, 40, 42]];
function costColor(f) {
  const t = Math.min(1, Math.max(0, Math.log(f) / Math.log(COST_CAP)));
  let i = 0; while (i < COST_STOPS.length - 2 && t > COST_STOPS[i + 1][0]) i++;
  const a = COST_STOPS[i], b = COST_STOPS[i + 1], u = (t - a[0]) / (b[0] - a[0]);
  return [lerp(a[1], b[1], u) | 0, lerp(a[2], b[2], u) | 0, lerp(a[3], b[3], u) | 0, t];
}
// rasterise a province's plots to a 1px/plot cost heat offscreen (built once, blitted scaled
// and smoothed), transparent where the ground is effectively flat so the terrain reads through.
function buildCostCanvas(p, plots) {
  let x0 = 1e9, y0 = 1e9, x1 = -1e9, y1 = -1e9;
  for (const q of plots) { if (q.x < x0) x0 = q.x; if (q.x > x1) x1 = q.x; if (q.y < y0) y0 = q.y; if (q.y > y1) y1 = q.y; }
  const w = x1 - x0 + 1, h = y1 - y0 + 1;
  const grid = new Map();
  for (const q of plots) grid.set(q.x * 1e5 + q.y, q);
  const oc = document.createElement("canvas"); oc.width = w; oc.height = h;
  const octx = oc.getContext("2d"), im = octx.createImageData(w, h), d = im.data;
  for (const q of plots) {
    const e = q.elevation | 0; let md = 0;
    for (const nb of NB4) { const n = grid.get((q.x + nb[0]) * 1e5 + (q.y + nb[1])); if (n) { const dd = Math.abs((n.elevation | 0) - e); if (dd > md) md = dd; } }
    const o = ((q.y - y0) * w + (q.x - x0)) * 4;
    const f = costFactor(md);
    if (f < 1.03) { d[o + 3] = 0; continue; }               // effectively flat → clear
    const c = costColor(f);
    d[o] = c[0]; d[o + 1] = c[1]; d[o + 2] = c[2]; d[o + 3] = (255 * (0.12 + 0.6 * c[3])) | 0;
  }
  octx.putImageData(im, 0, 0);
  p._mcanvas = oc; p._mbox = { x0, y0, w, h };
}
// blit the cost overlay for the provinces in view (same cull/scale as drawPlots), when the
// toggle is on and we are zoomed into the plot layer. Drawn over the terrain, under outlines.
function drawCostOverlay() {
  if (!showCost || cam.k < K_PLOT) return;
  const a = Math.min(1, (cam.k - K_PLOT) / 1.5);
  const smooth = ctx.imageSmoothingEnabled;
  ctx.globalAlpha = a; ctx.imageSmoothingEnabled = true;
  for (const p of P) {
    if (!p.hasPlots || !p._plots) continue;                 // drawPlots requests the load
    const bb = provSrcBox(p); if (!bb) continue;
    const sx0 = pxr(bb.x0), sy0 = pyr(bb.y0), sx1 = pxr(bb.x1), sy1 = pyr(bb.y1);
    if (sx1 < 0 || sy1 < 0 || sx0 > VIEW.w || sy0 > VIEW.h) continue;
    if (!p._mcanvas) buildCostCanvas(p, p._plots);
    const b = p._mbox, dX = pxr(b.x0), dY = pyr(b.y0);
    ctx.drawImage(p._mcanvas, dX, dY, pxr(b.x0 + b.w) - dX, pyr(b.y0 + b.h) - dY);
  }
  ctx.globalAlpha = 1; ctx.imageSmoothingEnabled = smooth;
}
// draw the plot layer for the provinces in view, fading in just past K_PLOT. Below
// K_TEX each province blits its flat-colour 1px/plot offscreen (cheap overview); past
// K_TEX (and not mid-pan) it draws real ground-texture tiles per plot.
function drawPlots() {
  if (cam.k < K_PLOT) return;
  const textured = cam.k >= K_TEX && ttReady && !dragging;   // flat tiles while panning (cheap)
  const a = Math.min(1, (cam.k - K_PLOT) / 1.5);
  const smooth = ctx.imageSmoothingEnabled;
  ctx.globalAlpha = a;
  for (const p of P) {
    if (!p.hasPlots) continue;
    const bb = provSrcBox(p);
    let sx0, sy0, sx1, sy1;
    if (bb) { sx0 = pxr(bb.x0); sy0 = pyr(bb.y0); sx1 = pxr(bb.x1); sy1 = pyr(bb.y1); }
    else { const x = px(p.lon), y = py(p.lat); sx0 = x - 20; sy0 = y - 20; sx1 = x + 20; sy1 = y + 20; }
    if (sx1 < 0 || sy1 < 0 || sx0 > VIEW.w || sy0 > VIEW.h) continue;   // cull to viewport
    if (!p._plots) { loadPlots(p); continue; }
    if (textured) {
      if (!p._tcanvas) buildPlotTexCanvas(p);                 // textured offscreen, built once
      ctx.imageSmoothingEnabled = true;
      const b = p._tbox, dX = pxr(b.x0), dY = pyr(b.y0);
      ctx.drawImage(p._tcanvas, dX, dY, pxr(b.x0 + b.w) - dX, pyr(b.y0 + b.h) - dY);
      continue;
    }
    if (!p._pcanvas) buildPlotCanvas(p, p._plots);            // flat-colour offscreen, built once
    ctx.imageSmoothingEnabled = false;
    const b = p._pbox, dX = pxr(b.x0), dY = pyr(b.y0);
    ctx.drawImage(p._pcanvas, dX, dY, pxr(b.x0 + b.w) - dX, pyr(b.y0 + b.h) - dY);
  }
  ctx.globalAlpha = 1; ctx.imageSmoothingEnabled = smooth;
}
// rasterise a province's plots to a textured offscreen — each plot drawn as its Civ4
// ground-texture tile (from the atlas) at TPP px, plus relief/river overlays — built
// once and blitted scaled (so hover/pan redraws stay a single drawImage per province).
// TPP drops for very large provinces to bound the offscreen size.
function buildPlotTexCanvas(p) {
  let x0 = 1e9, y0 = 1e9, x1 = -1e9, y1 = -1e9;
  for (const q of p._plots) { if (q.x < x0) x0 = q.x; if (q.x > x1) x1 = q.x; if (q.y < y0) y0 = q.y; if (q.y > y1) y1 = q.y; }
  const w = x1 - x0 + 1, h = y1 - y0 + 1;
  let tpp = 32; while (tpp > 4 && Math.max(w, h) * tpp > 2600) tpp = Math.max(4, tpp - 4);
  const oc = document.createElement("canvas"); oc.width = w * tpp; oc.height = h * tpp;
  const o = oc.getContext("2d"); o.imageSmoothingEnabled = true;
  const grid = new Map();
  for (const q of p._plots) grid.set(q.x * 1e5 + q.y, q);
  // 1) base terrain as continuous repeating patterns (no per-plot tile seam)
  const pat = {};
  for (const q of p._plots) {
    const cx = (q.x - x0) * tpp, cy = (q.y - y0) * tpp;
    let pp = pat[q.terrain];
    if (pp === undefined) { const tc = ttTiles && ttTiles[q.terrain]; pp = pat[q.terrain] = tc ? o.createPattern(tc, "repeat") : null; }
    if (pp) { o.fillStyle = pp; o.fillRect(cx, cy, tpp, tpp); }
    else { const g = terrainRgb(q.terrain); o.fillStyle = `rgb(${g[0]},${g[1]},${g[2]})`; o.fillRect(cx, cy, tpp, tpp); }
  }
  // 2) 16-way edge blend: a higher-LayerOrder neighbour feathers over this plot across
  // the shared edge (Civ4 §6.1, adapted to the raster — a colour bleed, not a tile swap)
  const f = tpp * 0.5;
  for (const q of p._plots) {
    const ql = LY[q.terrain] || 0, cx = (q.x - x0) * tpp, cy = (q.y - y0) * tpp;
    for (const d of NB4) {
      const n = grid.get((q.x + d[0]) * 1e5 + (q.y + d[1]));
      if (!n || n.terrain === q.terrain || (LY[n.terrain] || 0) <= ql) continue;
      const g = terrainRgb(n.terrain);
      const c0 = `rgba(${g[0]},${g[1]},${g[2]},.8)`, c1 = `rgba(${g[0]},${g[1]},${g[2]},0)`;
      let gr, rx, ry, rw, rh;
      if (d[0] === 1) { gr = o.createLinearGradient(cx + tpp, 0, cx + tpp - f, 0); rx = cx + tpp - f; ry = cy; rw = f; rh = tpp; }
      else if (d[0] === -1) { gr = o.createLinearGradient(cx, 0, cx + f, 0); rx = cx; ry = cy; rw = f; rh = tpp; }
      else if (d[1] === 1) { gr = o.createLinearGradient(0, cy + tpp, 0, cy + tpp - f); rx = cx; ry = cy + tpp - f; rw = tpp; rh = f; }
      else { gr = o.createLinearGradient(0, cy, 0, cy + f); rx = cx; ry = cy; rw = tpp; rh = f; }
      gr.addColorStop(0, c0); gr.addColorStop(1, c1);
      o.fillStyle = gr; o.fillRect(rx, ry, rw, rh);
    }
  }
  // 3) hillshade from real heightmap elevation (surface normal vs a NW light: slopes
  // facing the light lighten, those in shadow darken), snow on the highest ground, then
  // feature sprites and rivers. EXAG exaggerates the gentle continental slopes.
  const EXAG = 4, STR = 1.5, SX = -0.42, SY = -0.42, SZ = 0.8;   // NW sun (SX/SY/SZ ≠ LY layer map)
  for (const q of p._plots) {
    const cx = (q.x - x0) * tpp, cy = (q.y - y0) * tpp, e = q.elevation | 0;
    const gW = grid.get((q.x - 1) * 1e5 + q.y), gE = grid.get((q.x + 1) * 1e5 + q.y);
    const gN = grid.get(q.x * 1e5 + (q.y - 1)), gS = grid.get(q.x * 1e5 + (q.y + 1));
    const nx = ((gW ? gW.elevation : e) - (gE ? gE.elevation : e)) * EXAG;
    const ny = ((gN ? gN.elevation : e) - (gS ? gS.elevation : e)) * EXAG;
    const k = ((nx * SX + ny * SY + SZ) / Math.hypot(nx, ny, 1) - SZ) * STR;   // 0 on flat ground
    if (k > 0.01) { o.fillStyle = `rgba(255,255,248,${Math.min(0.5, k).toFixed(3)})`; o.fillRect(cx, cy, tpp, tpp); }
    else if (k < -0.01) { o.fillStyle = `rgba(12,16,28,${Math.min(0.5, -k).toFixed(3)})`; o.fillRect(cx, cy, tpp, tpp); }
    if (e >= 165) { o.fillStyle = `rgba(232,238,247,${Math.min(0.6, (e - 165) / 50).toFixed(3)})`; o.fillRect(cx, cy, tpp, tpp); }
    if (q.feature) featureSprite(o, cx, cy, tpp, q.feature, q.x, q.y);
    if (q.river) { o.fillStyle = "rgba(74,124,170,.55)"; o.fillRect(cx, cy, tpp, tpp); }
  }
  p._tcanvas = oc; p._tbox = { x0, y0, w, h };
}
// small deterministic RNG seeded by a plot's coords, so feature sprites are stable
function mkRng(seed) { let s = seed >>> 0 || 1; return () => { s = (s * 1664525 + 1013904223) >>> 0; return s / 4294967296; }; }
// draw a procedural 2D sprite for a plot's Civ4 feature into cell (cx,cy) of size s.
// (The real feature art is 3D .nif — pre-rendering it to sprites is a later toolchain;
// these keyed vector marks read cleanly on the 2D map and cover the features in play.)
function featureSprite(o, cx, cy, s, feature, sx, sy) {
  const rng = mkRng((sx * 73856093) ^ (sy * 19349663));
  const trees = (col, shadow, count, rad) => {
    for (let i = 0; i < count; i++) {
      const px = cx + s * (0.18 + 0.64 * rng()), py = cy + s * (0.24 + 0.58 * rng()), r = s * rad * (0.8 + 0.5 * rng());
      o.fillStyle = shadow; o.beginPath(); o.arc(px + r * 0.28, py + r * 0.5, r, 0, 7); o.fill();
      o.fillStyle = col; o.beginPath(); o.arc(px, py, r, 0, 7); o.fill();
    }
  };
  if (/JUNGLE|RAINFOREST/.test(feature)) trees("#274a18", "rgba(8,16,6,.38)", 3 + (rng() * 2 | 0), 0.18);
  else if (/FOREST|WOOD|MANGROVE|TAIGA/.test(feature)) trees("#345320", "rgba(8,16,6,.32)", 2 + (rng() * 2 | 0), 0.155);
  else if (/SWAMP|BOG|MARSH|WETLAND/.test(feature)) {
    o.strokeStyle = "#66743c"; o.lineWidth = Math.max(1, s * 0.045); o.lineCap = "round";
    for (let i = 0; i < 3; i++) { const px = cx + s * (0.24 + 0.52 * rng()), py = cy + s * 0.72; o.beginPath(); o.moveTo(px, py); o.lineTo(px + s * 0.05 * (rng() - 0.5), py - s * (0.22 + 0.16 * rng())); o.stroke(); }
  }
  else if (/CACTUS|KAKTUS/.test(feature)) {
    o.strokeStyle = "#5a7a44"; o.lineWidth = Math.max(1.2, s * 0.075); o.lineCap = "round"; o.lineJoin = "round";
    const px = cx + s * (0.34 + 0.3 * rng()), py = cy + s * 0.76, hgt = s * (0.34 + 0.16 * rng());
    o.beginPath(); o.moveTo(px, py); o.lineTo(px, py - hgt);
    o.moveTo(px, py - hgt * 0.55); o.lineTo(px + s * 0.13, py - hgt * 0.55); o.lineTo(px + s * 0.13, py - hgt * 0.82); o.stroke();
  }
  else if (/OASIS/.test(feature)) { o.fillStyle = "#2f6f8a"; o.beginPath(); o.arc(cx + s * 0.52, cy + s * 0.56, s * 0.2, 0, 7); o.fill(); trees("#2e6a2a", "rgba(0,0,0,.3)", 1, 0.14); }
  else if (/SAVANNA/.test(feature)) {
    if (rng() > 0.45) { o.fillStyle = "#556a30"; o.beginPath(); o.arc(cx + s * 0.5, cy + s * 0.4, s * 0.17, 0, 7); o.fill(); o.strokeStyle = "#3a3020"; o.lineWidth = Math.max(1, s * 0.035); o.beginPath(); o.moveTo(cx + s * 0.5, cy + s * 0.5); o.lineTo(cx + s * 0.5, cy + s * 0.74); o.stroke(); }
  }
  // FLOOD_PLAINS: a ground quality, not foliage — left as terrain
}

// ---- province polygons: choropleth heat by caravan-days, cached per view ----
let showHeat = true;
let showCost = false;          // terrain movement-cost overlay (elevation difficulty)
// WorldMap is the base view; Caravan is a toggle-able overlay mode (routes/heat/timeline)
let mode = /caravan/.test(location.hash) ? "caravan" : "world";
const MAXD = BUNDLE.meta.maxDays;
const lerp = (a,b,t) => a + (b-a)*t;
function heatColor(days) {                      // amber (low) -> red (high), over dark terrain
  const t = Math.pow(days/MAXD, 0.5);
  return `rgba(${lerp(236,228,t)|0},${lerp(178,96,t)|0},${lerp(96,56,t)|0},${(0.12+0.6*t).toFixed(3)})`;
}
function provPath(p) {                           // Path2D of a province's rings, rebuilt on view change
  if (p._pv === viewVersion) return p._path;
  const path = new Path2D();
  for (const ring of p.rings) {
    ring.forEach((pt,i)=>{ const x=pxr(pt[0]), y=pyr(pt[1]); i?path.lineTo(x,y):path.moveTo(x,y); });
    path.closePath();
  }
  p._path = path; p._pv = viewVersion; return path;
}

// ---- canvas ----
const cv = document.getElementById("map"), ctx = cv.getContext("2d");
const stage = document.getElementById("stage");
function resize() {
  const r = stage.getBoundingClientRect(), dpr = Math.min(window.devicePixelRatio||1, 2);
  cv.width = r.width*dpr; cv.height = r.height*dpr; VIEW.dpr = dpr;
  fitView(r.width, r.height); clampPan(); draw();
}
const cssVar = n => getComputedStyle(document.documentElement).getPropertyValue(n).trim();

let hoverProv = null;
let selected = null;          // journey idx or null
let selectedProv = null;      // the province whose full detail fills the sidebar, or null
let curT = t0;

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

// destinations, excluded from the province-name pass (they carry their own journey labels)
const destSet = new Set(J.map(j=>j.destId));

function draw() {
  const w=VIEW.w, h=VIEW.h, dpr=VIEW.dpr;
  ctx.setTransform(dpr,0,0,dpr,0,0);
  ctx.clearRect(0,0,w,h);

  // dark void + the baked terrain raster, aligned to the crop rectangle under the camera
  ctx.fillStyle="#090d14"; ctx.fillRect(0,0,w,h);
  if (mapReady) {
    ctx.imageSmoothingEnabled=true;
    ctx.drawImage(mapImg, 0,0,MAP.dw,MAP.dh,
      cam.x + cam.k*VIEW.dx, cam.y + cam.k*VIEW.dy, cam.k*VIEW.dw, cam.k*VIEW.dh);
  }
  drawPlots();   // crisp per-plot Civ4 terrain over the blurred raster when zoomed in
  drawCostOverlay();   // elevation movement-cost heat over the terrain, when toggled on

  // choropleth: a full caravan-days overview while zoomed out, but once the terrain
  // plots/textures show (cam.k >= K_PLOT) only the hovered province is shaded — the
  // static heat would otherwise hide the real terrain colours under it.
  if (showHeat && mode === "caravan") {
    if (cam.k < K_PLOT) { for (const p of P) if (p.rings && p.days) { ctx.fillStyle=heatColor(p.days); ctx.fill(provPath(p)); } }
    else if (hoverProv && hoverProv.rings && hoverProv.days) { ctx.fillStyle=heatColor(hoverProv.days); ctx.fill(provPath(hoverProv)); }
  }
  // province outlines
  ctx.strokeStyle="rgba(190,205,230,.18)"; ctx.lineWidth=0.8;
  for (const p of P) if (p.rings) ctx.stroke(provPath(p));
  // hovered province highlight (polygon if we have one, else a centroid ring for seas)
  if (hoverProv && hoverProv.rings) {
    const hp = provPath(hoverProv);
    ctx.fillStyle="rgba(231,236,244,.12)"; ctx.fill(hp);
    ctx.strokeStyle="#eef2f8"; ctx.lineWidth=1.6; ctx.stroke(hp);
  } else if (hoverProv) {
    ctx.beginPath(); ctx.arc(px(hoverProv.lon), py(hoverProv.lat), 6, 0, 7);
    ctx.strokeStyle="#eef2f8"; ctx.lineWidth=1.4; ctx.stroke();
  }
  // selected province: a persistent accent outline while its detail fills the sidebar
  if (selectedProv && selectedProv.rings) {
    const sp = provPath(selectedProv);
    ctx.fillStyle="rgba(232,183,106,.12)"; ctx.fill(sp);
    ctx.strokeStyle=cssVar("--accent")||"#e8b76a"; ctx.lineWidth=2.2; ctx.stroke(sp);
  } else if (selectedProv) {
    ctx.beginPath(); ctx.arc(px(selectedProv.lon), py(selectedProv.lat), 7, 0, 7);
    ctx.strokeStyle=cssVar("--accent")||"#e8b76a"; ctx.lineWidth=2; ctx.stroke();
  }

  // caravan overlay (routes, origin, moving bands) — only in Caravan mode
  if (mode === "caravan") {
  // routes (dim when another is selected), with a soft shadow to read over terrain
  J.forEach(j => {
    const dim = selected!==null && selected!==j.idx;
    ctx.beginPath();
    j.keys.forEach((k,i)=>{ const x=px(k.lon), y=py(k.lat); i?ctx.lineTo(x,y):ctx.moveTo(x,y); });
    ctx.strokeStyle=j.color; ctx.globalAlpha=dim?.14:(selected===j.idx?.98:.72);
    ctx.lineWidth=selected===j.idx?2.8:1.8; ctx.lineJoin="round"; ctx.lineCap="round";
    ctx.shadowColor="rgba(4,7,12,.55)"; ctx.shadowBlur=3; ctx.stroke(); ctx.shadowBlur=0;
    ctx.globalAlpha=1;
  });

  // origin star
  drawStar(px(BUNDLE.meta.origin.lon), py(BUNDLE.meta.origin.lat), 7, cssVar("--accent"));

  // destinations + moving caravans
  J.forEach(j => {
    const dim = selected!==null && selected!==j.idx;
    const dest = j.keys[j.keys.length-1];
    const dx=px(dest.lon), dy=py(dest.lat);
    ctx.beginPath(); ctx.arc(dx,dy,3.6,0,7); ctx.fillStyle=j.color; ctx.globalAlpha=dim?.28:1; ctx.fill();
    ctx.lineWidth=1.4; ctx.strokeStyle="rgba(9,13,20,.9)"; ctx.stroke(); ctx.globalAlpha=1;
    const pos = journeyPos(j, curT);
    if (pos.started) {
      const x=px(pos.lon), y=py(pos.lat);
      const cargo = lerpField(j, curT, "cargo");
      ctx.globalAlpha=dim?.3:1;
      const hr = 6 + (cargo/490)*7;                       // cargo halo
      ctx.beginPath(); ctx.arc(x,y,hr,0,7); ctx.strokeStyle=j.color; ctx.globalAlpha=dim?.12:.32; ctx.lineWidth=2.5; ctx.stroke();
      ctx.globalAlpha=dim?.3:1;
      ctx.beginPath(); ctx.arc(x,y,4.6,0,7); ctx.fillStyle=j.color; ctx.fill();
      ctx.beginPath(); ctx.arc(x,y,4.6,0,7); ctx.strokeStyle="#0b0f16"; ctx.lineWidth=1.6; ctx.stroke();
      ctx.globalAlpha=1;
    }
  });
  } // end caravan overlay

  drawLabels();
}

// place province name labels over the map with a halo, skipping any that would
// overflow the stage or collide with one already placed (priority: origin first,
// then destinations, then the largest context provinces).
function drawLabels() {
  drawGeoLabels();          // zoom-banded continent/super-region/region tiers, behind the rest
  const placed = [];
  const fits = b => {
    if (b.x < 3 || b.y < 3 || b.x+b.w > VIEW.w-3 || b.y+b.h > VIEW.h-3) return false;
    return !placed.some(q => b.x < q.x+q.w && b.x+b.w > q.x && b.y < q.y+q.h && b.y+b.h > q.y);
  };
  const label = (name, ax, ay, o) => {
    ctx.font = o.font;
    const tw = ctx.measureText(name).width, gap = o.dot ? 9 : 5;
    for (const side of [1, -1]) {
      const bx = side>0 ? ax+gap : ax-gap-tw;
      const box = { x: bx, y: ay-o.size/2-1, w: tw, h: o.size+2 };
      if (!fits(box)) continue;
      placed.push(box);
      if (o.dot) {
        ctx.beginPath(); ctx.arc(ax,ay,o.dotR||3,0,7); ctx.fillStyle=o.dot; ctx.fill();
        ctx.lineWidth=1.2; ctx.strokeStyle="rgba(9,13,20,.9)"; ctx.stroke();
      }
      ctx.textAlign="left"; ctx.textBaseline="middle";
      ctx.lineJoin="round"; ctx.lineWidth=3.4; ctx.strokeStyle="rgba(8,12,19,.92)";
      ctx.strokeText(name, bx, ay);
      ctx.fillStyle=o.color; ctx.fillText(name, bx, ay);
      return;
    }
  };
  const F1="600 12px system-ui,'Segoe UI',sans-serif", F2="500 10.5px system-ui,'Segoe UI',sans-serif";
  if (mode === "caravan") {
    label(BUNDLE.meta.origin.name, px(BUNDLE.meta.origin.lon), py(BUNDLE.meta.origin.lat),
      { font:F1, size:12, color:cssVar("--accent") });
    J.forEach(j => {
      if (selected!==null && selected!==j.idx) return;
      const d = j.keys[j.keys.length-1];
      label(j.dest, px(d.lon), py(d.lat), { font:F1, size:12, color:"#eaf0f8", dot:j.color, dotR:3.6 });
    });
  }
  // province names fade in only once zoomed in (below that the geographic tiers own the
  // map): label the provinces actually on screen, largest first and collision-culled, so
  // names resolve wherever you zoom rather than only for the globally-biggest few
  if (selected===null) {
    const pa = Math.min(1, Math.max(0, (cam.k - 6.5) / 2));   // fade in over cam.k 6.5 -> 8.5
    if (pa > 0.01) {
      const inView = [];
      for (const p of P) {
        if (p.type!=="LAND") continue;
        // origin/destinations carry their own journey labels in Caravan mode; in World mode
        // they are ordinary provinces and get named like the rest
        if (mode==="caravan" && (p.id===BUNDLE.meta.origin.id || destSet.has(p.id))) continue;
        const x = px(p.lon), y = py(p.lat);
        if (x < -40 || y < -20 || x > VIEW.w+40 || y > VIEW.h+20) continue;   // cull to viewport
        inView.push({ p, x, y });
      }
      inView.sort((a,b)=> b.p.plots - a.p.plots);
      ctx.save(); ctx.globalAlpha = pa;
      for (let i=0; i<inView.length && i<90; i++)
        label(inView[i].p.name, inView[i].x, inView[i].y, { font:F2, size:10.5, color:"#9fb0c8" });
      ctx.restore();
    }
  }
}

// ---- zoom-banded geographic tier labels (continent -> super-region -> region) ----
// Each tier is visible across a cam.k band and cross-fades at the seams, so zooming out
// coarsens the labelling (province -> region -> super-region -> continent) and zooming in
// refines it. Anchors + names come from BUNDLE.geo (built by build.mjs). k = [fadeIn0,
// full, holdTo, fadeOut1].
const GEO_TIERS = [
  { arr:"continents",   k:[0.9,1.0,1.5,2.3], size:16, weight:"800", color:"#e6edf7", halo:4.2, track:"3px", upper:true },
  { arr:"superRegions", k:[1.7,2.2,3.4,4.7], size:13, weight:"700", color:"#cdd9ea", halo:3.7, track:"1.5px", upper:true },
  { arr:"regions",      k:[3.6,4.7,7.0,9.5], size:11, weight:"600", color:"#aebcd2", halo:3.3, track:"0px", upper:false },
];
// trapezoidal visibility envelope: 0 outside [k0,k3], ramps up over [k0,k1], holds to k2, down to k3
function tierAlpha(k, [k0,k1,k2,k3]) {
  if (k <= k0 || k >= k3) return 0;
  if (k < k1) return (k - k0) / (k1 - k0);
  if (k <= k2) return 1;
  return (k3 - k) / (k3 - k2);
}
function drawGeoLabels() {
  const G = BUNDLE.geo; if (!G) return;
  for (const t of GEO_TIERS) {
    const a = tierAlpha(cam.k, t.k);
    if (a <= 0.01) continue;
    const items = G[t.arr] || [];
    const placed = [];               // per-tier collision, so tiers can cross-fade over each other
    ctx.save();
    ctx.globalAlpha = a;
    ctx.font = `${t.weight} ${t.size}px system-ui,'Segoe UI',sans-serif`;
    ctx.letterSpacing = t.track;
    ctx.textAlign = "center"; ctx.textBaseline = "middle";
    ctx.lineJoin = "round";
    for (const g of items) {         // pre-sorted largest-first = priority
      const name = t.upper ? g.name.toUpperCase() : g.name;
      const cx = px(g.lon), cy = py(g.lat), tw = ctx.measureText(name).width;
      const box = { x: cx - tw/2, y: cy - t.size/2 - 1, w: tw, h: t.size + 2 };
      if (box.x < 3 || box.y < 3 || box.x+box.w > VIEW.w-3 || box.y+box.h > VIEW.h-3) continue;
      if (placed.some(q => box.x < q.x+q.w && box.x+box.w > q.x && box.y < q.y+q.h && box.y+box.h > q.y)) continue;
      placed.push(box);
      ctx.lineWidth = t.halo; ctx.strokeStyle = "rgba(8,12,19,.9)"; ctx.strokeText(name, cx, cy);
      ctx.fillStyle = t.color; ctx.fillText(name, cx, cy);
    }
    ctx.restore();
  }
  ctx.letterSpacing = "0px";
}
function drawStar(cx,cy,r,color){
  ctx.beginPath();
  for(let i=0;i<10;i++){ const a=Math.PI/5*i - Math.PI/2, rr=i%2?r*0.44:r; const x=cx+Math.cos(a)*rr, y=cy+Math.sin(a)*rr; i?ctx.lineTo(x,y):ctx.moveTo(x,y); }
  ctx.closePath(); ctx.fillStyle=color; ctx.fill();
  ctx.strokeStyle=cssVar("--panel-2"); ctx.lineWidth=1.2; ctx.stroke();
}

// ---- pan & zoom ----
// keep the map covering the viewport (or centred on an axis where it is smaller than
// the viewport); cam.x/cam.y are absolute screen translations added after the k-scale.
function clampAxis(camv, base, dim, viewDim) {
  const size = cam.k * dim, pos = camv + cam.k * base;
  if (size <= viewDim) return (viewDim - size) / 2 - cam.k * base;   // centre, no pan on this axis
  return Math.min(0, Math.max(viewDim - size, pos)) - cam.k * base;
}
function clampPan() {
  cam.x = clampAxis(cam.x, VIEW.dx, VIEW.dw, VIEW.w);
  cam.y = clampAxis(cam.y, VIEW.dy, VIEW.dh, VIEW.h);
}
function zoomAt(mx, my, factor) {
  const k2 = Math.max(1, Math.min(64, cam.k * factor));   // deep enough to read individual plots
  if (k2 === cam.k) return;
  const f = k2 / cam.k;
  cam.x = mx - f * (mx - cam.x);     // keep the point under (mx,my) fixed
  cam.y = my - f * (my - cam.y);
  cam.k = k2;
  clampPan(); viewVersion++; draw();
}
stage.addEventListener("wheel", e => {
  e.preventDefault();
  const r = stage.getBoundingClientRect();
  zoomAt(e.clientX - r.left, e.clientY - r.top, Math.exp(-e.deltaY * 0.0016));
}, { passive: false });

let dragging = false, lastX = 0, lastY = 0, panMoved = false;
stage.addEventListener("mousedown", e => {
  if (e.button !== 0) return;
  dragging = true; panMoved = false; lastX = e.clientX; lastY = e.clientY;
  stage.classList.add("grabbing");
});
window.addEventListener("mousemove", e => {
  if (!dragging) return;
  const dx = e.clientX - lastX, dy = e.clientY - lastY;
  if (Math.abs(dx) + Math.abs(dy) > 2) panMoved = true;
  cam.x += dx; cam.y += dy; lastX = e.clientX; lastY = e.clientY;
  clampPan(); viewVersion++; draw();
});
window.addEventListener("mouseup", () => { if (dragging) { dragging = false; stage.classList.remove("grabbing"); draw(); } });

document.getElementById("zoomIn").onclick = () => zoomAt(VIEW.w/2, VIEW.h/2, 1.5);
document.getElementById("zoomOut").onclick = () => zoomAt(VIEW.w/2, VIEW.h/2, 1/1.5);
document.getElementById("zoomReset").onclick = () => { cam.k = 1; cam.x = 0; cam.y = 0; clampPan(); viewVersion++; draw(); };

// ---- timeline ----
const scrub=document.getElementById("scrub"), dNow=document.getElementById("dNow");
document.getElementById("dLo").textContent = fmtDate(t0);
document.getElementById("dHi").textContent = fmtDate(t1);
function setT(t, fromInput){
  curT = Math.max(t0, Math.min(t1, t));
  dNow.textContent = fmtDate(curT);
  if(!fromInput) scrub.value = Math.round((curT-t0)/(t1-t0)*1000);
  draw();
  if (selected!==null) updateDetailLive();
}
scrub.addEventListener("input", ()=> setT(t0 + (+scrub.value/1000)*(t1-t0), true));

let playing=false, speed=2, raf=0, lastTs=0;
const DAYS_PER_SEC = {1:120, 2:340, 3:900};
const playBtn=document.getElementById("playBtn"), playIcon=document.getElementById("playIcon");
function tick(ts){
  if(!playing) return;
  if(lastTs){ const dt=(ts-lastTs)/1000; setT(curT + dt*DAYS_PER_SEC[speed]); if(curT>=t1){ pause(); } }
  lastTs=ts; raf=requestAnimationFrame(tick);
}
function play(){ if(curT>=t1) setT(t0); playing=true; lastTs=0; playIcon.innerHTML='<path d="M6 5h4v14H6zM14 5h4v14h-4z"/>'; playBtn.setAttribute("aria-label","Pause"); raf=requestAnimationFrame(tick); }
function pause(){ playing=false; cancelAnimationFrame(raf); playIcon.innerHTML='<path d="M8 5v14l11-7z"/>'; playBtn.setAttribute("aria-label","Play"); }
playBtn.addEventListener("click", ()=> playing?pause():play());
const reduce = matchMedia("(prefers-reduced-motion: reduce)").matches;

const speedBox=document.getElementById("speed");
[[1,"0.5×"],[2,"1×"],[3,"2×"]].forEach(([v,lab])=>{
  const b=document.createElement("button"); b.textContent=lab; b.setAttribute("aria-pressed", v===speed);
  b.onclick=()=>{ speed=v; [...speedBox.children].forEach(c=>c.setAttribute("aria-pressed", c===b)); };
  speedBox.appendChild(b);
});

// ---- legend ----
const legend=document.getElementById("legend");
legend.innerHTML = '<div class="lg-h">Caravans → destination</div>';
J.forEach(j=>{
  const b=document.createElement("button"); b.className="legrow"; b.setAttribute("aria-pressed","false");
  b.innerHTML = `<span class="dot" style="background:${j.color}"></span><span>${j.dest}</span><span class="km">${j.provinceCount} prov</span>`;
  b.onclick=()=> selectJourney(selected===j.idx?null:j.idx);
  j._leg=b; legend.appendChild(b);
});
// choropleth key
const heatKey=document.createElement("div"); heatKey.className="heatkey"; heatKey.id="heatkey";
heatKey.innerHTML=`<div class="lg-h">Caravan-days · shading</div><div class="hk-bar"></div>
  <div class="hk-scale"><span>less</span><span>${MAXD}</span></div>`;
legend.appendChild(heatKey);

// ---- heat toggle ----
const heatBtn=document.getElementById("heatBtn");
heatBtn.setAttribute("aria-pressed","true");
heatBtn.onclick=()=>{ showHeat=!showHeat; heatBtn.setAttribute("aria-pressed",showHeat);
  heatBtn.style.opacity=showHeat?"":".5"; heatKey.style.display=showHeat?"":"none"; draw(); };

// ---- movement-cost overlay toggle (terrain-zoom elevation difficulty) ----
const costBtn=document.getElementById("costBtn"), costKey=document.getElementById("costKey");
costBtn.setAttribute("aria-pressed","false"); costBtn.style.opacity=".5";
costBtn.onclick=()=>{ showCost=!showCost; costBtn.setAttribute("aria-pressed",showCost);
  costBtn.style.opacity=showCost?"":".5"; costKey.style.display=showCost?"":"none"; draw(); };

// ---- interaction: hover province ----
const tip=document.getElementById("tip");
// point-in-polygon over a province's rings (even-odd, in screen space)
function pointInProv(p, mx, my){
  let inside=false;
  for(const ring of p.rings){
    for(let i=0, j=ring.length-1; i<ring.length; j=i++){
      const xi=pxr(ring[i][0]), yi=pyr(ring[i][1]), xj=pxr(ring[j][0]), yj=pyr(ring[j][1]);
      if(((yi>my)!==(yj>my)) && (mx < (xj-xi)*(my-yi)/(yj-yi)+xi)) inside=!inside;
    }
  }
  return inside;
}
function provinceAt(mx, my){
  for(const p of P){ if(p.rings && pointInProv(p, mx, my)) return p; }   // exact polygon hit
  let best=null, bd=1e9;                                                  // else nearest centroid (seas)
  for(const p of P){ const dx=px(p.lon)-mx, dy=py(p.lat)-my, d=dx*dx+dy*dy; if(d<bd){bd=d;best=p;} }
  return bd<90 ? best : null;
}
stage.addEventListener("mousemove", e=>{
  if(dragging) return;                       // panning — skip hover work
  const r=stage.getBoundingClientRect(), mx=e.clientX-r.left, my=e.clientY-r.top;
  const best = provinceAt(mx, my);
  if(best){ hoverProv=best;
    tip.innerHTML=`<b>${best.name}</b> <span class="r">${best.type.toLowerCase()}</span><br><span class="r">${(best.region||"—").replace(/_/g," ").replace(" region","")} · ${best.plots} plots${best.days?` · ${best.days} caravan-days`:""}</span>`;
    tip.style.left=Math.min(mx+14, r.width-230)+"px"; tip.style.top=(my+14)+"px"; tip.classList.add("on");
  } else { hoverProv=null; tip.classList.remove("on"); }
  draw();
});
stage.addEventListener("mouseleave", ()=>{ hoverProv=null; tip.classList.remove("on"); draw(); });
stage.addEventListener("click", e=>{
  if(panMoved){ panMoved=false; return; }    // this "click" was the end of a drag
  const r=stage.getBoundingClientRect(), mx=e.clientX-r.left, my=e.clientY-r.top;
  // in caravan mode a click near a route's current marker selects that journey
  if (mode==="caravan") {
    let best=null,bd=1e9;
    J.forEach(j=>{ const d=j.keys[j.keys.length-1]; const dx=px(d.lon)-mx,dy=py(d.lat)-my,dd=dx*dx+dy*dy; if(dd<bd){bd=dd;best=j;} });
    if(best && bd<160){ selectJourney(selected===best.idx?null:best.idx); return; }
  }
  // otherwise the click selects the province under the cursor (toggles off if re-clicked)
  const prov = provinceAt(mx, my);
  if (prov) selectProvince(selectedProv===prov ? null : prov);
});

// ---- rail ----
const rail=document.getElementById("rail");
function selectJourney(idx){
  selectedProv=null;               // journey selection replaces any province detail
  selected=idx;
  J.forEach(j=> j._leg.setAttribute("aria-pressed", j.idx===idx));
  renderRail(); draw();
}
function railMeta(){
  const m=BUNDLE.meta;
  const span = ((t1-t0)/365.25).toFixed(1);
  return `<div class="runmeta">
    <div class="rm-title serif" style="font-size:16px">Run summary</div>
    <div class="rm-sub"><code class="mono" style="color:var(--accent)">${m.scenario}</code> · seed ${m.seed}</div>
    <div class="metagrid">
      <div class="metacell"><div class="k">Caravans</div><div class="v">${J.length}</div></div>
      <div class="metacell"><div class="k">Provinces mapped</div><div class="v">${P.length}</div></div>
      <div class="metacell"><div class="k">Origin</div><div class="v" style="font-size:15px">${m.origin.name}</div></div>
      <div class="metacell"><div class="k">Span</div><div class="v">${span}<small> yrs</small></div></div>
    </div></div>`;
}
function sparkline(j, field, color, opts={}){
  const ks=j.keys, W=300, H=96, pad=6;
  const xs=ks.map(k=>k.t), ys=ks.map(k=>k[field]);
  const xmin=xs[0], xmax=xs[xs.length-1], ymax=Math.max(opts.ymax||0, ...ys), ymin=0;
  const X=t=> pad + (t-xmin)/(xmax-xmin)*(W-2*pad);
  const Y=v=> H-pad - (v-ymin)/((ymax-ymin)||1)*(H-2*pad);
  let d="", area="";
  ks.forEach((k,i)=>{ const x=X(k.t),y=Y(k[field]); d+=(i?"L":"M")+x.toFixed(1)+" "+y.toFixed(1)+" "; });
  area = d + `L ${X(xmax).toFixed(1)} ${(H-pad)} L ${X(xmin).toFixed(1)} ${(H-pad)} Z`;
  // now marker
  const nv = lerpField(j, curT, field), nx = X(Math.max(xmin,Math.min(xmax,curT))), ny=Y(nv);
  const grid=[0.5].map(f=>`<line x1="${pad}" y1="${(Y(ymax*f)).toFixed(1)}" x2="${W-pad}" y2="${(Y(ymax*f)).toFixed(1)}" stroke="var(--line-soft)" stroke-width="1"/>`).join("");
  return `<svg viewBox="0 0 ${W} ${H}" preserveAspectRatio="none">
    <defs><linearGradient id="g${field}${j.idx}" x1="0" x2="0" y1="0" y2="1">
      <stop offset="0" stop-color="${color}" stop-opacity="0.28"/><stop offset="1" stop-color="${color}" stop-opacity="0"/>
    </linearGradient></defs>
    ${grid}
    <path d="${area}" fill="url(#g${field}${j.idx})"/>
    <path d="${d}" fill="none" stroke="${color}" stroke-width="2" stroke-linejoin="round"/>
    <line x1="${nx.toFixed(1)}" y1="${pad}" x2="${nx.toFixed(1)}" y2="${H-pad}" stroke="var(--ink-faint)" stroke-width="1" stroke-dasharray="2 3"/>
    <circle cx="${nx.toFixed(1)}" cy="${ny.toFixed(1)}" r="3.5" fill="${color}" stroke="var(--panel-2)" stroke-width="1.5"/>
  </svg>`;
}
function parseCarrying(s){
  if(!s) return {items:[], more:0};
  const more = (s.match(/\(\+(\d+) more\)/)||[])[1];
  const items = s.replace(/\s*\(\+\d+ more\)/,"").split(";").map(x=>x.trim()).filter(Boolean).map(x=>{
    const m=x.match(/^(.*?)\s+(\d+)$/); return m?{name:m[1].replace(/_/g," "), n:+m[2]}:{name:x,n:0};
  });
  return {items, more: more?+more:0};
}
function worldRail(){
  return `<div class="runmeta">
    <div class="rm-title serif" style="font-size:16px">WorldMap</div>
    <div class="rm-sub">Anbennar · the whole world, real Civ4 terrain</div>
    <div class="metagrid">
      <div class="metacell"><div class="k">Land provinces</div><div class="v">${P.length}</div></div>
      <div class="metacell"><div class="k">Zoom</div><div class="v" style="font-size:15px">to the plot</div></div>
    </div></div>
    <p class="footnote">The full world, rendered from the engine's real terrain. Drag to pan, scroll to zoom — keep zooming past the continent view to resolve any province into its terrain plot by plot (textures, hillshade from the heightmap, rivers, features). Hover the map to read a province. Switch to <b>Caravan</b> mode to replay the six-band migration from Dhenijansar.</p>`;
}
// show/hide the caravan-only chrome and swap the title for a mode
function setMode(m){
  mode = m;
  document.querySelectorAll("#modeToggle button").forEach(b=> b.setAttribute("aria-pressed", b.dataset.mode===m));
  const cara = m === "caravan";
  legend.style.display = cara ? "" : "none";
  document.querySelector(".transport").style.display = cara ? "" : "none";
  heatBtn.style.display = cara ? "" : "none";
  document.querySelector(".eyebrow").textContent = cara ? `CivStudio · seed ${BUNDLE.meta.seed} · replay` : "CivStudio · WorldMap";
  document.querySelector(".title").textContent = cara ? "Migration from Dhenijansar" : "The World of Anbennar";
  document.querySelector(".subtitle").style.display = cara ? "" : "none";
  if (!cara) { pause(); selected = null; }
  selectedProv = null;             // start each mode on its own overview
  renderRail(); draw();
}
// ---- province detail: the full-information sidebar for a selected province ----
// aggregate a province's per-plot data into a terrain breakdown once its plots are loaded
function provinceStats(plots) {
  const terr = {}, feat = {}, res = {};
  let flat=0, hill=0, peak=0, rivers=0, eMin=255, eMax=0, eSum=0;
  for (const q of plots) {
    terr[q.terrain] = (terr[q.terrain]||0) + 1;
    if (q.plotType==="HILL") hill++; else if (q.plotType==="PEAK") peak++; else flat++;
    if (q.river) rivers++;
    if (q.feature) feat[q.feature] = (feat[q.feature]||0) + 1;
    if (q.bonus) res[q.bonus] = (res[q.bonus]||0) + 1;
    const e = q.elevation|0; if (e<eMin) eMin=e; if (e>eMax) eMax=e; eSum+=e;
  }
  const desc = o => Object.entries(o).sort((a,b)=> b[1]-a[1]);
  return { n:plots.length, terr:desc(terr), feat:desc(feat), res:desc(res), flat, hill, peak,
    rivers, eMin: plots.length?eMin:0, eMax, eMean: plots.length?Math.round(eSum/plots.length):0 };
}
// prettify a Civ4 TERRAIN_/FEATURE_/BONUS_ id (or a bare key like "mild"): strip prefix, Title Case
function prettyId(s) {
  return String(s).replace(/^(TERRAIN|FEATURE|BONUS)_/,"").toLowerCase().replace(/_/g," ")
    .replace(/\b\w/g, c=>c.toUpperCase());
}
function selectProvince(p) {
  selectedProv = p;
  if (p && p.hasPlots !== false && !p._plots) loadPlots(p);   // stream in terrain for the breakdown
  renderRail(); draw();
}
function provinceRail(p) {
  const g = p.geo || {};
  // each tier is [displayName, rawClausewitzKey]; show the key in parentheses after the name
  const crumbs = [g.continent, g.superRegion, g.region, g.area].filter(t => t && t[0])
    .map(t=>`<span>${t[0]}${t[1]?` <span class="pv-key">(${t[1]})</span>`:''}</span>`)
    .join('<span class="crumb-sep">›</span>');
  const coord = `${Math.abs(p.lat).toFixed(2)}°${p.lat>=0?"N":"S"}, ${Math.abs(p.lon).toFixed(2)}°${p.lon>=0?"E":"W"}`;
  let terrainHtml;
  if (p._plots) {
    const s = provinceStats(p._plots);
    const bars = s.terr.map(([k,n])=>{
      const pct = Math.round(n/s.n*100), c = terrainRgb(k);
      return `<div class="pv-bar-row"><span class="pv-bar-lab" title="${prettyId(k)}">${prettyId(k)}</span>
        <span class="pv-bar"><i style="width:${pct}%;background:rgb(${c[0]},${c[1]},${c[2]})"></i></span>
        <span class="pv-bar-val">${pct}%</span></div>`;
    }).join("");
    const chips = o => o.length ? o.map(([k,n])=>`<span class="pv-chip">${prettyId(k)}<b>${n}</b></span>`).join("") : '<span class="pv-none">—</span>';
    terrainHtml = `
      <p class="sectlabel">Terrain · ${s.n} plots</p>
      <div class="pv-bars">${bars}</div>
      <div class="statrow" style="margin-top:10px">
        <div class="stat"><div class="k">Flat</div><div class="v">${s.flat}</div></div>
        <div class="stat"><div class="k">Hill</div><div class="v">${s.hill}</div></div>
        <div class="stat"><div class="k">Peak</div><div class="v">${s.peak}</div></div>
      </div>
      <div class="statrow" style="margin-top:8px">
        <div class="stat"><div class="k">Elevation</div><div class="v">${s.eMin}–${s.eMax}<small style="font-size:11px;color:var(--ink-soft)"> µ${s.eMean}</small></div></div>
        <div class="stat"><div class="k">River plots</div><div class="v">${s.rivers}</div></div>
      </div>
      <p class="sectlabel" style="margin-top:14px">Features</p>
      <div class="pv-chips">${chips(s.feat)}</div>
      <p class="sectlabel" style="margin-top:12px">Resources</p>
      <div class="pv-chips">${chips(s.res)}</div>`;
  } else if (p.hasPlots === false || !PLOT_INDEX[p.id]) {
    terrainHtml = `<p class="footnote">No per-plot terrain for this province.</p>`;
  } else {
    terrainHtml = `<p class="footnote">Loading terrain…</p>`;
  }
  rail.innerHTML = `
    <button class="backbtn" id="backProv">← Back</button>
    <div class="detail">
      <div class="d-head"><h2 class="serif">${p.name}</h2></div>
      <div class="rm-sub" style="color:var(--ink-soft);margin-top:-6px"><span class="r">${p.type.toLowerCase()}</span> · province ${p.id}</div>
      ${crumbs ? `<div class="pv-crumbs">${crumbs}</div>` : ""}
      <div class="statrow" style="margin-top:12px">
        <div class="stat"><div class="k">Land plots</div><div class="v">${p.plots}</div></div>
        <div class="stat"><div class="k">Water plots</div><div class="v">${p.waterPlots||0}</div></div>
        <div class="stat"><div class="k">Neighbours</div><div class="v">${(p.nb||[]).length}</div></div>
      </div>
      <div class="metagrid" style="margin-top:8px">
        <div class="metacell"><div class="k">Coordinates</div><div class="v" style="font-size:13px">${coord}</div></div>
        <div class="metacell"><div class="k">Winter</div><div class="v" style="font-size:13px">${p.winter?prettyId(p.winter):"—"}</div></div>
        ${p.days?`<div class="metacell"><div class="k">Caravan-days</div><div class="v">${p.days}</div></div>`:""}
      </div>
      ${terrainHtml}
    </div>`;
  document.getElementById("backProv").onclick = ()=>{ selectedProv=null; renderRail(); draw(); };
}
function renderRail(){
  if (selectedProv) { provinceRail(selectedProv); return; }
  if (mode === "world") { rail.innerHTML = worldRail(); return; }
  if(selected===null){
    const rows = J.map(j=>`<tr class="click" data-idx="${j.idx}">
        <td><div class="destcell"><span class="dot" style="background:${j.color}"></span>${j.dest}</div></td>
        <td class="num">${j.provinceCount}</td>
        <td class="num">${(j.days/365.25).toFixed(1)}y</td>
        <td class="num" style="color:var(--cargo)">${j.cargoFinal}</td>
      </tr>`).join("");
    rail.innerHTML = railMeta() + `
      <div>
        <p class="sectlabel">The six journeys</p>
        <table class="cmp">
          <thead><tr><th>Destination</th><th class="num">Prov</th><th class="num">Time</th><th class="num">Cargo</th></tr></thead>
          <tbody>${rows}</tbody>
        </table>
      </div>
      <p class="footnote">All six bands leave <b>${BUNDLE.meta.origin.name}</b> on <span class="mono">${BUNDLE.meta.dateStart}</span> and march the province graph one daylight-bounded leg per day, foraging food and gathering trade goods into a capacity-capped cargo. Drag to pan, scroll to zoom — keep zooming past the continent view to resolve each province into its real Civ4 terrain, plot by plot. Hover the map to read a province; pick a route on the map or a row below to follow one band. Scrub or press play to watch them travel.</p>`;
    rail.querySelectorAll("tr.click").forEach(tr=> tr.onclick=()=> selectJourney(+tr.dataset.idx));
  } else {
    const j=J[selected];
    rail.innerHTML = `
      <button class="backbtn" id="back">← All caravans</button>
      <div class="detail">
        <div class="d-head"><span class="route-dot" style="background:${j.color}"></span>
          <h2 class="serif">${j.dest}</h2></div>
        <div class="rm-sub" style="color:var(--ink-soft);margin-top:-8px">from ${BUNDLE.meta.origin.name} · <span class="mono">${j.startDate}</span> → <span class="mono">${j.endDate}</span></div>
        <div class="statrow">
          <div class="stat"><div class="k">Provinces</div><div class="v">${j.provinceCount}</div></div>
          <div class="stat"><div class="k">Duration</div><div class="v">${(j.days/365.25).toFixed(1)}<small style="font-size:11px;color:var(--ink-soft)"> yr</small></div></div>
          <div class="stat"><div class="k">Band now</div><div class="v" id="bandNow">–</div></div>
        </div>
        <div class="chart">
          <div class="c-head"><span class="c-title">Cargo hauled</span><span class="c-now mono" id="cargoNow" style="color:var(--cargo)"></span></div>
          ${sparkline(j,"cargo",cssVar("--cargo"),{ymax:500})}
        </div>
        <div class="chart">
          <div class="c-head"><span class="c-title">Larder (food carried)</span><span class="c-now mono" id="larderNow" style="color:var(--good)"></span></div>
          ${sparkline(j,"larder",cssVar("--good"))}
        </div>
        <div>
          <p class="sectlabel">Cargo on arrival · ${j.cargoFinal} units</p>
          <div class="cargo-list" id="cargoList"></div>
        </div>
        <p class="footnote">Foraging refills the larder while daylight allows; surplus daylight is spent gathering the trade goods the band crosses — only those whose reveal-tech it knows — into <code>Cargo</code>, capped at hold capacity. This band arrives at <b>${j.dest}</b> carrying the goods below.</p>
      </div>`;
    document.getElementById("back").onclick=()=> selectJourney(null);
    const {items,more}=parseCarrying(j.carryingFinal);
    const mx=Math.max(...items.map(i=>i.n),1);
    document.getElementById("cargoList").innerHTML = items.map(it=>`
      <div class="cargo-item"><span class="cname" title="${it.name}">${it.name}</span>
        <span class="cargo-bar"><i style="width:${(it.n/mx*100).toFixed(0)}%"></i></span>
        <span class="cval">${it.n}</span></div>`).join("") + (more?`<div class="cargo-more">+ ${more} more goods</div>`:"");
    updateDetailLive();
  }
}
function updateDetailLive(){
  const j=J[selected]; if(!j) return;
  const cn=document.getElementById("cargoNow"), ln=document.getElementById("larderNow"), bn=document.getElementById("bandNow");
  if(cn) cn.textContent = fmtInt(lerpField(j,curT,"cargo"))+" u";
  if(ln) ln.textContent = fmtInt(lerpField(j,curT,"larder"));
  if(bn) bn.textContent = Math.round(lerpField(j,curT,"band"));
  // re-render sparkline now-markers cheaply by redrawing rail charts' vertical line:
  const charts=rail.querySelectorAll(".chart svg");
  const fields=["cargo","larder"];
  charts.forEach((svg,i)=>{
    const ks=j.keys, W=300,H=96,pad=6, xmin=ks[0].t, xmax=ks[ks.length-1].t;
    const X=t=> pad+(t-xmin)/(xmax-xmin)*(W-2*pad);
    const ymax=(fields[i]==="cargo")?500:Math.max(...ks.map(k=>k.larder));
    const Y=v=> H-pad-(v/((ymax)||1))*(H-2*pad);
    const nx=X(Math.max(xmin,Math.min(xmax,curT))), nv=lerpField(j,curT,fields[i]), ny=Y(nv);
    const line=svg.querySelector("line[stroke-dasharray]"), c=svg.querySelector("circle");
    if(line){ line.setAttribute("x1",nx.toFixed(1)); line.setAttribute("x2",nx.toFixed(1)); }
    if(c){ c.setAttribute("cx",nx.toFixed(1)); c.setAttribute("cy",ny.toFixed(1)); }
  });
}

// ---- theme toggle ----
const themeBtn=document.getElementById("themeBtn");
themeBtn.onclick=()=>{
  const cur=document.documentElement.getAttribute("data-theme")
    || (matchMedia("(prefers-color-scheme: dark)").matches?"dark":"light");
  document.documentElement.setAttribute("data-theme", cur==="dark"?"light":"dark");
  draw(); if(selected!==null) renderRail();
};
matchMedia("(prefers-color-scheme: dark)").addEventListener("change", ()=>{ draw(); });

// ---- deep link: index.html#p=<provinceId>&z=<zoom> focuses a province at a zoom ----
const Pby = new Map(P.map(p => [p.id, p]));
function focusProvince(id, k) {
  const p = Pby.get(id); if (!p) return;
  cam.k = Math.max(1, Math.min(64, k || 18));
  cam.x = VIEW.w / 2 - cam.k * baseXr(sxSrc(p.lon));
  cam.y = VIEW.h / 2 - cam.k * baseYr(sySrc(p.lat));
  clampPan(); viewVersion++; draw();
}
function applyHash() {
  const p = /(?:^|[#&])p=(\d+)/.exec(location.hash);
  const z = /(?:^|[#&])z=(\d+(?:\.\d+)?)/.exec(location.hash);
  if (p) focusProvince(+p[1], z ? +z[1] : 18);
}
window.addEventListener("hashchange", applyHash);

// ---- shared button tooltips (positioned to stay within the stage) ----
const btntip = document.getElementById("btntip");
let tipTimer = 0;
function showBtnTip(el) {
  const text = el.getAttribute("data-tip"); if (!text) return;
  btntip.textContent = text;
  const sr = stage.getBoundingClientRect(), br = el.getBoundingClientRect();
  const bw = btntip.offsetWidth, bh = btntip.offsetHeight;
  let x = br.left - sr.left + br.width / 2 - bw / 2;       // centre on the button, clamp to stage
  x = Math.max(6, Math.min(x, sr.width - bw - 6));
  let y = br.top - sr.top - bh - 8;                        // above by default…
  if (y < 6) y = br.bottom - sr.top + 8;                   // …flip below when there is no room
  btntip.style.left = x + "px"; btntip.style.top = y + "px";
  btntip.classList.add("on");
}
function hideBtnTip() { clearTimeout(tipTimer); btntip.classList.remove("on"); }
stage.querySelectorAll("[data-tip]").forEach(el => {
  el.addEventListener("mouseenter", () => { clearTimeout(tipTimer); tipTimer = setTimeout(() => showBtnTip(el), 320); });
  el.addEventListener("mouseleave", hideBtnTip);
  el.addEventListener("mousedown", hideBtnTip);
});

// ---- province search (by name or id → zoom to it) ----
const searchInput = document.getElementById("search");
const searchResults = document.getElementById("searchResults");
const searchClear = document.getElementById("searchClear");
let searchMatches = [], searchActive = -1;
function goToProvince(p, k = 9) {
  focusProvince(p.id, k);        // zoom + centre the camera on it
  selectProvince(p);             // and open its detail panel
}
function runSearch(raw) {
  const q = raw.trim().toLowerCase();
  searchClear.hidden = !q;
  if (!q) { searchResults.hidden = true; searchMatches = []; return; }
  const isNum = /^\d+$/.test(q);
  const scored = [];
  for (const p of P) {
    if (p.type !== "LAND") continue;
    let score = -1;
    if (isNum) { const ids = String(p.id); if (ids === q) score = 100; else if (ids.startsWith(q)) score = 55; }
    if (score < 0) {
      const name = p.name.toLowerCase();
      if (name === q) score = 90; else if (name.startsWith(q)) score = 70; else if (name.includes(q)) score = 40;
    }
    if (score >= 0) scored.push({ p, score });
  }
  scored.sort((a, b) => b.score - a.score || b.p.plots - a.p.plots || a.p.name.localeCompare(b.p.name));
  searchMatches = scored.slice(0, 12).map(s => s.p);
  searchActive = searchMatches.length ? 0 : -1;
  renderSearchResults();
}
function renderSearchResults() {
  if (!searchMatches.length) {
    searchResults.innerHTML = `<div class="search-empty">No province matches.</div>`;
    searchResults.hidden = false; return;
  }
  searchResults.innerHTML = searchMatches.map((p, i) => {
    const reg = (p.geo && p.geo.region && p.geo.region[0]) || "";
    return `<div class="search-row${i === searchActive ? " active" : ""}" role="option" data-i="${i}">
      <span class="sr-name">${p.name}</span><span class="sr-id">#${p.id}</span>
      <span class="sr-meta">${reg}</span></div>`;
  }).join("");
  searchResults.hidden = false;
  searchResults.querySelectorAll(".search-row").forEach(row =>
    row.addEventListener("mousedown", e => { e.preventDefault(); pickSearch(+row.dataset.i); }));
}
function pickSearch(i) {
  const p = searchMatches[i]; if (!p) return;
  searchResults.hidden = true; searchInput.blur();
  goToProvince(p);
}
searchInput.addEventListener("input", () => runSearch(searchInput.value));
searchInput.addEventListener("focus", () => { if (searchInput.value.trim()) runSearch(searchInput.value); });
searchInput.addEventListener("blur", () => setTimeout(() => { searchResults.hidden = true; }, 150));
searchInput.addEventListener("keydown", e => {
  if (e.key === "Escape") { searchInput.value = ""; runSearch(""); searchInput.blur(); return; }
  if (searchResults.hidden || !searchMatches.length) return;
  if (e.key === "ArrowDown") { e.preventDefault(); searchActive = Math.min(searchActive + 1, searchMatches.length - 1); renderSearchResults(); }
  else if (e.key === "ArrowUp") { e.preventDefault(); searchActive = Math.max(searchActive - 1, 0); renderSearchResults(); }
  else if (e.key === "Enter") { e.preventDefault(); if (searchActive >= 0) pickSearch(searchActive); }
});
searchClear.addEventListener("click", () => { searchInput.value = ""; runSearch(""); searchInput.focus(); });

// ---- mode toggle ----
document.querySelectorAll("#modeToggle button").forEach(b =>
  b.addEventListener("click", () => setMode(b.dataset.mode)));

// ---- boot ----
window.addEventListener("resize", resize);
resize();
setT(t0);
setMode(mode);            // paints the rail + chrome for the active mode (default: world)
applyHash();
if(mode === "caravan" && !reduce && !location.hash) setTimeout(play, 650);
