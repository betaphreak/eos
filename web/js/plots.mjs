"use strict";
// The plot layer: the Civ4 ground-texture art, the per-province offscreen canvases built from it, and
// the draw pass that blits them. What used to also live here now has its own module — the shoreline
// (coast.mjs), the plot fetch (plotfetch.mjs), the resource icons (bonusicons.mjs), the movement-cost
// heat (cost.mjs), and the offscreen primitives all three share (plotcanvas.mjs).
import { P, terrainRgb, provSrcBox, K_PLOT, TT, RIVER, TREES, FEATURE_OVERLAYS, IMPROVEMENT_OVERLAYS, LY, NB4, cam, VIEW, ctx, px, py, pxr, pyr, S } from "./core.mjs";
import { draw } from "./main.mjs";
import { bandAlpha, kBand, atLeast, BAND } from "./bands.mjs";
import { loadArt, plotBounds, buildPixelCanvas, blitProvinceCanvas } from "./plotcanvas.mjs";
import { paintCoast, drawSeaIce } from "./coast.mjs";
import { drawBonusOverlay } from "./bonusicons.mjs";
import { loadPlots } from "./plotfetch.mjs";

// the Civ4 ground-texture atlas (sliced per-terrain into repeating tiles by extractTiles); null →
// drawPlots stays on the flat 1px/plot colour offscreen
let ttReady = false, ttTiles = null;
const ttImg = loadArt(TT, () => { extractTiles(); ttReady = true; });
// the baked water tile for the river ribbon (docs/river-rendering.md §2); null → drawRiver keeps the flat fill
let rvReady = false;
const rvImg = loadArt(RIVER, () => { rvReady = true; });
// the real Civ4 foliage sprite atlases (docs/features-art.md): {leafy,palm,swamp,…} strips of tree
// cutouts, one Image + ready flag per group; null → featureSprite keeps the procedural blobs. A
// late-loading atlas invalidates the cached province texture canvases (they baked procedural blobs
// before the art arrived) so they rebuild with the real foliage.
const treeImg = {}, treeReady = {};
if (TREES) for (const k of Object.keys(TREES))
  treeImg[k] = loadArt(TREES[k], () => { treeReady[k] = true; for (const p of P) p._tcanvas = null; });
