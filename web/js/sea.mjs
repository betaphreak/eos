"use strict";
// The OCEAN — the screen-space base the whole scene floats on, and the polar ice cap over it.
//
// Why this is its own module and its own registry (SCREEN_LAYERS in layers.mjs) rather than a normal
// LAYERS entry: everything in LAYERS is drawn once per on-screen WORLD COPY, because the cylindrical
// wrap re-renders the scene per copy with a shifted camera. These two are SCREEN-SPACE — they fill
// the viewport once, from the latitude at each screen row, and know nothing about world copies.
// Running them per copy would re-fill the same pixels N times and, worse, composite the soft-light
// ripple N times over itself (each pass darkening the last). So they are drawn once per frame, ahead
// of the wrap loop, and the registry models that as a separate ordered stack instead of pretending
// they are per-copy layers.
//
// Extracted from main.mjs, which had accumulated them as hardcoded calls inside paint() — the last
// draws in the scene that weren't in a registry. See docs/zoom-bands.md §Layer registry.
import { VIEW, cam, MAP, SEA, SEA_BANDS, ICE_ART, ctx, latAtScreenY, K_PLOT, K_TEX } from "./core.mjs";
import { bandAlpha, kBand } from "./bands.mjs";

// A redraw request, injected by main (initSea) so this module never imports main.mjs back — the
// image loads are async and must repaint whenever they land. Same idiom as initMinimap(draw).
let redraw = () => {};

// the ocean layer, drawn behind the (transparent-sea) land raster so it shows through only the
// sea: a climate-banded COLOUR from a vertical latitude gradient (tropical → temperate → polar),
// modulated by a screen-space greyscale RIPPLE tile via `soft-light`. Either half degrades: no
// SEA_BANDS → a flat sea fill; no ripple tile → gradient only; neither → the flat void.
const seaImg = new Image();
let seaPat = null;
// the real Civ4 pack-ice tile for the open-ocean cap; absent → no cap (the per-plot shelf floes remain)
const iceImg = new Image();
let iceReady = false;

/** Wire the async art loads to a repaint. Called once by main; safe to call before the images land. */
export function initSea(onLoad) {
  redraw = onLoad || redraw;
  if (SEA) { seaImg.onload = () => { seaPat = ctx.createPattern(seaImg, "repeat"); redraw(); }; seaImg.src = SEA.src; }
  if (ICE_ART) { iceImg.onload = () => { iceReady = true; redraw(); }; iceImg.src = ICE_ART.src; }
}

