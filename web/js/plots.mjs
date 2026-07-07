import { BUNDLE, P, TCOL, terrainRgb, provSrcBox, PLOT_INDEX, K_PLOT, K_TEX, K_MAX, TT, RIVER, SHORE, FOAM_ART, ICE_ART, BONUS_ICONS, SEA_BANDS, LY, NB4, cam, VIEW, ctx, pxr, pyr, lerp, S } from "./core.mjs";
import { draw } from "./main.mjs";
import { renderRail } from "./panel.mjs";
let ttImg = null, ttReady = false, ttTiles = null;
if (TT) { ttImg = new Image(); ttImg.onload = () => { extractTiles(); ttReady = true; draw(); }; ttImg.src = TT.src; }
// the baked water tile for the river ribbon (docs/river-rendering.md §2); null when the
// build could not decode the Civ4 river art (LFS/file://) → drawRiver keeps the flat fill
let rvImg = null, rvReady = false;
if (RIVER) { rvImg = new Image(); rvImg.onload = () => { rvReady = true; draw(); }; rvImg.src = RIVER.src; }
// the baked greyscale shore-wave tile for the coast shallows (docs/coastlines.md Phase D); null
// when the Civ4 shore art couldn't be decoded → the shallows stay flat-tinted, no ripple
let shoreImg = null, shoreReady = false;
if (SHORE) { shoreImg = new Image(); shoreImg.onload = () => { shoreReady = true; draw(); }; shoreImg.src = SHORE.src; }
// the real Civ4 shoreline foam crest (docs/coastlines.md Phase G), waves/wave_crest.dds; null when
// absent → drawFoam keeps the thin procedural foam line
let foamImg = null, foamReady = false;
if (FOAM_ART) { foamImg = new Image(); foamImg.onload = () => { foamReady = true; draw(); }; foamImg.src = FOAM_ART.src; }
// the real Civ4 pack-ice tile (docs/coastlines.md Phase G), features/icepack; null when absent →
// drawSeaIce falls back to flat pale floes
let iceImg = null, iceReady = false, icePat = null;
if (ICE_ART) { iceImg = new Image(); iceImg.onload = () => { iceReady = true; draw(); }; iceImg.src = ICE_ART.src; }
// the shallows tint — the Civ4 shoreblend hue baked into the bundle, or the old teal fallback
const SHORE_COL = (SEA_BANDS && SEA_BANDS.shore) ? SEA_BANDS.shore.join(",") : "116,178,196";
// the real Civ4 resource-icon atlas (docs/bonus-sprite-bake.md), sliced from GameFont.tga; null when
// absent → drawBonusOverlay keeps the procedural category glyphs
let biImg = null, biReady = false;
if (BONUS_ICONS) { biImg = new Image(); biImg.onload = () => { biReady = true; draw(); }; biImg.src = BONUS_ICONS.src; }
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
  // a sea/lake province's shelf stays transparent in the flat overview (its resource glyphs only
  // appear at texture zoom), so the mid-zoom map shows clean water rather than coloured blobs
  const water = p.type === "SEA" || p.type === "LAKE";
  for (const q of plots) {
    if (water) continue;                       // leave water cells transparent (imageData is zero-filled)
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
  const vis = [];   // in-view provinces with plots loaded — reused by the bonus overlay (no 2nd P scan)
  for (const p of P) {
    if (!p.hasPlots) continue;
    const bb = provSrcBox(p);
    let sx0, sy0, sx1, sy1;
    if (bb) { sx0 = pxr(bb.x0); sy0 = pyr(bb.y0); sx1 = pxr(bb.x1); sy1 = pyr(bb.y1); }
    else { const x = px(p.lon), y = py(p.lat); sx0 = x - 20; sy0 = y - 20; sx1 = x + 20; sy1 = y + 20; }
    if (sx1 < 0 || sy1 < 0 || sx0 > VIEW.w || sy0 > VIEW.h) continue;   // cull to viewport
    if (!p._plots) { loadPlots(p); continue; }
    vis.push(p);
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
  drawBonusOverlay(vis);   // resource icons: screen-space overlay over the in-view provinces only
}
// rasterise a province's plots to a textured offscreen — each plot drawn as its Civ4
// ground-texture tile (from the atlas) at TPP px, plus relief/river overlays — built
// once and blitted scaled (so hover/pan redraws stay a single drawImage per province).
// TPP drops for very large provinces to bound the offscreen size.
function buildPlotTexCanvas(p) {
  let x0 = 1e9, y0 = 1e9, x1 = -1e9, y1 = -1e9;
  for (const q of p._plots) { if (q.x < x0) x0 = q.x; if (q.x > x1) x1 = q.x; if (q.y < y0) y0 = q.y; if (q.y > y1) y1 = q.y; }
  // pad the offscreen two cells beyond the land so the coastline can bleed OUTWARD into the
  // adjacent sea (which is not a plot of this province) — wide enough for the >1-cell shallows +
  // beach reach (Lever A). The coord/size/blit all follow x0..y1; the margin is transparent sea.
  const PAD = 2;
  x0 -= PAD; y0 -= PAD; x1 += PAD; y1 += PAD;
  const w = x1 - x0 + 1, h = y1 - y0 + 1;
  let tpp = 32; while (tpp > 4 && Math.max(w, h) * tpp > 2600) tpp = Math.max(4, tpp - 4);
  const oc = document.createElement("canvas"); oc.width = w * tpp; oc.height = h * tpp;
  const o = oc.getContext("2d"); o.imageSmoothingEnabled = true;
  const grid = new Map();
  for (const q of p._plots) grid.set(q.x * 1e5 + q.y, q);
  const riverPat = rvReady && rvImg ? o.createPattern(rvImg, "repeat") : null;   // water texture, or null
  // a sea/lake province's plots are all water — skip the land terrain/relief/shore/feature/river
  // stages entirely (its cells stay transparent so the base sea layer shows through), leaving only
  // the resource icons below. LAND and IMPASSABLE wasteland build the full ground.
  const water = p.type === "SEA" || p.type === "LAKE";
  if (!water) {
  // 1) base terrain as continuous repeating patterns (no per-plot tile seam)
  const pat = {};
  for (const q of p._plots) {
    const cx = (q.x - x0) * tpp, cy = (q.y - y0) * tpp;
    let pp = pat[q.terrain];
    if (pp === undefined) { const tc = ttTiles && ttTiles[q.terrain]; pp = pat[q.terrain] = tc ? o.createPattern(tc, "repeat") : null; }
    if (pp) { o.fillStyle = pp; o.fillRect(cx, cy, tpp, tpp); }
    else { const g = terrainRgb(q.terrain); o.fillStyle = `rgb(${g[0]},${g[1]},${g[2]})`; o.fillRect(cx, cy, tpp, tpp); }
  }
  // 2) edge blend: a neighbour's colour feathers over this plot across the shared edge (Civ4 §6.1,
  // adapted to the raster — a colour bleed, not a tile swap). A HIGHER-LayerOrder neighbour bleeds
  // strongly; EQUAL-order neighbours bleed mutually at half strength (each side blends the other) so
  // same-layer terrain boundaries — grass/plains/tundra etc. — soften instead of meeting at a hard
  // seam; a LOWER neighbour is skipped here and handled when ITS cell bleeds this one back.
  const f = tpp * 0.62;
  for (const q of p._plots) {
    const ql = LY[q.terrain] || 0, cx = (q.x - x0) * tpp, cy = (q.y - y0) * tpp;
    for (const d of NB4) {
      const n = grid.get((q.x + d[0]) * 1e5 + (q.y + d[1]));
      if (!n || n.terrain === q.terrain) continue;
      const nl = LY[n.terrain] || 0;
      if (nl < ql) continue;                       // lower neighbour bleeds from its own side
      const a = nl > ql ? 0.8 : 0.5;               // equal layers → softer mutual blend
      const g = terrainRgb(n.terrain);
      const c0 = `rgba(${g[0]},${g[1]},${g[2]},${a})`, c1 = `rgba(${g[0]},${g[1]},${g[2]},0)`;
      let gr, rx, ry, rw, rh;
      if (d[0] === 1) { gr = o.createLinearGradient(cx + tpp, 0, cx + tpp - f, 0); rx = cx + tpp - f; ry = cy; rw = f; rh = tpp; }
      else if (d[0] === -1) { gr = o.createLinearGradient(cx, 0, cx + f, 0); rx = cx; ry = cy; rw = f; rh = tpp; }
      else if (d[1] === 1) { gr = o.createLinearGradient(0, cy + tpp, 0, cy + tpp - f); rx = cx; ry = cy + tpp - f; rw = tpp; rh = f; }
      else { gr = o.createLinearGradient(0, cy, 0, cy + f); rx = cx; ry = cy; rw = tpp; rh = f; }
      gr.addColorStop(0, c0); gr.addColorStop(1, c1);
      o.fillStyle = gr; o.fillRect(rx, ry, rw, rh);
    }
  }
  // 3) snow on the highest ground. (The elevation-normal hillshade that used to sit here was
  // removed: with EXAG amplifying the gentle continental heightmap, near-flat provinces — most of
  // the map — picked up a strong per-plot bright/dark checker that just read as square tiles. The
  // ground is now the flat Civ4 terrain texture; relief reads from the terrain/feature mix instead.)
  for (const q of p._plots) {
    const e = q.elevation | 0;
    if (e >= 165) { const cx = (q.x - x0) * tpp, cy = (q.y - y0) * tpp; o.fillStyle = `rgba(232,238,247,${Math.min(0.6, (e - 165) / 50).toFixed(3)})`; o.fillRect(cx, cy, tpp, tpp); }
  }
  // 4) coast shallows: real Civ4 shore texture, drawn as one province-level pass so the ripple
  // blends over the whole shore region at once (paintCoast); then features + rivers on top, so a
  // river reaching the sea sits over the shallows/foam rather than under them
  paintCoast(o, oc.width, oc.height, p._plots, x0, y0, tpp, pat);
  for (const q of p._plots) {
    const cx = (q.x - x0) * tpp, cy = (q.y - y0) * tpp;
    if (q.feature) featureSprite(o, cx, cy, tpp, q.feature, q.x, q.y);
    if (q.river) drawRiver(o, cx, cy, tpp, q, grid, riverPat);
  }
  } // end land-only ground stages
  if (water) drawSeaIce(o, p._plots, x0, y0, tpp);   // polar sea ice on the shelf water plots
  p._tcanvas = oc; p._tbox = { x0, y0, w, h }; p._grid = grid;   // grid: q.x*1e5+q.y → plot, for the resource tooltip
}
// Resource icons are a SCREEN-SPACE overlay (not baked into the province texture), shown only at the
// deepest plot zooms (cam.k ≥ BONUS_ZOOM_MIN → the 64/128/256 steps). Each resourced plot — land or
// coastal-shelf water — gets its real Civ4 symbol (from the GameFont atlas), or the procedural
// category glyph fallback, anchored in the plot's BOTTOM-LEFT corner and sized to BONUS_MAX_PX at max
// zoom (k=K_MAX), scaling down proportionally as you zoom out. Cheap: at this depth only a handful of
// provinces are in view. Categories (colour + shape): sea food, gems/luxuries, energy, metals,
// farm/trade crops, livestock.
const BONUS_HIDE_AT = 16;       // no resource icons at this zoom or below (textures only just appear)
const BONUS_PLOTS = 1.32;       // icon size in PLOTS (≈21px at 64× on desktop). Sized off the on-screen
                                // plot size, not absolute px, so it scales with the terrain on any
                                // viewport — fixes mobile pinch, where a fixed-px icon covered too many plots.
function drawBonusOverlay(vis) {
  if (cam.k <= BONUS_HIDE_AT || !vis.length) return;
  const plotPx = pxr(1) - pxr(0);                      // one plot's on-screen size (tracks zoom AND viewport)
  const size = plotPx * BONUS_PLOTS;
  const inset = Math.max(0.5, plotPx * 0.06);          // nudge off the very corner (frac of a plot)
  const useIcons = BONUS_ICONS && biReady;
  ctx.save();
  ctx.lineWidth = Math.max(1, size * 0.06);
  ctx.strokeStyle = "rgba(8,12,20,.85)";               // dark keyline so glyphs read on any ground
  for (const p of vis) {                               // already culled to the viewport by drawPlots
    for (const q of p._plots) {
      if (!q.bonus) continue;
      const x = pxr(q.x) + inset, y = pyr(q.y + 1) - inset - size;      // plot bottom-left corner
      const idx = useIcons ? BONUS_ICONS.index[q.bonus] : undefined;
      if (idx !== undefined) {                          // real Civ4 GameFont symbol
        const cell = BONUS_ICONS.cell, cols = BONUS_ICONS.cols;
        ctx.imageSmoothingEnabled = true;
        ctx.drawImage(biImg, (idx % cols) * cell, Math.floor(idx / cols) * cell, cell, cell, x, y, size, size);
      } else {                                          // fallback: procedural category glyph
        const r = size / 2;
        glyphPath(ctx, bonusGlyph(q.bonus).s, x + r, y + r, r);
        ctx.fillStyle = bonusGlyph(q.bonus).c; ctx.fill(); ctx.stroke();
      }
    }
  }
  ctx.restore();
}
// Polar sea ice on a water province's shelf (docs/coastlines.md Phase E/G). The shelf is 70–90% ice,
// so drawing per-plot floes read as a checkerboard of white squares. Instead the whole ice field is
// ONE merged sheet: each cell's edges stay flush where the neighbour is also ice (interior seams
// vanish — no grid), and are inset by a jittered margin only where they face OPEN WATER, so just the
// outer ice/ocean boundary is ragged. One texture fill for the sheet, a cool rim on the outer edge
// only. Degrades to a flat pale sheet when the ice tile isn't loaded.
function drawSeaIce(o, plots, x0, y0, tpp) {
  const ice = plots.filter(q => q.feature === "FEATURE_ICE");
  if (!ice.length) return;
  const grid = new Map();
  for (const q of plots) grid.set(q.x * 1e5 + q.y, q);
  const isIce = (x, y) => { const n = grid.get(x * 1e5 + y); return !!(n && n.feature === "FEATURE_ICE"); };
  const hash = (x, y) => ((Math.imul(x | 0, 374761393) ^ Math.imul(y | 0, 668265263)) >>> 0) / 4294967295;
  // inset an edge ONLY when it faces open water (jittered → ragged floe margin); ice-ice edges flush
  const iN = q => isIce(q.x, q.y - 1) ? 0 : tpp * (0.12 + 0.26 * hash(q.x * 3 + 1, q.y));
  const iS = q => isIce(q.x, q.y + 1) ? 0 : tpp * (0.12 + 0.26 * hash(q.x * 3 + 2, q.y));
  const iW = q => isIce(q.x - 1, q.y) ? 0 : tpp * (0.12 + 0.26 * hash(q.x, q.y * 3 + 1));
  const iE = q => isIce(q.x + 1, q.y) ? 0 : tpp * (0.12 + 0.26 * hash(q.x, q.y * 3 + 2));
  if (iceReady) { icePat = icePat || o.createPattern(iceImg, "repeat");
    const s = Math.max(0.25, tpp * 4 / ICE_ART.tile); icePat.setTransform(new DOMMatrix([s, 0, 0, s, 0, 0])); }
  // 1) one merged fill of the whole sheet — interior seams gone, only the open-water edge is ragged
  const field = new Path2D();
  for (const q of ice) {
    const cx = (q.x - x0) * tpp, cy = (q.y - y0) * tpp;
    field.rect(cx + iW(q), cy + iN(q), tpp - iW(q) - iE(q), tpp - iN(q) - iS(q));
  }
  o.save();
  o.globalAlpha = 0.92; o.fillStyle = icePat || "rgb(226,236,245)"; o.fill(field);
  o.globalAlpha = 1; o.fillStyle = "rgba(255,255,255,0.08)"; o.fill(field);   // faint sun sheen over the sheet
  // 2) a cool rim on the OUTER (open-water) boundary only — loose segments, never the interior seams
  o.strokeStyle = "rgba(150,178,198,0.5)"; o.lineWidth = Math.max(0.6, tpp * 0.045); o.beginPath();
  for (const q of ice) {
    const cx = (q.x - x0) * tpp, cy = (q.y - y0) * tpp;
    const L = cx + iW(q), R = cx + tpp - iE(q), T = cy + iN(q), Bt = cy + tpp - iS(q);
    if (!isIce(q.x, q.y - 1)) { o.moveTo(L, T); o.lineTo(R, T); }
    if (!isIce(q.x, q.y + 1)) { o.moveTo(L, Bt); o.lineTo(R, Bt); }
    if (!isIce(q.x - 1, q.y)) { o.moveTo(L, T); o.lineTo(L, Bt); }
    if (!isIce(q.x + 1, q.y)) { o.moveTo(R, T); o.lineTo(R, Bt); }
  }
  o.stroke();
  o.restore();
}
// the glyph outline for a category shape, centred at (cx,cy) with radius r
function glyphPath(o, shape, cx, cy, r) {
  o.beginPath();
  if (shape === "circle") o.arc(cx, cy, r, 0, Math.PI * 2);
  else if (shape === "diamond") { o.moveTo(cx, cy - r); o.lineTo(cx + r, cy); o.lineTo(cx, cy + r); o.lineTo(cx - r, cy); o.closePath(); }
  else if (shape === "square") o.rect(cx - r * 0.82, cy - r * 0.82, r * 1.64, r * 1.64);
  else { o.moveTo(cx, cy - r); o.lineTo(cx + r * 0.9, cy + r * 0.72); o.lineTo(cx - r * 0.9, cy + r * 0.72); o.closePath(); }   // triangle
}
// bonus type -> {colour, shape} by category (first keyword match wins); pale dot for the rest
const BONUS_CATEGORIES = [
  [/FISH|CRAB|CLAM|SHRIMP|LOBSTER|WHALE|OYSTER|SEAWEED|KELP/, { c: "#5fe3d0", s: "circle" }],       // sea food — teal circle
  [/PEARL|GEM|GOLD|SILVER|DIAMOND|AMBER|JADE|CORAL|OPAL/,     { c: "#e879f9", s: "diamond" }],       // gems/luxury — magenta diamond
  [/OIL|GAS|COAL|URANIUM|METHANE|HYDROTHERMAL|VENT|PEAT|TAR/, { c: "#f59e0b", s: "triangle" }],      // energy — amber triangle
  [/IRON|COPPER|TIN|ALUMIN|LEAD|ZINC|NICKEL|TITAN|MITHRIL|ORE|MARBLE|STONE/, { c: "#9fb0c0", s: "square" }], // metal/stone — steel square
  [/WHEAT|CORN|MAIZE|RICE|BANANA|POTATO|SUGAR|WINE|SPICE|COFFEE|TEA|TOBACCO|COTTON|SILK|DYE|INCENSE|OLIVE|CITRUS|WHEAT/, { c: "#84cc16", s: "circle" }], // farm/trade crop — green circle
  [/COW|CATTLE|SHEEP|PIG|HORSE|DEER|BISON|CAMEL|ELEPHANT|GOAT|REINDEER|FUR|IVORY/, { c: "#d6a06a", s: "circle" }], // livestock/game — tan circle
];
function bonusGlyph(type) {
  for (const [re, style] of BONUS_CATEGORIES) if (re.test(type)) return style;
  return { c: "#c8d2e0", s: "circle" };                // uncategorised — pale dot
}
// Coast rendering from each land plot's 8-bit sea mask (q.coast — see docs/coastlines.md): 1=E,2=W,
// 4=S,8=N edges (low nibble), 16=NW,32=NE,64=SE,128=SW diagonal sea corners (high nibble). Two things
// make the shore: (1) a wavy shallow band that both reaches OUTWARD from the shoreline into the sea AND
// recedes INWARD into the land — the inward part is carved with a CORNER-CONTINUOUS erosion so the
// coast is a smooth wavy line across cells, not a grid staircase; (2) the real Civ4 shoredetail ripple
// clipped to that shallow shape. (Earlier per-cell rectangular bites read as blue blotches on the land,
// and the wave-crest foam lapped onto land — both dropped for this continuous shallows.)
const COAST_EDGES = [[1, 1, 0], [2, -1, 0], [4, 0, 1], [8, 0, -1]];   // bit, dx, dy (E,W,S,N)
const COAST_CORNERS = [[16, 0, 0], [32, 1, 0], [64, 1, 1], [128, 0, 1]];   // bit, cell-corner ux,uy (NW,NE,SE,SW)
function paintCoast(o, W, H, plots, x0, y0, tpp, terrPat) {
  const coastal = plots.filter(q => q.coast);
  if (!coastal.length) return;
  // ramp fades the land-extension detail out at low offscreen resolution (tpp), where a per-plot bump
  // would be a pixel or two of mush. Tracks offscreen resolution, NOT the on-screen zoom.
  const ramp = Math.max(0, Math.min(1, (tpp - 8) / 12));
  const bands = ctx2 => { for (const q of coastal) drawCoastBands(ctx2, (q.x - x0) * tpp, (q.y - y0) * tpp, tpp, q.coast); };
  // The coast is WATER (the shelf tile), so we don't touch the land — the coastal LAND cells PROTRUDE
  // into the adjacent coast water by a corner-continuous jittered depth. Shallows are painted first (in
  // the water), then the land bumps are laid on top with the plot's real terrain texture: the boundary
  // becomes a wavy land-into-water line, and the shore hue never lands on solid ground.
  const extend = () => { if (ramp > 0) for (const q of coastal) extendCoast(o, (q.x - x0) * tpp, (q.y - y0) * tpp, tpp, q, terrPat); };
  if (!shoreReady) { bands(o); extend(); return; }   // no ripple art → flat shore-hue bands
  // 1) shore-hue bands on a scratch layer (its alpha = the shallow-water shape)
  const cc = document.createElement("canvas"); cc.width = W; cc.height = H;
  bands(cc.getContext("2d"));
  // 2) the shore ripple, clipped to that shape — 8 plots per 128px tile → fine near-shore chop
  const rc = document.createElement("canvas"); rc.width = W; rc.height = H;
  const r = rc.getContext("2d"), pat = r.createPattern(shoreImg, "repeat");
  const sc = Math.max(0.25, tpp / 16);
  pat.setTransform(new DOMMatrix([sc, 0, 0, sc, 0, 0]));
  r.fillStyle = pat; r.fillRect(0, 0, W, H);
  r.globalCompositeOperation = "destination-in"; r.drawImage(cc, 0, 0);
  // 3) composite: shallows colour, ripple soft-light over it, then the land bumps ON TOP
  o.drawImage(cc, 0, 0);
  o.save(); o.globalCompositeOperation = "soft-light"; o.globalAlpha = 0.9; o.drawImage(rc, 0, 0); o.restore();
  extend();
}
// deterministic 0..1 hash — the same integer-mix idiom drawSeaIce uses, for jitter that is
// stable across redraws and seed-reproducible (no Math.random)
const chash = (a, b) => ((Math.imul(a | 0, 2654435761) ^ Math.imul(b | 0, 40503)) >>> 0) / 4294967295;
// How far the LAND protrudes into the coast water at a GLOBAL plot corner (0.05..0.42 cell). Keyed on
// the shared corner coords, so adjacent coastal cells read the SAME depth there — the extended outer
// edge is a continuous polyline across cells (a wavy shore), not per-cell rectangles.
function coastDepth(gx, gy, s) { return s * (0.18 + 0.45 * chash(gx, gy)); }
// The extension quads for a coastal cell — one per water edge, from the grid shoreline OUTWARD into the
// coast water, the two ends reaching by the shared corner depths. Filled with the plot's terrain, so a
// land bump juts into the shallows.
function coastExtendPolys(q, cx, cy, s) {
  const m = q.coast, out = [];
  if (m & 1) { const a = coastDepth(q.x + 1, q.y, s), b = coastDepth(q.x + 1, q.y + 1, s);   // E → +x
    out.push([[cx + s, cy], [cx + s + a, cy], [cx + s + b, cy + s], [cx + s, cy + s]]); }
  if (m & 2) { const a = coastDepth(q.x, q.y, s), b = coastDepth(q.x, q.y + 1, s);           // W → -x
    out.push([[cx, cy], [cx - a, cy], [cx - b, cy + s], [cx, cy + s]]); }
  if (m & 4) { const a = coastDepth(q.x, q.y + 1, s), b = coastDepth(q.x + 1, q.y + 1, s);   // S → +y
    out.push([[cx, cy + s], [cx, cy + s + a], [cx + s, cy + s + b], [cx + s, cy + s]]); }
  if (m & 8) { const a = coastDepth(q.x, q.y, s), b = coastDepth(q.x + 1, q.y, s);           // N → -y
    out.push([[cx, cy], [cx, cy - a], [cx + s, cy - b], [cx + s, cy]]); }
  return out;
}
function fillPolys(o, polys) {
  for (const p of polys) { o.beginPath(); o.moveTo(p[0][0], p[0][1]);
    for (let i = 1; i < p.length; i++) o.lineTo(p[i][0], p[i][1]); o.closePath(); o.fill(); }
}
// lay the land bumps into the coast water, filled with the plot's own terrain texture (or flat colour)
function extendCoast(o, cx, cy, s, q, terrPat) {
  const pp = terrPat && terrPat[q.terrain];
  if (pp) o.fillStyle = pp;
  else { const g = terrainRgb(q.terrain); o.fillStyle = `rgb(${g[0]},${g[1]},${g[2]})`; }
  fillPolys(o, coastExtendPolys(q, cx, cy, s));
}
// an outward fade of `col` from the shoreline into the sea — edges as linear ramps, diagonal
// corners as radial ones — reaching `f` px with peak alpha `a0`. Shared by the shallows and beach.
function outwardBands(o, cx, cy, s, mask, col, f, a0) {
  for (const [bit, dx, dy] of COAST_EDGES) {
    if (!(mask & bit)) continue;
    let gr, rx, ry, rw, rh;
    if (dx === 1)      { gr = o.createLinearGradient(cx + s, 0, cx + s + f, 0); rx = cx + s; ry = cy;     rw = f; rh = s; }  // E
    else if (dx === -1){ gr = o.createLinearGradient(cx, 0, cx - f, 0);         rx = cx - f; ry = cy;     rw = f; rh = s; }  // W
    else if (dy === 1) { gr = o.createLinearGradient(0, cy + s, 0, cy + s + f); rx = cx;     ry = cy + s; rw = s; rh = f; }  // S
    else               { gr = o.createLinearGradient(0, cy, 0, cy - f);         rx = cx;     ry = cy - f; rw = s; rh = f; }  // N
    gr.addColorStop(0, `rgba(${col},${a0})`); gr.addColorStop(1, `rgba(${col},0)`);
    o.fillStyle = gr; o.fillRect(rx, ry, rw, rh);
  }
  for (const [bit, ux, uy] of COAST_CORNERS) {
    if (!(mask & bit)) continue;
    const px = cx + ux * s, py = cy + uy * s;            // the plot's corner point
    const gr = o.createRadialGradient(px, py, 0, px, py, f);
    gr.addColorStop(0, `rgba(${col},${a0})`); gr.addColorStop(1, `rgba(${col},0)`);
    o.fillStyle = gr; o.fillRect(px - (ux ? 0 : f), py - (uy ? 0 : f), f, f);
  }
}
// the shallow-water band: the Civ4 shoreblend hue reaching ~1 cell out from the shoreline into the
// sea (its alpha is the shape the shore ripple is clipped to). The land bumps are drawn OVER this
// afterward, so the visible shallows ring sits just beyond the wavy shore.
function drawCoastBands(o, cx, cy, s, mask) {
  outwardBands(o, cx, cy, s, mask, SHORE_COL, s * 1.35, ".85");
}
// A river plot's segment: a water-textured ribbon from the cell centre out to each
// 4-neighbour that also carries a river (to the shared edge), or a source blob when it
// stands alone. The ribbon width tapers by the plot's authored river width — the low
// digit of the packed river code (q.river % 10; node markers read as width 1). Uses the
// baked water tile as a repeating pattern, falling back to the flat blue fill colour the
// map used before when that tile is unavailable (LFS not pulled / file://).
function drawRiver(o, cx, cy, s, q, grid, pat) {
  // links come from the packed adjacency mask (thousands digit: 1=E,2=W,4=S,8=N, in NB4 order),
  // which is global — so a river links to a neighbour in the ADJACENT province, not just this
  // one's grid. Fall back to the in-province grid when the mask is absent (older packs → 0).
  const adj = Math.floor(q.river / 1000) % 16;
  const isR = (d, i) => {
    if (adj & (1 << i)) return true;                                    // global mask: neighbour is a river (maybe cross-province)
    const n = grid.get((q.x + d[0]) * 1e5 + (q.y + d[1]));             // fallback for older packs (adj == 0)
    return !!(n && n.river);
  };
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