// flat Civ6 strategic-view feature overlays (docs/civ6-art-replacement.md §D): one tile per Civ6-covered
// feature, blitted to fill a featured plot instead of scattering billboards. Deduped by src (FOREST +
// FOREST_ANCIENT share one image).
const foImg = {}, foReady = {};   // both keyed by FEATURE_*
if (FEATURE_OVERLAYS) { const imgBySrc = {};
  for (const k of Object.keys(FEATURE_OVERLAYS)) {
    const asset = FEATURE_OVERLAYS[k];
    if (!imgBySrc[asset.src]) imgBySrc[asset.src] = loadArt(asset, () => {
      for (const kk of Object.keys(FEATURE_OVERLAYS)) if (FEATURE_OVERLAYS[kk].src === asset.src) foReady[kk] = true;
      for (const p of P) p._tcanvas = null;
    });
    foImg[k] = imgBySrc[asset.src];
  }
}
// flat Civ6 strategic-view improvement overlays (docs/civ6-art-replacement.md §F): one 128² alpha sprite
// per Civ6-covered improvement (Farm/Mine/Quarry), blitted centred on an improved plot — like a feature
// overlay. Placement is DEFERRED: nothing carries an `improvement` yet (the engine doesn't emit one), so
// this layer is wired but draws nothing until per-plot placement lands. Keyed by IMPROVEMENT_*.
const impImg = {}, impReady = {};
if (IMPROVEMENT_OVERLAYS) {
  for (const k of Object.keys(IMPROVEMENT_OVERLAYS)) {
    impImg[k] = loadArt(IMPROVEMENT_OVERLAYS[k], () => { impReady[k] = true; for (const p of P) p._tcanvas = null; });
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
// Per-paint wall-clock budget for the heavy offscreen rasterisation (buildPlotTexCanvas /
// buildPlotCanvas). Those run once per province in the draw loop; when several fetches resolve in one
// batch (common after a slow load) building them all in a single frame froze the UI. Build until the
// budget is spent, then defer the rest to later frames (drawPlots reschedules a paint). At least one
// province always builds per frame, so it still converges quickly.
const PLOT_FRAME_BUDGET_MS = 6;
// Above this plot count, skip the (multi-pass, per-plot) textured build and use the cheap flat 1px/plot
// canvas instead — a single giant province (e.g. ~80k plots) would otherwise block for seconds on its
// one textured build, which the per-frame budget can't interrupt mid-build.
const MAX_TEX_PLOTS = 20000;

// Urban plots (docs/urban-plots.md): a city is now an OVERLAY on natural terrain, not a synthetic
// terrain — the plot cache (MAP_VERSION 8+) carries the generated ground plus a `urban` flag, so an
// urban plot renders as its real terrain and the `q.urban` flag (straight off the plot JSON) locates
// the city for the district layer (districts.mjs), routes (urban→paved) and the info panel. No client
// re-terraining is needed any more; the old TERRAIN_URBAN grey-ground substrate was retired engine-side.

// rasterise a province's plots to a 1px/plot offscreen canvas: terrain colour, relief
// shading (hill lighter, peak toward rock-grey), a light feature tint, and river blend
function buildPlotCanvas(p, plots) {
  // a sea/lake province's shelf plots render as flat water terrain (coast→sea depth ramp from the
  // terrain key); the land-only relief/feature/river tints below are skipped for them
  const water = p.type === "SEA" || p.type === "LAKE";
  const { canvas, box } = buildPixelCanvas(plots, (q, d, o) => {
    const c = terrainRgb(q.terrain); let r = c[0], g = c[1], b = c[2];
    if (!water) {
      const f = q.feature;
      if (f) {
        if (/FOREST|JUNGLE|WOOD/.test(f)) { r = r * 0.7 | 0; g = g * 0.82 + 16 | 0; b = b * 0.6 | 0; }
        else if (/SWAMP|MARSH|BOG/.test(f)) { r = r * 0.82 | 0; g = g * 0.86 | 0; b = b * 0.82 | 0; }
      }
      if (q.plotType === "HILL") { r = Math.min(255, r * 1.14 + 8) | 0; g = Math.min(255, g * 1.14 + 8) | 0; b = Math.min(255, b * 1.14 + 8) | 0; }
      else if (q.plotType === "PEAK") { r = (r + 150) / 2 | 0; g = (g + 152) / 2 | 0; b = (b + 158) / 2 | 0; }
      if (q.river) { r = r * 0.55 + 42 | 0; g = g * 0.55 + 60 | 0; b = b * 0.55 + 76 | 0; }   // muted blue-grey, not vivid cyan
    }
    d[o] = r; d[o + 1] = g; d[o + 2] = b; d[o + 3] = 255;
  });
  p._pcanvas = canvas; p._pbox = box;
}
// draw the plot layer for the provinces in view, fading in just past K_PLOT. Below
// K_TEX each province blits its flat-colour 1px/plot offscreen (cheap overview); past
// K_TEX (and not mid-pan) it draws real ground-texture tiles per plot.
// `only` (optional): a province predicate — draw just the provinces it accepts. The
// Underworld plane uses it to relight the cavern provinces' plots over its surface veil
// (see main.drawUnderworld); called with no argument it draws the whole world.
function drawPlots(only) {
  if (cam.k < K_PLOT) return;
  const textured = atLeast(BAND.TERRAIN) && ttReady && !S.dragging;   // real textures from band 4 (16×); flat tiles while panning
  const a = bandAlpha(kBand([K_PLOT, 6.5]));   // fade in over the plots band
  const smooth = ctx.imageSmoothingEnabled;
  ctx.globalAlpha = a;
  const vis = [];   // in-view provinces with plots loaded — reused by the bonus overlay (no 2nd P scan)
  const buildDeadline = performance.now() + PLOT_FRAME_BUDGET_MS;   // stop starting builds past this
  let deferred = false;
  for (const p of P) {
    if (only && !only(p)) continue;
    const bb = provSrcBox(p);
    let sx0, sy0, sx1, sy1;
    if (bb) { sx0 = pxr(bb.x0); sy0 = pyr(bb.y0); sx1 = pxr(bb.x1); sy1 = pyr(bb.y1); }
    else { const x = px(p.lon), y = py(p.lat); sx0 = x - 20; sy0 = y - 20; sx1 = x + 20; sy1 = y + 20; }
    if (sx1 < 0 || sy1 < 0 || sx0 > VIEW.w || sy0 > VIEW.h) continue;   // cull to viewport
    if (!p._plots) { loadPlots(p); continue; }   // request the server-generated grid on first sight
    if (!p._plots.length) continue;              // loaded-empty (deep ocean): nothing to draw
    vis.push(p);
    // giant provinces skip the heavy textured build (bounded worst case) and use the flat canvas
    if (textured && p._plots.length <= MAX_TEX_PLOTS) {
      if (!p._tcanvas) {
        if (performance.now() >= buildDeadline) {   // out of frame budget — flat placeholder now, texture next frame
          deferred = true;
          if (p._pcanvas) { ctx.imageSmoothingEnabled = false; blitProvinceCanvas(p._pcanvas, p._pbox); }
          continue;
        }
        buildPlotTexCanvas(p);                       // textured offscreen, built once
      }
      ctx.imageSmoothingEnabled = true;
      blitProvinceCanvas(p._tcanvas, p._tbox);
      continue;
    }
    if (!p._pcanvas) {
      if (performance.now() >= buildDeadline) { deferred = true; continue; }   // out of budget — build next frame
      buildPlotCanvas(p, p._plots);                  // flat-colour offscreen, built once
    }
    ctx.imageSmoothingEnabled = false;
    blitProvinceCanvas(p._pcanvas, p._pbox);
  }
  ctx.globalAlpha = 1; ctx.imageSmoothingEnabled = smooth;
  drawBonusOverlay(vis);   // resource icons: screen-space overlay over the in-view provinces only
  if (deferred) draw();    // keep each paint under budget — finish the remaining builds over the next frames
}
// A smooth grayscale noise tile (deterministic, built once): black RGB with a soft-blob ALPHA
// channel in ~[0.25,1]. Used to make the terrain edge/corner blend IRREGULAR instead of a clean
// linear ramp — multiplied into the blend mask so boundaries interleave organically, which is what
// kills the square-tile look at deep zoom (Civ4's alpha blend masks do the same). Low-res hash noise
// upscaled with smoothing → cloudy blobs; each plot samples a different sub-region so neighbours differ.
const BLEND_NOISE = (() => {
  const LO = 32, HI = 128;
  const lo = document.createElement("canvas"); lo.width = lo.height = LO;
  const lx = lo.getContext("2d"), im = lx.createImageData(LO, LO), d = im.data;
  for (let y = 0; y < LO; y++) for (let x = 0; x < LO; x++) {
    const s = Math.sin(x * 12.9898 + y * 78.233) * 43758.5453, v = s - Math.floor(s);   // deterministic hash [0,1)
    const i = (y * LO + x) * 4;
    // alpha kept in a HIGH band [0.55,1]: the noise only nibbles the feather edge irregular, it must
    // not gut the neighbour's coverage (a low floor made lone tiles read MORE square, not less)
    d[i] = d[i + 1] = d[i + 2] = 0; d[i + 3] = Math.round((0.55 + 0.45 * v) * 255);
  }
  lx.putImageData(im, 0, 0);
  const hi = document.createElement("canvas"); hi.width = hi.height = HI;
  const hx = hi.getContext("2d"); hx.imageSmoothingEnabled = true;
  hx.drawImage(lo, 0, 0, LO, LO, 0, 0, HI, HI);   // upscale → smooth blobs
  return hi;
})();
const NOISE_SUB = 40, NOISE_RANGE = 128 - NOISE_SUB;   // per-plot sample window into BLEND_NOISE
// a per-plot/edge offset into the noise so adjacent blends don't share the same irregular edge
const noiseOff = (qx, qy, d) => {
  const h = ((qx * 73856093) ^ (qy * 19349663) ^ ((d[0] + 2) * 10007) ^ ((d[1] + 2) * 20011)) >>> 0;
  return [h % NOISE_RANGE, (h >> 8) % NOISE_RANGE];
};

// rasterise a province's plots to a textured offscreen — each plot drawn as its Civ4
// ground-texture tile (from the atlas) at TPP px, plus relief/river overlays — built
// once and blitted scaled (so hover/pan redraws stay a single drawImage per province).
// TPP drops for very large provinces to bound the offscreen size.
function buildPlotTexCanvas(p) {
  let { x0, y0, x1, y1 } = plotBounds(p._plots);
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
  // a sea/lake province's plots are all water: they still get the flat terrain fill (stage 1) and
  // the soft same-layer edge blend (stage 2) — softening the coast→sea shelf ramp — but skip the
  // land-only snow/coast-shallows/feature/river stages (3-4). LAND and wasteland build the full ground.
  const water = p.type === "SEA" || p.type === "LAKE";
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
  const f = tpp * 0.85;
  // When a plot is big enough to read its texture (deep/city zoom), feather the neighbour's REAL
  // terrain tile across the edge instead of a flat colour: draw the tile into a per-plot temp,
  // mask it to a soft edge ramp with `destination-in`, and composite it over this plot's base. That
  // dissolves grass/plains/tundra boundaries the way Civ4's blend tiles do. At small tpp (a huge
  // province zoomed out) the seam is sub-pixel, so keep the cheap flat-colour feather there.
  const textured = tpp >= 12 && ttTiles;
  let eb = null, ebx = null;
  if (textured) { eb = document.createElement("canvas"); eb.width = tpp; eb.height = tpp; ebx = eb.getContext("2d"); }
  for (const q of p._plots) {
    const ql = LY[q.terrain] || 0, cx = (q.x - x0) * tpp, cy = (q.y - y0) * tpp;
    for (const d of NB4) {
      const n = grid.get((q.x + d[0]) * 1e5 + (q.y + d[1]));
      if (!n || n.terrain === q.terrain) continue;
      const nl = LY[n.terrain] || 0;
      // blend BOTH sides of every boundary so neither edge stays a hard line (Civ4 mutual blend):
      // a higher-layer neighbour bleeds strongly onto this lower plot, a lower one bleeds back strongly
      // enough to actually soften this higher plot's edge, equal layers meet in the middle.
      const a = nl > ql ? 0.95 : nl < ql ? 0.55 : 0.7;
      const tile = textured ? ttTiles[n.terrain] : null;
      if (tile) {
        // paint the neighbour's tile, mask it to a feather along the shared edge, blit over the plot
        ebx.globalCompositeOperation = "source-over"; ebx.clearRect(0, 0, tpp, tpp);
        ebx.drawImage(tile, 0, 0, tile.width, tile.height, 0, 0, tpp, tpp);
        let gm;
        if (d[0] === 1)       gm = ebx.createLinearGradient(tpp, 0, tpp - f, 0);
        else if (d[0] === -1) gm = ebx.createLinearGradient(0, 0, f, 0);
        else if (d[1] === 1)  gm = ebx.createLinearGradient(0, tpp, 0, tpp - f);
        else                  gm = ebx.createLinearGradient(0, 0, 0, f);
        gm.addColorStop(0, `rgba(0,0,0,${a})`); gm.addColorStop(1, "rgba(0,0,0,0)");
        ebx.globalCompositeOperation = "destination-in";
        ebx.fillStyle = gm; ebx.fillRect(0, 0, tpp, tpp);
        const [nx, ny] = noiseOff(q.x, q.y, d);      // multiply by smooth noise → irregular, non-square edge
        ebx.drawImage(BLEND_NOISE, nx, ny, NOISE_SUB, NOISE_SUB, 0, 0, tpp, tpp);
        o.drawImage(eb, cx, cy);
        continue;
      }
      const g = terrainRgb(n.terrain);            // fallback: flat-colour feather (small tpp / missing tile)
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
  // 2b) corner blend: the 4-edge pass leaves the diagonal gaps — where a plot's DIAGONAL neighbour
  // differs but both flanking orthogonal neighbours match, that corner stays a hard square notch.
  // Feather the diagonal neighbour's tile into the corner with a radial mask. Skipped when a flanking
  // orthogonal neighbour already shares that terrain (its edge blend covers the corner), and only when
  // the texture is big enough to read (same tpp gate + temp canvas as the edge pass).
  if (textured) {
    const NB4D = [[1, -1], [-1, -1], [1, 1], [-1, 1]];   // NE, NW, SE, SW
    const fc = tpp * 0.6;
    for (const q of p._plots) {
      const ql = LY[q.terrain] || 0, cx = (q.x - x0) * tpp, cy = (q.y - y0) * tpp;
      for (const d of NB4D) {
        const n = grid.get((q.x + d[0]) * 1e5 + (q.y + d[1]));
        if (!n || n.terrain === q.terrain) continue;
        const nl = LY[n.terrain] || 0;
        const e1 = grid.get((q.x + d[0]) * 1e5 + q.y);   // the two orthogonal neighbours flanking this corner
        const e2 = grid.get(q.x * 1e5 + (q.y + d[1]));
        if ((e1 && e1.terrain === n.terrain) || (e2 && e2.terrain === n.terrain)) continue;   // edge blend already covers it
        const tile = ttTiles[n.terrain];
        if (!tile) continue;
        const a = nl > ql ? 0.95 : nl < ql ? 0.55 : 0.7;   // mutual: soften both sides of the corner
        ebx.globalCompositeOperation = "source-over"; ebx.clearRect(0, 0, tpp, tpp);
        ebx.drawImage(tile, 0, 0, tile.width, tile.height, 0, 0, tpp, tpp);
        const vx = d[0] === 1 ? tpp : 0, vy = d[1] === 1 ? tpp : 0;
        const gm = ebx.createRadialGradient(vx, vy, 0, vx, vy, fc);
        gm.addColorStop(0, `rgba(0,0,0,${a})`); gm.addColorStop(1, "rgba(0,0,0,0)");
        ebx.globalCompositeOperation = "destination-in";
        ebx.fillStyle = gm; ebx.fillRect(0, 0, tpp, tpp);
        const [nx, ny] = noiseOff(q.x, q.y, d);      // irregular corner, same noise mask as the edges
        ebx.drawImage(BLEND_NOISE, nx, ny, NOISE_SUB, NOISE_SUB, 0, 0, tpp, tpp);
        o.drawImage(eb, cx, cy);
      }
    }
  }
  if (!water) {
  // 3) snow on the highest ground. (The elevation-normal hillshade that used to sit here was
  // removed: with EXAG amplifying the gentle continental heightmap, near-flat provinces — most of
  // the map — picked up a strong per-plot bright/dark checker that just read as square tiles. The
  // ground is now the flat Civ4 terrain texture; relief reads from the terrain/feature mix instead.)
  // built at 1px/plot then blitted UPSCALED with smoothing, so the white feathers between snowy and
  // bare plots (bilinear alpha ramp) instead of stamping a hard square on each high plot.
  {
    const sc = document.createElement("canvas"); sc.width = w; sc.height = h;
    const sxc = sc.getContext("2d"), sim = sxc.createImageData(w, h), sd = sim.data;
    let anySnow = false;
    for (let i = 0; i < w * h; i++) { sd[i * 4] = 232; sd[i * 4 + 1] = 238; sd[i * 4 + 2] = 247; }   // white; alpha 0 (RGB set so upscale has no dark halo)
    for (const q of p._plots) {
      const e = q.elevation | 0;
      if (e < 165) continue;
      anySnow = true;
      const oi = ((q.y - y0) * w + (q.x - x0)) * 4;
      sd[oi + 3] = Math.round(Math.min(0.6, (e - 165) / 50) * 255);
    }
    if (anySnow) { sxc.putImageData(sim, 0, 0); o.imageSmoothingEnabled = true; o.drawImage(sc, 0, 0, w, h, 0, 0, w * tpp, h * tpp); }
  }
  // 4) coast shallows: real Civ4 shore texture, drawn as one province-level pass so the ripple
  // blends over the whole shore region at once (paintCoast); then rivers, then features on top, so a
  // river reaching the sea sits over the shallows/foam rather than under them — and so tree foliage
  // sits over the river (two passes, not per-plot, so a tree always overlaps a neighbouring river cell)
  paintCoast(o, oc.width, oc.height, p._plots, x0, y0, tpp);
  // desaturate the river ribbon so water recedes into the landscape instead of gridding vivid cyan
  // over it — baked once into the cached province canvas, so it costs nothing per frame
  o.filter = "saturate(0.7) brightness(0.94)";
  for (const q of p._plots) {
    if (q.river) { const cx = (q.x - x0) * tpp, cy = (q.y - y0) * tpp; drawRiver(o, cx, cy, tpp, q, grid, riverPat); }
  }
  o.filter = "none";
  for (const q of p._plots) {
    if (q.feature) { const cx = (q.x - x0) * tpp, cy = (q.y - y0) * tpp; featureSprite(o, cx, cy, tpp, q.feature, q.x, q.y); }
  }
  // improvements: a flat Civ6 SV overlay (farm/mine/quarry) over each improved plot, on top of the
  // ground + feature. No-op today — nothing carries an `improvement` yet (placement deferred).
  for (const q of p._plots) {
    if (q.improvement) { const cx = (q.x - x0) * tpp, cy = (q.y - y0) * tpp; improvementSprite(o, cx, cy, tpp, q.improvement, q.x, q.y); }
  }
  // (city cores are re-terrained to their countryside in markUrbanPlots; a subtle screen-space
  // marker in city.mjs keeps them locatable — the old Civ4 city sprite was pulled, see there.)
  } // end land-only ground stages
  if (water) drawSeaIce(o, p._plots, x0, y0, tpp);   // polar sea ice on the shelf water plots
  p._tcanvas = oc; p._tbox = { x0, y0, w, h }; p._grid = grid;   // grid: q.x*1e5+q.y → plot, for the resource tooltip
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
  o.save();
  // FULL-TILE water: fill the whole river cell with the Civ6 river-water texture, so its pattern reads
  // (a thin stroke only showed a sliver — the "texture doesn't fit" report). Adjacent river cells are
  // contiguous, so a chain reads as one continuous waterway. See docs/river-rendering.md.
  o.fillStyle = pat || "rgba(74,124,170,1)";
  o.globalAlpha = pat ? 0.92 : 0.6;
  o.fillRect(cx, cy, s, s);
  // SHORE: on each land-facing edge (not shared with another river cell), a lighter shallow band fades
  // INWARD from the bank into the deeper centre — the river's shallows, mirroring the sea coast — plus a
  // thin dark wet line right at the bank. A shared river edge stays open so the waterway is unbroken.
  const f = s * 0.42;                          // shallow reach into the cell
  o.globalAlpha = 1;
  for (let i = 0; i < 4; i++) {
    if (isR(NB4[i], i)) continue;              // open water toward another river cell
    let gr, rx, ry, rw, rh;
    if (i === 0)      { gr = o.createLinearGradient(cx + s, 0, cx + s - f, 0); rx = cx + s - f; ry = cy;         rw = f; rh = s; }  // E
    else if (i === 1) { gr = o.createLinearGradient(cx, 0, cx + f, 0);         rx = cx;         ry = cy;         rw = f; rh = s; }  // W
    else if (i === 2) { gr = o.createLinearGradient(0, cy + s, 0, cy + s - f); rx = cx;         ry = cy + s - f; rw = s; rh = f; }  // S
    else              { gr = o.createLinearGradient(0, cy, 0, cy + f);         rx = cx;         ry = cy;         rw = s; rh = f; }  // N
    gr.addColorStop(0, "rgba(150,198,224,0.8)"); gr.addColorStop(1, "rgba(150,198,224,0)");
    o.fillStyle = gr; o.fillRect(rx, ry, rw, rh);
  }
  const edge = [                               // the bank line on each land-facing side (NB4 [E,W,S,N])
    [cx + s, cy, cx + s, cy + s], [cx, cy, cx, cy + s],
    [cx, cy + s, cx + s, cy + s], [cx, cy, cx + s, cy],
  ];
  o.globalAlpha = 0.4; o.strokeStyle = "rgba(30,50,44,1)"; o.lineWidth = Math.max(1, s * 0.06); o.lineCap = "round";
  o.beginPath();
  for (let i = 0; i < 4; i++) if (!isR(NB4[i], i)) { o.moveTo(edge[i][0], edge[i][1]); o.lineTo(edge[i][2], edge[i][3]); }
  o.stroke();
  o.restore();
}
// small deterministic RNG seeded by a plot's coords, so feature sprites are stable
function mkRng(seed) { let s = seed >>> 0 || 1; return () => { s = (s * 1664525 + 1013904223) >>> 0; return s / 4294967296; }; }
// feature → real Civ4 foliage sprite atlas + density/scale, or null for a feature with no
// atlas (drawn as bare terrain). CACTUS and VERY_TALL_GRASS have no billboard imposter in
// the Civ4 art, so tools/nifbake renders their 3D .nif models to sprite sheets at build
// time (docs/features-art.md); the rest come from the *_1024.dds billboards.
function treeGroupFor(feature) {
  if (/JUNGLE|RAINFOREST/.test(feature))         return { key: "leafy",  lo: 3, hi: 5, scale: 0.60 };  // dense
  if (/SWAMP|BOG|MARSH|WETLAND/.test(feature))   return { key: "swamp",  lo: 2, hi: 3, scale: 0.44 };
  if (/SAVANNA/.test(feature))                   return { key: "palm",   lo: 1, hi: 2, scale: 0.6 };   // sparse
  if (/OASIS/.test(feature))                     return { key: "palm",   lo: 1, hi: 2, scale: 0.55 };
  if (/CACTUS|KAKTUS/.test(feature))             return { key: "cactus", lo: 1, hi: 2, scale: 0.55 };  // real Civ4 cactus (nif)
  if (/BAMBOO/.test(feature))                    return { key: "bamboo", lo: 2, hi: 3, scale: 0.55 };
  // VERY_TALL_GRASS/SWORD_GRASS/TALL_GRASS is handled procedurally (stampGrass), before treeGroupFor.
  if (/FOREST|WOOD|TAIGA|MANGROVE/.test(feature)) return { key: "leafy",  lo: 2, hi: 4, scale: 0.55 };
  return null;
}
// stamp real Civ4 tree cutouts into a plot: N sprites at jittered positions, back-to-front, each sized
// to the plot. Returns false when the group's atlas isn't loaded (caller falls back to procedural).
function stampTrees(o, cx, cy, s, g, rng) {
  const meta = TREES && TREES[g.key], img = treeImg[g.key];
  if (!meta || !treeReady[g.key]) return false;
  const n = g.lo + (rng() * (g.hi - g.lo + 1) | 0), items = [];
  for (let i = 0; i < n; i++) {
    const sp = meta.sprites[rng() * meta.sprites.length | 0];
    const th = s * g.scale * (0.82 + 0.36 * rng()), tw = th * sp[2] / sp[3];
    items.push({ sp, tw, th, px: cx + s * (0.16 + 0.68 * rng()), py: cy + s * (0.22 + 0.6 * rng()) });
  }
  items.sort((a, b) => a.py - b.py);                    // nearer (lower) trees drawn last → natural overlap
  for (const it of items) o.drawImage(img, it.sp[0], it.sp[1], it.sp[2], it.sp[3], it.px - it.tw / 2, it.py - it.th / 2, it.tw, it.th);
  return true;
}
function featureSprite(o, cx, cy, s, feature, sx, sy) {
  // Civ6-covered features (forest, jungle, marsh/swamp, oasis) draw as a flat Civ6 SV overlay filling
  // the plot; per-plot horizontal flip breaks the tiling. C2C-only flora (bamboo/cactus/tall-grass/
  // savanna) keeps the scattered Civ4 billboards. FLOOD_PLAINS (a ground quality) draws nothing.
  if (foImg[feature] && foReady[feature]) {
    if ((sx ^ sy) & 1) { o.save(); o.translate(cx + s, cy); o.scale(-1, 1); o.drawImage(foImg[feature], 0, 0, s, s); o.restore(); }
    else o.drawImage(foImg[feature], cx, cy, s, s);
    return;
  }
  const rng = mkRng((sx * 73856093) ^ (sy * 19349663));
  // tall grass has no good billboard (the C2C sword-grass sprite was a muddy wheat crop), so draw it
  // procedurally: a few clumps of thin curved blades. Clean, varied, no ugly texture.
  if (/VERY_TALL_GRASS|SWORD_GRASS|TALL_GRASS/.test(feature)) { stampGrass(o, cx, cy, s, rng); return; }
  const g = treeGroupFor(feature);
  if (!g) return;
  stampTrees(o, cx, cy, s, g, rng);              // real foliage sprites; nothing if not yet loaded
}
// Procedural tall-grass: N clumps of a few thin, curved, tapering blades in varied greens — a clean
// savanna tuft in place of the muddy sword-grass billboard. Deterministic via the plot rng.
function stampGrass(o, cx, cy, s, rng) {
  const clumps = 3 + (rng() * 3 | 0);            // 3–5 clumps per plot
  o.save();
  o.lineCap = "round";
  for (let c = 0; c < clumps; c++) {
    const bx = cx + s * (0.12 + 0.76 * rng()), by = cy + s * (0.5 + 0.45 * rng());   // clump base
    const h = s * (0.15 + 0.13 * rng());          // clump height (shorter than before)
    const g = 108 + (rng() * 46 | 0);             // muted green value 108–154
    o.strokeStyle = `rgb(${(g * 0.52) | 0},${(g * 0.82) | 0},${(g * 0.34) | 0})`;   // olive / forest, not lime
    o.lineWidth = Math.max(0.5, s * 0.02);
    const blades = 3 + (rng() * 3 | 0);
    for (let b = 0; b < blades; b++) {
      const bx0 = bx + (blades > 1 ? b / (blades - 1) - 0.5 : 0) * s * 0.13;   // spread the bases
      const lean = (rng() - 0.5) * s * 0.13;                                    // each blade leans its own way
      o.beginPath();
      o.moveTo(bx0, by);
      o.quadraticCurveTo(bx0 + lean * 0.5, by - h * 0.55, bx0 + lean, by - h * (0.8 + 0.4 * rng()));
      o.stroke();
    }
  }
  o.restore();
}
// A flat Civ6 SV improvement overlay (farm/mine/quarry) centred on an improved plot. A 128² alpha sprite
// blitted to fill the plot; per-plot horizontal flip breaks the tiling like the feature overlays. Nothing
// draws if the art isn't loaded, the improvement is uncovered by Civ6, or (today) the plot has no
// improvement at all — placement is deferred (docs/civ6-art-replacement.md §F).
function improvementSprite(o, cx, cy, s, improvement, sx, sy) {
  if (!impImg[improvement] || !impReady[improvement]) return;
  if ((sx ^ sy) & 1) { o.save(); o.translate(cx + s, cy); o.scale(-1, 1); o.drawImage(impImg[improvement], 0, 0, s, s); o.restore(); }
  else o.drawImage(impImg[improvement], cx, cy, s, s);
}
export { drawPlots };
