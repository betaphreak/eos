import { BUNDLE, P, TCOL, terrainRgb, provSrcBox, provOnScreen, apiUrl, K_PLOT, K_TEX, K_MAX, TT, RIVER, SHORE, ICE_ART, BONUS_ICONS, TRADE_GOODS, TREES, SEA_BANDS, LY, NB4, cam, VIEW, ctx, px, py, pxr, pyr, lerp, S } from "./core.mjs";
import { draw } from "./main.mjs";
import { renderRail } from "./panel.mjs";
// Load a baked art image once: on load run `onReady` (flip its ready flag / invalidate caches) and
// repaint. Returns the Image, or null when the asset is absent from the bundle (LFS not pulled /
// file:// build) so each caller keeps its procedural fallback.
function loadArt(asset, onReady) {
  if (!asset) return null;
  const img = new Image();
  img.onload = () => { onReady(); draw(); };
  img.src = asset.src;
  return img;
}
// the Civ4 ground-texture atlas (sliced per-terrain into repeating tiles by extractTiles); null →
// drawPlots stays on the flat 1px/plot colour offscreen
let ttReady = false, ttTiles = null;
const ttImg = loadArt(TT, () => { extractTiles(); ttReady = true; });
// the baked water tile for the river ribbon (docs/river-rendering.md §2); null → drawRiver keeps the flat fill
let rvReady = false;
const rvImg = loadArt(RIVER, () => { rvReady = true; });
// the baked greyscale shore-wave tile for the coast shallows (docs/coastlines.md Phase D); null →
// the shallows stay flat-tinted, no ripple
let shoreReady = false;
const shoreImg = loadArt(SHORE, () => { shoreReady = true; });
// the real Civ4 pack-ice tile (docs/coastlines.md Phase G), features/icepack; null → drawSeaIce
// falls back to flat pale floes
let iceReady = false, icePat = null;
const iceImg = loadArt(ICE_ART, () => { iceReady = true; });
// the shallows tint — the Civ4 shoreblend hue baked into the bundle, or the old teal fallback
const SHORE_COL = (SEA_BANDS && SEA_BANDS.shore) ? SEA_BANDS.shore.join(",") : "116,178,196";
// beach sand — dry sand feathered back onto the coastal land, wet sand at the water's edge
const SAND = "226,208,164", WET_SAND = "200,182,140";
// the real Civ4 resource-icon atlas (docs/bonus-sprite-bake.md), sliced from GameFont.tga; null →
// drawBonusOverlay keeps the procedural category glyphs
let biReady = false;
const biImg = loadArt(BONUS_ICONS, () => { biReady = true; });
// the Anbennar trade-good icon atlas (docs/trade-goods.md): ONE icon per PROVINCE (the province-level
// resource), distinct from the per-PLOT bonus atlas above. null → no province good icons.
let tgReady = false;
const tgImg = loadArt(TRADE_GOODS && TRADE_GOODS.icons, () => { tgReady = true; });
// the real Civ4 foliage sprite atlases (docs/features-art.md): {leafy,palm,swamp,…} strips of tree
// cutouts, one Image + ready flag per group; null → featureSprite keeps the procedural blobs. A
// late-loading atlas invalidates the cached province texture canvases (they baked procedural blobs
// before the art arrived) so they rebuild with the real foliage.
const treeImg = {}, treeReady = {};
if (TREES) for (const k of Object.keys(TREES))
  treeImg[k] = loadArt(TREES[k], () => { treeReady[k] = true; for (const p of P) p._tcanvas = null; });
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
// lazy-load a province's plot grid: fetch it from the server (GET /api/plots/{id}), which
// generates the canonical field on demand and caches it (docs/plot-serving.md); the body is the
// province's gzipped JSON, gunzipped here. Then rasterise and redraw. An empty array (the ~176
// deep-ocean provinces with no shelf) or any failure leaves it as the blurred raster / open sea.
async function loadPlots(p) {
  if (p._loading || p._plots) return;
  p._loading = true;
  try {
    // ?v=<plotVersion> versions the immutable cache: a generation change (server bumps
    // ProvincePlotStore.GEN_VERSION, shipped as BUNDLE.plotVersion) changes the URL, so the browser
    // fetches the fresh grid instead of a stale cached one. See docs/plot-serving.md.
    const res = await fetch(apiUrl("/api/plots/" + p.id) + "?v=" + (BUNDLE.plotVersion || 0));
    if (!res.ok) throw new Error("plots " + res.status);   // 404 off-map, 5xx, …
    const stream = res.body.pipeThrough(new DecompressionStream("gzip"));
    const arr = JSON.parse(await new Response(stream).text());
    p._loading = false;
    // mark as loaded even when empty (deep ocean), so the draw loop and panel stop re-requesting
    p._plots = arr || [];
    if (p._plots.length) draw();
    if (S.selectedProv === p) renderRail();
  } catch (e) {
    // the per-plot terrain feed failed for THIS province — degrade gracefully (leave the blurred
    // raster) and mark it loaded-empty so the draw loop doesn't re-request it. Do NOT tear down the
    // session: one province's plots failing is not a dead server (the bundle/SSE surface that).
    p._loading = false; p._plots = [];
  }
}
// min/max plot coords of a province's grid plus the derived offscreen size (one pixel per plot).
// Shared by every per-province offscreen builder.
function plotBounds(plots) {
  let x0 = 1e9, y0 = 1e9, x1 = -1e9, y1 = -1e9;
  for (const q of plots) { if (q.x < x0) x0 = q.x; if (q.x > x1) x1 = q.x; if (q.y < y0) y0 = q.y; if (q.y > y1) y1 = q.y; }
  return { x0, y0, x1, y1, w: x1 - x0 + 1, h: y1 - y0 + 1 };
}
// rasterise a province's plots to a 1px/plot offscreen: `perPlot(q, d, o)` writes plot `q`'s RGBA at
// byte offset `o` into the pixel buffer `d` (cells with no plot stay transparent). Returns
// {canvas, box:{x0,y0,w,h}} — the box maps the offscreen back to source-pixel plot space for blitting.
function buildPixelCanvas(plots, perPlot) {
  const { x0, y0, w, h } = plotBounds(plots);
  const oc = document.createElement("canvas"); oc.width = w; oc.height = h;
  const octx = oc.getContext("2d"), im = octx.createImageData(w, h), d = im.data;
  for (const q of plots) perPlot(q, d, ((q.y - y0) * w + (q.x - x0)) * 4);
  octx.putImageData(im, 0, 0);
  return { canvas: oc, box: { x0, y0, w, h } };
}
// blit a province offscreen (built in source-pixel plot space, box = {x0,y0,w,h}) to the screen at
// the current camera — the scaled drawImage keeps hover/pan redraws to one call per province.
function blitProvinceCanvas(canvas, box) {
  const dX = pxr(box.x0), dY = pyr(box.y0);
  ctx.drawImage(canvas, dX, dY, pxr(box.x0 + box.w) - dX, pyr(box.y0 + box.h) - dY);
}
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
      if (q.river) { r = r * 0.45 + 33 | 0; g = g * 0.45 + 61 | 0; b = b * 0.45 + 91 | 0; }
    }
    d[o] = r; d[o + 1] = g; d[o + 2] = b; d[o + 3] = 255;
  });
  p._pcanvas = canvas; p._pbox = box;
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
  const grid = new Map();
  for (const q of plots) grid.set(q.x * 1e5 + q.y, q);
  const { canvas, box } = buildPixelCanvas(plots, (q, d, o) => {
    const e = q.elevation | 0; let md = 0;
    for (const nb of NB4) { const n = grid.get((q.x + nb[0]) * 1e5 + (q.y + nb[1])); if (n) { const dd = Math.abs((n.elevation | 0) - e); if (dd > md) md = dd; } }
    const f = costFactor(md);
    if (f < 1.03) { d[o + 3] = 0; return; }                 // effectively flat → clear
    const c = costColor(f);
    d[o] = c[0]; d[o + 1] = c[1]; d[o + 2] = c[2]; d[o + 3] = (255 * (0.12 + 0.6 * c[3])) | 0;
  });
  p._mcanvas = canvas; p._mbox = box;
}
// blit the cost overlay for the provinces in view (same cull/scale as drawPlots), when the
// toggle is on and we are zoomed into the plot layer. Drawn over the terrain, under outlines.
function drawCostOverlay() {
  if (!S.showCost || cam.k < K_PLOT) return;
  const a = Math.min(1, (cam.k - K_PLOT) / 1.5);
  const smooth = ctx.imageSmoothingEnabled;
  ctx.globalAlpha = a; ctx.imageSmoothingEnabled = true;
  for (const p of P) {
    if (!p._plots || !p._plots.length) continue;            // unloaded (drawPlots requests it) or empty
    const bb = provSrcBox(p); if (!bb) continue;
    const sx0 = pxr(bb.x0), sy0 = pyr(bb.y0), sx1 = pxr(bb.x1), sy1 = pyr(bb.y1);
    if (sx1 < 0 || sy1 < 0 || sx0 > VIEW.w || sy0 > VIEW.h) continue;
    if (!p._mcanvas) buildCostCanvas(p, p._plots);
    blitProvinceCanvas(p._mcanvas, p._mbox);
  }
  ctx.globalAlpha = 1; ctx.imageSmoothingEnabled = smooth;
}
// draw the plot layer for the provinces in view, fading in just past K_PLOT. Below
// K_TEX each province blits its flat-colour 1px/plot offscreen (cheap overview); past
// K_TEX (and not mid-pan) it draws real ground-texture tiles per plot.
// `only` (optional): a province predicate — draw just the provinces it accepts. The
// Underworld plane uses it to relight the cavern provinces' plots over its surface veil
// (see main.drawUnderworld); called with no argument it draws the whole world.
function drawPlots(only) {
  if (cam.k < K_PLOT) return;
  const textured = cam.k >= K_TEX && ttReady && !S.dragging;   // flat tiles while panning (cheap)
  const a = Math.min(1, (cam.k - K_PLOT) / 1.5);
  const smooth = ctx.imageSmoothingEnabled;
  ctx.globalAlpha = a;
  const vis = [];   // in-view provinces with plots loaded — reused by the bonus overlay (no 2nd P scan)
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
    if (textured) {
      if (!p._tcanvas) buildPlotTexCanvas(p);                 // textured offscreen, built once
      ctx.imageSmoothingEnabled = true;
      blitProvinceCanvas(p._tcanvas, p._tbox);
      continue;
    }
    if (!p._pcanvas) buildPlotCanvas(p, p._plots);            // flat-colour offscreen, built once
    ctx.imageSmoothingEnabled = false;
    blitProvinceCanvas(p._pcanvas, p._pbox);
  }
  ctx.globalAlpha = 1; ctx.imageSmoothingEnabled = smooth;
  drawBonusOverlay(vis);   // resource icons: screen-space overlay over the in-view provinces only
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
  paintCoast(o, oc.width, oc.height, p._plots, x0, y0, tpp, pat);
  for (const q of p._plots) {
    if (q.river) { const cx = (q.x - x0) * tpp, cy = (q.y - y0) * tpp; drawRiver(o, cx, cy, tpp, q, grid, riverPat); }
  }
  for (const q of p._plots) {
    if (q.feature) { const cx = (q.x - x0) * tpp, cy = (q.y - y0) * tpp; featureSprite(o, cx, cy, tpp, q.feature, q.x, q.y); }
  }
  // the city: a Civ4 city sprite over each urban core plot, sized by province development
  for (const q of p._plots) {
    if (q.terrain === "TERRAIN_URBAN") { const cx = (q.x - x0) * tpp, cy = (q.y - y0) * tpp; citySprite(o, cx, cy, tpp, p.dev); }
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
function drawBonusOverlay(vis) {
  if (cam.k <= BONUS_HIDE_AT || !vis.length) return;
  const plotPx = pxr(1) - pxr(0);                      // one plot's on-screen size (tracks zoom AND viewport)
  const size = bonusIconSize();
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
// the on-screen rect [x0,y0,x1,y1] of a plot's resource icon (primary world copy), or null when icons
// are hidden or the plot has no bonus — MUST match drawBonusOverlay's geometry. Used by the hover
// tooltip so pointing at the big bottom-left-anchored glyph hits its owning plot, not the cell under it.
export function bonusIconRect(q) {
  if (cam.k <= BONUS_HIDE_AT || !q || !q.bonus) return null;
  const plotPx = pxr(1) - pxr(0), size = bonusIconSize(), inset = Math.max(0.5, plotPx * 0.06);
  const x = pxr(q.x) + inset, y = pyr(q.y + 1) - inset - size;
  return [x, y, x + size, y + size];
}

// bonus-icon on-screen size: the atlas cell drawn at 100% (native px) at the deepest zoom (K_MAX =
// 256×), scaling down proportionally below — so a resource icon reads at a fixed, readable size
// instead of ballooning with the plot as you zoom in. ctx is dpr-scaled, so cell px == CSS px.
function bonusIconSize() {
  return (BONUS_ICONS ? BONUS_ICONS.cell : 24) * cam.k / K_MAX;
}

// Stamp each in-view province's TRADE-GOOD icon at its centroid — the province-level resource, drawn
// like the per-plot bonuses are but one per province (docs/trade-goods.md). Lives in a zoom BAND: it
// appears at the terrain-texture zoom (K_TEX, 16×) — the overview and mid zoom stay clean — and fades
// out as you dive toward plot detail, where the finer per-plot bonus icons take over the resource
// story. Overworld physical view only (gated by the caller in main.renderScene).
const TG_MIN_PROV_PX = 16;    // skip only tiny slivers (at 16×+ most provinces are well above this)
const TG_ICON_PX = 26;        // on-screen icon size, in CSS px (ctx is dpr-scaled)
const TG_FADE_START = 48, TG_FADE_END = 64;   // fade out over this zoom range as plot bonuses take over
export function drawTradeGoodIcons() {
  if (!TRADE_GOODS || !TRADE_GOODS.icons || !tgReady) return;
  if (cam.k < K_TEX) return;                     // only from the terrain-texture zoom (16×) upward
  // fade out as you approach plot-detail zoom, where the per-plot bonus icons carry the resources
  const fade = 1 - Math.max(0, Math.min(1, (cam.k - TG_FADE_START) / (TG_FADE_END - TG_FADE_START)));
  if (fade <= 0.01) return;
  const { cell, cols, index } = TRADE_GOODS.icons, prov = TRADE_GOODS.prov;
  const size = TG_ICON_PX, r = size / 2;
  ctx.save();
  ctx.imageSmoothingEnabled = true;
  ctx.globalAlpha = fade;
  for (const p of P) {
    if (!p.rings) continue;
    const key = prov[p.id];
    if (!key) continue;
    const idx = index[key];
    if (idx === undefined || !provOnScreen(p)) continue;
    const box = provSrcBox(p);
    if (!box) continue;
    const w = Math.abs(pxr(box.x1) - pxr(box.x0)), h = Math.abs(pyr(box.y1) - pyr(box.y0));
    if (Math.min(w, h) < TG_MIN_PROV_PX) continue;          // too small on screen → skip (declutter)
    const cx = px(p.lon), cy = py(p.lat);
    // a soft dark disc so the icon reads on any terrain/colour
    ctx.beginPath();
    ctx.arc(cx, cy, r * 0.92, 0, 7);
    ctx.fillStyle = "rgba(10,14,22,0.5)";
    ctx.fill();
    ctx.drawImage(tgImg, (idx % cols) * cell, Math.floor(idx / cols) * cell, cell, cell,
      cx - r, cy - r, size, size);
  }
  ctx.restore();
}
// Polar sea ice on a water province's shelf (docs/coastlines.md Phase E/G). Coverage is per-cell
// (sparse at sub-polar latitudes, near-solid by the pole), so drawing cells as SQUARES read as a
// blocky checkerboard. Instead each ice cell is a slightly-oversized ROUNDED FLOE blob unioned into
// one field: isolated cells become round pancake floes (natural drift ice), and where cells crowd
// together the blobs overlap into a solid sheet with a rounded, ragged margin. A cool rim shows only
// on the outer boundary (an expanded field drawn under the floes). Degrades to a flat pale sheet
// when the ice tile isn't loaded.
function drawSeaIce(o, plots, x0, y0, tpp) {
  const ice = plots.filter(q => q.feature === "FEATURE_ICE");
  if (!ice.length) return;
  const hash = (x, y) => ((Math.imul(x | 0, 374761393) ^ Math.imul(y | 0, 668265263)) >>> 0) / 4294967295;
  if (iceReady) { icePat = icePat || o.createPattern(iceImg, "repeat");
    const s = Math.max(0.25, tpp * 4 / ICE_ART.tile); icePat.setTransform(new DOMMatrix([s, 0, 0, s, 0, 0])); }
  const rw = tpp * 0.05;                                // rim width (the cool floe edge)
  const rim = new Path2D(), field = new Path2D();
  for (const q of ice) {
    const cx = (q.x - x0) * tpp + tpp / 2, cy = (q.y - y0) * tpp + tpp / 2;
    // radius < 0.5·tpp so floes stay discrete islands with open water between them (rather than
    // overlapping into a solid sheet of big white discs); jittered per-cell so outlines vary
    const r = tpp * (0.34 + 0.12 * hash(q.x * 7 + 1, q.y * 7 + 3));
    rim.moveTo(cx + r + rw, cy); rim.arc(cx, cy, r + rw, 0, Math.PI * 2);
    field.moveTo(cx + r, cy); field.arc(cx, cy, r, 0, Math.PI * 2);
  }
  o.save();
  o.fillStyle = "rgba(150,178,198,0.12)"; o.fill(rim);       // cool rim shows only past the floe edge
  o.globalAlpha = 0.2; o.fillStyle = icePat || "rgb(226,236,245)"; o.fill(field);   // ~80% transparent — the sea reads through the floes
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
  // The coast is WATER (the shelf tile), so we don't touch the land — the coastal LAND cells grow a
  // SAND BEACH that protrudes into the shallows by a corner-continuous jittered depth (a smooth wavy
  // sand line across cells, not a grid staircase) and feathers back onto the land. Shallows are painted
  // first (in the water), then the beach on top: land → dry sand → wet sand → shallows → sea.
  const beach = () => { if (ramp > 0) for (const q of coastal) drawBeach(o, (q.x - x0) * tpp, (q.y - y0) * tpp, tpp, q); };
  // a soft foam lap just seaward of the sand (repurposes the retired foam crest)
  const foam = () => { if (ramp > 0) for (const q of coastal) drawFoam(o, (q.x - x0) * tpp, (q.y - y0) * tpp, tpp, q.coast); };
  if (!shoreReady) { bands(o); beach(); foam(); return; }   // no ripple art → flat shore-hue bands
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
  // 3) composite: shallows colour, ripple soft-light over it, then the sand beach ON TOP
  o.drawImage(cc, 0, 0);
  o.save(); o.globalCompositeOperation = "soft-light"; o.globalAlpha = 0.9; o.drawImage(rc, 0, 0); o.restore();
  beach();
  foam();
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
// an INWARD fade of `col` from the shoreline back into the LAND cell — the mirror of
// outwardBands. Used for the dry-sand beach apron feathering off the water's edge onto land.
function inwardBands(o, cx, cy, s, mask, col, f, a0) {
  for (const [bit, dx, dy] of COAST_EDGES) {
    if (!(mask & bit)) continue;
    let gr, rx, ry, rw, rh;
    if (dx === 1)      { gr = o.createLinearGradient(cx + s, 0, cx + s - f, 0); rx = cx + s - f; ry = cy;         rw = f; rh = s; }  // sea E → sand on the land's east strip
    else if (dx === -1){ gr = o.createLinearGradient(cx, 0, cx + f, 0);         rx = cx;         ry = cy;         rw = f; rh = s; }  // W
    else if (dy === 1) { gr = o.createLinearGradient(0, cy + s, 0, cy + s - f); rx = cx;         ry = cy + s - f; rw = s; rh = f; }  // S
    else               { gr = o.createLinearGradient(0, cy, 0, cy + f);         rx = cx;         ry = cy;         rw = s; rh = f; }  // N
    gr.addColorStop(0, `rgba(${col},${a0})`); gr.addColorStop(1, `rgba(${col},0)`);
    o.fillStyle = gr; o.fillRect(rx, ry, rw, rh);
  }
  for (const [bit, ux, uy] of COAST_CORNERS) {                 // round the sand into the cell at outer corners
    if (!(mask & bit)) continue;
    const px = cx + ux * s, py = cy + uy * s;
    const gr = o.createRadialGradient(px, py, 0, px, py, f);
    gr.addColorStop(0, `rgba(${col},${a0})`); gr.addColorStop(1, `rgba(${col},0)`);
    o.fillStyle = gr; o.fillRect(px - ux * f, py - uy * f, f, f);
  }
}
// The beach on a coastal LAND cell: wet-sand bumps protruding into the shallows (the same
// corner-continuous outline the land used, so the sand edge is a smooth wavy polyline across
// cells, not a staircase), then dry sand feathered back onto the land. Replaces the old
// terrain-coloured land bumps — the Civ4 sandy shore. See docs/coastlines.md.
function drawBeach(o, cx, cy, s, q) {
  o.fillStyle = `rgb(${WET_SAND})`;
  fillPolys(o, coastExtendPolys(q, cx, cy, s));               // wet sand juts into the water
  inwardBands(o, cx, cy, s, q.coast, SAND, s * 0.62, ".95");  // dry sand feathers back onto land
}
// A thin foam lap right at the water's edge: a soft white feather fading seaward, drawn just
// outside the sand. (The real Civ4 wave-crest art this once used was retired — it never read
// cleanly at these zooms — so the procedural feather is now the only path.)
function drawFoam(o, cx, cy, s, mask) {
  outwardBands(o, cx, cy, s, mask, "255,255,255", s * 0.3, ".5");
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
  if (/VERY_TALL_GRASS|SWORD_GRASS|TALL_GRASS/.test(feature)) return { key: "grass", lo: 2, hi: 3, scale: 0.5 };
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
  // every feature is now a real baked Civ4 sprite atlas (foliage or nif-rendered) or
  // nothing — the procedural vector stand-ins were removed once cactus/bamboo/grass got
  // real art. A feature with no atlas (e.g. FLOOD_PLAINS, a ground quality) draws no
  // foliage; the plot's terrain shows through.
  const g = treeGroupFor(feature);
  if (!g) return;
  const rng = mkRng((sx * 73856093) ^ (sy * 19349663));
  stampTrees(o, cx, cy, s, g, rng);              // real foliage sprites; nothing if not yet loaded
}
// A single Civ4 city sprite centred on an urban core plot (docs/urban-plots.md). The city is
// one connected building cluster, so it uses the largest baked sprite; its height scales with
// the province's development so a big capital reads larger than a town. Bottom-anchored so the
// buildings sit ON the plot and rise above it, like the foliage.
function citySprite(o, cx, cy, s, dev) {
  const meta = TREES && TREES.city, img = treeImg.city;
  if (!meta || !treeReady.city || !meta.sprites.length) return;
  const tier = 1.1 + Math.min(0.9, (dev || 0) / 45);   // town → capital: ~1.1 to ~2.0 plot-heights
  const sp = meta.sprites[0];
  const th = s * tier, tw = th * sp[2] / sp[3];
  const px = cx + s / 2, py = cy + s * 0.62;            // centred on the plot, bottom near its middle
  o.drawImage(img, sp[0], sp[1], sp[2], sp[3], px - tw / 2, py - th, tw, th);
}
export { drawPlots, drawCostOverlay, loadPlots };
