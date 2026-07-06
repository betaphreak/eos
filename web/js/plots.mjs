import { BUNDLE, P, TCOL, terrainRgb, provSrcBox, PLOT_INDEX, K_PLOT, K_TEX, TT, RIVER, COAST, LY, NB4, cam, VIEW, ctx, pxr, pyr, lerp, S } from "./core.mjs";
import { draw } from "./main.mjs";
import { renderRail } from "./panel.mjs";
let ttImg = null, ttReady = false, ttTiles = null;
if (TT) { ttImg = new Image(); ttImg.onload = () => { extractTiles(); ttReady = true; draw(); }; ttImg.src = TT.src; }
// the baked water tile for the river ribbon (docs/river-rendering.md §2); null when the
// build could not decode the Civ4 river art (LFS/file://) → drawRiver keeps the flat fill
let rvImg = null, rvReady = false;
if (RIVER) { rvImg = new Image(); rvImg.onload = () => { rvReady = true; draw(); }; rvImg.src = RIVER.src; }
// the Civ4 coastscalemask 16-way blend atlas (docs/coastlines.md §B), split into 16 per-index
// tiles; null when the masks are absent (LFS/file://) → drawCoast keeps the procedural surf
let csImg = null, csTiles = null;
if (COAST) { csImg = new Image(); csImg.onload = () => { extractCoast(); draw(); }; csImg.src = COAST.src; }
function extractCoast() {
  csTiles = [];
  for (let m = 0; m < COAST.n; m++) {
    const c = document.createElement("canvas"); c.width = COAST.tile; c.height = COAST.tile;
    c.getContext("2d").drawImage(csImg, m * COAST.tile, 0, COAST.tile, COAST.tile, 0, 0, COAST.tile, COAST.tile);
    csTiles[m] = c;
  }
}
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
    if (arr) { p._plots = arr; draw(); if (S.selectedProv === p) renderRail(); }   // fill the detail panel too
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
  if (!S.showCost || cam.k < K_PLOT) return;
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
  const textured = cam.k >= K_TEX && ttReady && !S.dragging;   // flat tiles while panning (cheap)
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
  const riverPat = rvReady && rvImg ? o.createPattern(rvImg, "repeat") : null;   // water texture, or null
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
    if (q.coast) drawCoast(o, cx, cy, tpp, q.coast);
    if (q.feature) featureSprite(o, cx, cy, tpp, q.feature, q.x, q.y);
    if (q.river) drawRiver(o, cx, cy, tpp, q, grid, riverPat);
  }
  p._tcanvas = oc; p._tbox = { x0, y0, w, h };
}
// draw the coastline on a plot from its 8-bit sea mask (q.coast — low nibble = orthogonal
// edges 1=E,2=W,4=S,8=N; high nibble = diagonal corners; see docs/coastlines.md). The
// shallow water is the faithful Civ4 coastscalemask 16-way blend, indexed by the corner
// nibble (coast>>4) — a smooth, rounded shore that tiles correctly — with a procedural surf
// band as the fallback when the masks are absent (LFS/file://). A thin foam line at each
// orthogonal water edge crisps the shoreline in both modes.
const COAST_EDGES = [[1, 1, 0], [2, -1, 0], [4, 0, 1], [8, 0, -1]];   // bit, dx, dy (E,W,S,N)
const SHALLOW = "116,178,196", FOAM = "224,240,244";
function drawCoast(o, cx, cy, s, mask) {
  o.save();
  if (csTiles) {                                       // faithful Civ4 coastscalemask blend
    const idx = (mask >> 4) & 15;                      // diagonal-corner index → blend tile
    if (idx === 15) { o.fillStyle = `rgba(${SHALLOW},.72)`; o.fillRect(cx, cy, s, s); }   // fully surrounded (blank mask)
    else if (idx !== 0 && csTiles[idx]) { o.imageSmoothingEnabled = true; o.drawImage(csTiles[idx], 0, 0, COAST.tile, COAST.tile, cx, cy, s, s); }
  } else {                                             // procedural surf fallback
    const f = s * 0.55;
    for (const [bit, dx, dy] of COAST_EDGES) {
      if (!(mask & bit)) continue;
      let gr, rx, ry, rw, rh;
      if (dx === 1) { gr = o.createLinearGradient(cx + s, 0, cx + s - f, 0); rx = cx + s - f; ry = cy; rw = f; rh = s; }
      else if (dx === -1) { gr = o.createLinearGradient(cx, 0, cx + f, 0); rx = cx; ry = cy; rw = f; rh = s; }
      else if (dy === 1) { gr = o.createLinearGradient(0, cy + s, 0, cy + s - f); rx = cx; ry = cy + s - f; rw = s; rh = f; }
      else { gr = o.createLinearGradient(0, cy, 0, cy + f); rx = cx; ry = cy; rw = s; rh = f; }
      gr.addColorStop(0, `rgba(${SHALLOW},.8)`); gr.addColorStop(1, `rgba(${SHALLOW},0)`);
      o.fillStyle = gr; o.fillRect(rx, ry, rw, rh);
    }
  }
  // a thin foam line at each orthogonal water edge (crisp shoreline), both modes
  const t = Math.max(1, s * 0.09);
  o.fillStyle = `rgba(${FOAM},.5)`;
  if (mask & 1) o.fillRect(cx + s - t, cy, t, s);      // E
  if (mask & 2) o.fillRect(cx, cy, t, s);              // W
  if (mask & 4) o.fillRect(cx, cy + s - t, s, t);      // S
  if (mask & 8) o.fillRect(cx, cy, s, t);              // N
  o.restore();
}
// A river plot's segment: a water-textured ribbon from the cell centre out to each
// 4-neighbour that also carries a river (to the shared edge), or a source blob when it
// stands alone. The ribbon width tapers by the plot's authored river width — the low
// digit of the packed river code (q.river % 10; node markers read as width 1). Uses the
// baked water tile as a repeating pattern, falling back to the flat blue fill colour the
// map used before when that tile is unavailable (LFS not pulled / file://).
function drawRiver(o, cx, cy, s, q, grid, pat) {
  const isR = d => { const n = grid.get((q.x + d[0]) * 1e5 + (q.y + d[1])); return n && n.river; };
  const links = NB4.filter(isR);
  const lvl = Math.min(4, (q.river % 10) || 1);            // width digit 1..4; guard 0
  const mx = cx + s / 2, my = cy + s / 2, w = s * (0.16 + 0.06 * lvl);
  o.save();
  o.strokeStyle = pat || "rgba(74,124,170,.85)"; o.fillStyle = pat || "rgba(74,124,170,.85)";
  o.lineWidth = w; o.lineCap = "round"; o.lineJoin = "round";
  o.globalAlpha = pat ? 0.9 : 0.55;
  if (!links.length) { o.beginPath(); o.arc(mx, my, w * 0.6, 0, 7); o.fill(); }
  else for (const d of links) { o.beginPath(); o.moveTo(mx, my); o.lineTo(mx + d[0] * s / 2, my + d[1] * s / 2); o.stroke(); }
  o.restore();
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
export { drawPlots, drawCostOverlay, loadPlots };