// piecewise sea colour by |latitude|: tropical (≤23°) → temperate (~40°) → polar (≥60°), then a
// fade toward deep-ocean dark past 72° so the empty polar seas beyond the mapped land read as
// deep water (and the soft-light ripple stops showing its tiling on that flat grey expanse).
function seaColorAt(lat) {
  const B = SEA_BANDS, a = Math.abs(lat);
  const mix = (u, v, f) => [u[0]+(v[0]-u[0])*f, u[1]+(v[1]-u[1])*f, u[2]+(v[2]-u[2])*f];
  let c;
  if (a <= 23) c = B.trop;
  else if (a >= 60) c = B.polar;
  else if (a <= 40) c = mix(B.trop, B.temp, (a - 23) / 17);
  else c = mix(B.temp, B.polar, (a - 40) / 20);
  if (a > 72) c = mix(c, [12, 18, 28], Math.min(1, (a - 72) / 16));
  return `rgb(${c[0]|0},${c[1]|0},${c[2]|0})`;
}
// fill the viewport with the ocean base: the latitude colour gradient, then the ripple overlay
const SEA_WAVE = 1.0;   // ripple tile size, in map-raster px per texture px (world-view wave scale)
export function drawSeaBase() {
  const w = VIEW.w, h = VIEW.h;
  if (SEA_BANDS) {
    const g = ctx.createLinearGradient(0, 0, 0, h);
    for (let i = 0; i <= 16; i++) g.addColorStop(i / 16, seaColorAt(latAtScreenY((i / 16) * h)));
    ctx.fillStyle = g; ctx.fillRect(0, 0, w, h);
  } else { ctx.fillStyle = "#090d14"; ctx.fillRect(0, 0, w, h); }
  // ripples (soft-light so grey=128 keeps the gradient colour). The pattern is ANCHORED to the
  // map — it pans and scales with the world instead of being a fixed screen grid — and fades out
  // by deep zoom, where the upscaled tile would blur and open water is calm anyway.
  if (seaPat) {
    const fade = 1 - bandAlpha(kBand([K_PLOT, K_TEX]));   // 1 ≤K_PLOT → 0 ≥K_TEX (fade out over the plot band)
    // confine the ripple to the mapped-latitude band (the raster's on-screen Y extent). Beyond it —
    // the empty polar seas between the map's top/bottom edge and the ±89° scene clip — the tile would
    // repeat as a visible static grid, so those bands stay flat gradient instead.
    const my0 = Math.max(0, cam.y + cam.k * VIEW.dy), my1 = Math.min(h, cam.y + cam.k * (VIEW.dy + VIEW.dh));
    if (fade > 0.02 && my1 > my0) {
      const s = cam.k * (VIEW.dw / MAP.dw) * SEA_WAVE;              // map px → screen, so it zooms with the map
      seaPat.setTransform(new DOMMatrix([s, 0, 0, s, cam.x + cam.k * VIEW.dx, cam.y + cam.k * VIEW.dy]));
      ctx.save();
      ctx.globalCompositeOperation = "soft-light"; ctx.globalAlpha = fade;
      ctx.fillStyle = seaPat; ctx.fillRect(0, my0, w, my1 - my0);
      ctx.restore();
    }
  }
}
// Polar sea ice on the OPEN ocean. drawSeaIce (plots.mjs) handles the coastal shelf floes per-plot,
// but the plotless deep ocean past the shelves has no floes — so at world/regional zoom the poles read
// as bare dark water. This is the screen-space ICE CAP for that open water: a latitude-ramped coverage
// of the Civ6 icecaps tile (map-anchored, so it scales with the world), faded out entering the plot
// band (like the ripple) where the per-plot shelf floes take over. Land is drawn on top, so it only
// shows on ocean.
let _iceLayer = null;
const ICE_TILE = 1.8;   // ice-tile magnification over the ripple scale — fewer visible seams on the ice sheet
// ice coverage 0..1 by |latitude|: open water below ~62°, ramping to a near-solid cap by ~80°
function iceCoverAt(lat) { const a = Math.abs(lat); return a <= 62 ? 0 : Math.min(1, (a - 62) / 18); }
export function drawPolarIce() {
  const w = VIEW.w, h = VIEW.h;
  if (!iceReady || !SEA_BANDS) return;
  const fade = 1 - bandAlpha(kBand([K_PLOT, K_TEX]));   // world/regional → 1, fades out over the plot band
  if (fade <= 0.02) return;
  if (iceCoverAt(latAtScreenY(0)) <= 0 && iceCoverAt(latAtScreenY(h)) <= 0) return;   // no polar water on screen
  // build the ice on a reused temp layer: the tile, masked by a per-row latitude-alpha gradient
  if (!_iceLayer) _iceLayer = document.createElement("canvas");
  if (_iceLayer.width !== (w|0) || _iceLayer.height !== (h|0)) { _iceLayer.width = Math.max(1, w|0); _iceLayer.height = Math.max(1, h|0); }
  const t = _iceLayer.getContext("2d");
  t.globalCompositeOperation = "source-over"; t.clearRect(0, 0, w, h);
  const ipat = t.createPattern(iceImg, "repeat");
  const s = cam.k * (VIEW.dw / MAP.dw) * SEA_WAVE * ICE_TILE;         // map px → screen, so the ice scales with the world
  ipat.setTransform(new DOMMatrix([s, 0, 0, s, cam.x + cam.k * VIEW.dx, cam.y + cam.k * VIEW.dy]));
  t.fillStyle = ipat; t.fillRect(0, 0, w, h);
  const g = t.createLinearGradient(0, 0, 0, h);                       // latitude coverage mask
  for (let i = 0; i <= 16; i++) g.addColorStop(i / 16, `rgba(255,255,255,${iceCoverAt(latAtScreenY((i / 16) * h))})`);
  t.globalCompositeOperation = "destination-in"; t.fillStyle = g; t.fillRect(0, 0, w, h);
  // ~0.78 so the polar sea colour still reads through the floes (ice OVER water, not an opaque sheet)
  ctx.save(); ctx.globalAlpha = 0.78 * fade; ctx.drawImage(_iceLayer, 0, 0); ctx.restore();
}
