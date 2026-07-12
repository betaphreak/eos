import { BUNDLE, MAP, VIEW, cam, ctx, cv, stage, P, provPath, provOnScreen, px, py, pxr, pyr, clampPan, worldW, sxSrc, sySrc, baseXr, baseYr, fitView, provSrcBox, K_PLOT, K_TEX, K_MAX, SEA, SEA_BANDS, isPolitical, isUnderground, latAtScreenY, cssVar, S } from "./core.mjs";
import { bandAlpha, kBand, bandName, regime, REGIME_INFO } from "./bands.mjs";
import { drawPlots } from "./plots.mjs";                       // still used directly by drawCavernPlots
import { scheduleLegendRefresh } from "./overlays/political.mjs";
import { ensureTiers } from "./overlays/tiers.mjs";
import { renderLayers } from "./layers.mjs";                   // the ordered scene registry (draw order + gating)
import { initMinimap, drawMinimap } from "./minimap.mjs";
// the baked terrain raster (a real image asset), drawn over the water; its ocean pixels are
// transparent so the sea layer below shows through, land is opaque.
// loading screen: show a random Anbennar splash (1:1, stage-cropped) until the map's first paint,
// then hide immediately (no minimum hold). The splash now doubles as index.html's "waiting for
// the server" screen (its health poll holds it while the server is down), so once the map is
// ready it should clear right away rather than lingering.
const loadEl = document.getElementById("loading");
const loadStart = performance.now();
const MIN_LOADING_MS = 0;
let loadingActive = false;
if (loadEl) {
  if (BUNDLE.loading && BUNDLE.loading.length) {
    loadEl.querySelector(".ld-art").src = BUNDLE.loading[Math.floor(Math.random() * BUNDLE.loading.length)];
  }
  loadingActive = true;                        // always manage it so first paint hides it (below)
}
// hide on first paint but KEEP the element in the DOM — the title (js/panel.mjs) re-opens its
// server picker as a dismissable overlay, so the markup must survive. Just fade it out.
function hideLoading() {
  if (!loadingActive) return;
  loadingActive = false;
  const wait = Math.max(0, MIN_LOADING_MS - (performance.now() - loadStart));
  setTimeout(() => { loadEl.classList.add("gone"); }, wait);
}

const mapImg = new Image();
let mapReady = false;
mapImg.onload = () => { mapReady = true; draw(); hideLoading(); };
mapImg.src = MAP.src;
// the ocean layer, drawn behind the (transparent-sea) land raster so it shows through only the
// sea: a climate-banded COLOUR from a vertical latitude gradient (tropical → temperate → polar),
// modulated by a screen-space greyscale RIPPLE tile via `soft-light`. Either half degrades: no
// SEA_BANDS → a flat sea fill; no ripple tile → gradient only; neither → the flat void.
const seaImg = new Image();
let seaPat = null;
if (SEA) { seaImg.onload = () => { seaPat = ctx.createPattern(seaImg, "repeat"); draw(); }; seaImg.src = SEA.src; }
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
function drawSeaBase(w, h) {
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
function resize() {
  const r = stage.getBoundingClientRect(), dpr = Math.min(window.devicePixelRatio||1, 2);
  if (!(r.width > 0) || !(r.height > 0)) return;   // ignore degenerate sizes (mid-layout / panel drag)
  cv.width = r.width*dpr; cv.height = r.height*dpr; VIEW.dpr = dpr;
  // Preserve the geographic point at the viewport centre AND the on-screen magnification across the
  // resize, so opening/closing/dragging the info panel beside the map (which shrinks/grows the stage)
  // never moves or rescales the world. fitView recomputes the base fit scale from the new size; we
  // then rebind cam.k/x/y to hold the same centre point at the same pixel scale — but only if the
  // result is finite (a transient zero dimension would otherwise poison cam with Infinity/NaN and
  // blank the map).
  const prev = (VIEW.w && VIEW.dw && cam.k) ? {
    fx: ((VIEW.w/2 - cam.x)/cam.k - VIEW.dx)/VIEW.dw,   // normalised map fraction at screen centre
    fy: ((VIEW.h/2 - cam.y)/cam.k - VIEW.dy)/VIEW.dh,
    mag: cam.k * VIEW.dw,                               // on-screen world size (dw/dh scale together)
  } : null;
  fitView(r.width, r.height);
  if (prev && VIEW.dw > 0 && VIEW.dh > 0) {
    const k = Math.max(1, Math.min(K_MAX, prev.mag / VIEW.dw));
    const x = VIEW.w/2 - k*(VIEW.dx + prev.fx*VIEW.dw);
    const y = VIEW.h/2 - k*(VIEW.dy + prev.fy*VIEW.dh);
    if (Number.isFinite(k) && Number.isFinite(x) && Number.isFinite(y)) { cam.k = k; cam.x = x; cam.y = y; }
  }
  clampPan();
  // Repaint SYNCHRONOUSLY, not via the RAF-coalesced draw(): setting cv.width/height above cleared the
  // canvas this frame, so deferring the paint to the next frame leaves the browser compositing a blank
  // canvas — which shows as the map blanking out while the side panel animates its size (the
  // ResizeObserver fires every frame). Painting now fills the freshly-sized canvas in the same frame.
  paint();
}
const zoomLabelEl = document.getElementById("zoomLevel");   // top-left readout: now the band name + regime chip
const regimePulseEl = document.getElementById("regimePulse");
let _sigRegime = null, _sigBand = null, _sigPlane = null;
// The top-bar readout shows the current BAND NAME (nearest band) tinted + iconed by the interaction
// REGIME, and doubles as the mode signal: it stamps the regime on #stage (→ the regime cursor) and
// flashes an accent vignette (#regimePulse) once whenever you cross a regime boundary. regime() is
// hysteretic (bands.mjs), so a scroll-tick on a seam can't strobe it. Runs every paint; the DOM is
// rebuilt only when the band/regime/plane actually changes.
function updateRegimeSignal() {
  const r = regime(), bn = bandName();
  stage.dataset.regime = r;                        // drives the regime cursor (styles.css) + input awareness
  if (r === _sigRegime && bn === _sigBand && S.plane === _sigPlane) return;
  const info = REGIME_INFO[r];
  if (zoomLabelEl) {
    zoomLabelEl.dataset.regime = r;
    const plane = S.plane === "underworld" ? ` <span class="rg-plane">· Underworld</span>` : "";
    zoomLabelEl.innerHTML = `<span class="rg-ico">${info.icon}</span><span class="rg-name">${bn}</span>${plane}`;
    zoomLabelEl.dataset.tip = `${info.name} regime · ${bn} band · ${Math.round(cam.k)}× — click to reset to the world`;
  }
  if (r !== _sigRegime && _sigRegime !== null && regimePulseEl) {   // pulse only on a real crossing, not first paint
    regimePulseEl.dataset.regime = r;
    regimePulseEl.classList.remove("pulsing");
    void regimePulseEl.offsetWidth;                // reflow so the animation restarts on repeat crossings
    regimePulseEl.classList.add("pulsing");
  }
  _sigRegime = r; _sigBand = bn; _sigPlane = S.plane;
}
// draw() is the public redraw request — it COALESCES to one paint per animation frame, so a burst of
// pan/zoom/pinch events (mobile fires many touchmoves per frame) collapses into a single scene render.
let rafPending = false;
function draw() {
  if (rafPending) return;
  rafPending = true;
  requestAnimationFrame(() => { rafPending = false; paint(); scheduleLegendRefresh(); });
}
function paint() {
  if (S.techOpen) return;   // tech-tree modal is in front — don't spend frames drawing the hidden map
  updateRegimeSignal();   // top-bar band-name chip + regime cursor + boundary pulse (replaces the raw × readout)
  S.markers = [];   // cave-entrance / teleporter hit-targets, repopulated this frame (hover reads them)
  const w=VIEW.w, h=VIEW.h, dpr=VIEW.dpr;
  ctx.setTransform(dpr,0,0,dpr,0,0);
  ctx.clearRect(0,0,w,h);
  ctx.fillStyle = "#070a10"; ctx.fillRect(0,0,w,h);   // void beyond the rendered latitude band

  // clip the whole scene to |lat| ≤ 89°: the Mercator projection diverges toward the poles and
  // the source map has no data there, so nothing (ocean, land, labels) is drawn above 89°.
  const yN = py(89), yS = py(-89);
  ctx.save();
  ctx.beginPath(); ctx.rect(0, Math.min(yN, yS), w, Math.abs(yS - yN)); ctx.clip();

  // the ocean base behind everything (the land raster's sea is transparent, so this shows
  // through it): a climate-banded latitude gradient + ripple overlay. Screen-space, drawn once.
  drawSeaBase(w, h);

  // cylindrical wrap: render the scene once per world-copy that overlaps the viewport, by
  // shifting the camera one wrap-period at a time — so each copy's own viewport culling and
  // provPath cache stay correct. Deep zoom → a single copy → no extra work. viewVersion is
  // derived per copy from baseVersion so the path cache is distinct per copy yet reused when idle.
  const period = worldW();
  if (!(period > 0)) { S.viewVersion = S.baseVersion * 16; renderScene(); ctx.restore(); drawMinimap(); return; }
  const L = cam.x + cam.k * VIEW.dx;                 // primary world's left screen edge
  const mMin = Math.floor((0 - L) / period), mMax = Math.floor((w - L) / period);
  const baseX = cam.x;
  for (let m = mMin; m <= mMax; m++) {
    cam.x = baseX + m * period;
    S.viewVersion = S.baseVersion * 16 + ((m - mMin) & 15);
    renderScene();
  }
  cam.x = baseX;
  ctx.restore();
  drawMinimap();   // the bottom-left world thumbnail + viewport rectangle tracks pan/zoom
}
// deterministic 0..1 per province id — a stable per-cell jitter (no Math.random, survives redraws)
const pjit = id => ((Math.imul(id | 0, 2654435761) >>> 0) % 1000) / 1000;
// A faint water wash over each SEA province's polygon, its lightness nudged per-province so adjacent
// seas read as distinct cells over the climate gradient (the deep-ocean provinces now ship outlines,
// so the whole ocean tessellates). Kept low-alpha so the gradient still shows through.
function drawSeaCells() {
  ctx.save();
  for (const p of P) {
    if (p.type !== "SEA" || !p.rings || !provOnScreen(p)) continue;
    const j = pjit(p.id);
    ctx.fillStyle = `rgba(${52 + (j * 26 | 0)},${84 + (j * 26 | 0)},${112 + (j * 22 | 0)},0.13)`;
    ctx.fill(provPath(p));
  }
  ctx.restore();
}
// Diagonal hatch tile for impassable/wasteland provinces, built once against the main context.
let hatchPat = null;
function impassableHatch() {
  if (hatchPat) return hatchPat;
  const T = 7, c = document.createElement("canvas"); c.width = c.height = T;
  const x = c.getContext("2d");
  x.strokeStyle = "rgba(22,25,31,0.5)"; x.lineWidth = 1.1;
  for (let o = -T; o <= T; o += T) { x.beginPath(); x.moveTo(o, T); x.lineTo(o + T, 0); x.stroke(); }
  hatchPat = ctx.createPattern(c, "repeat");
  return hatchPat;
}
// A grey wash + diagonal hatch over each impassable province (its terrain shows through the raster
// below), so wasteland reads as a distinct "you can't settle here" cell rather than plain ground.
function drawImpassable() {
  const hatch = impassableHatch();
  ctx.save();
  for (const p of P) {
    if (p.type !== "IMPASSABLE" || !p.rings || !provOnScreen(p)) continue;
    const path = provPath(p);
    ctx.fillStyle = "rgba(62,64,71,0.32)"; ctx.fill(path);
    ctx.fillStyle = hatch; ctx.fill(path);
  }
  ctx.restore();
}
// A light diagonal hash for the interstitial space between province polygons, shown only past deep
// zoom (band ≥ PLOT, 64×). Laid over the raster before the plot layer, so the opaque per-plot
// terrain covers each province and the hash survives only in the gaps between them (where ring
// simplification leaves the provinces not quite tiling).
let gapHatchPat = null;
function gapHatch() {
  if (gapHatchPat) return gapHatchPat;
  const T = 6, c = document.createElement("canvas"); c.width = c.height = T;
  const x = c.getContext("2d");
  x.strokeStyle = "rgba(200,208,222,0.5)"; x.lineWidth = 1;
  for (let o = -T; o <= T; o += T) { x.beginPath(); x.moveTo(o, T); x.lineTo(o + T, 0); x.stroke(); }
  gapHatchPat = ctx.createPattern(c, "repeat");
  return gapHatchPat;
}
function drawGapHatch() {
  ctx.save();
  ctx.fillStyle = gapHatch();
  ctx.fillRect(0, 0, VIEW.w, VIEW.h);
  ctx.restore();
}
// ---- per-world-copy scene layers ----
// The old imperative renderScene body is now a set of named layer functions; their draw ORDER and
// gating live in the LAYERS registry (layers.mjs), which renderScene runs. These stay defined here
// because they close over main's raster/camera state and the province-polygon helpers.

// the baked terrain raster, scaled by the camera — the base of every band
function drawRaster() {
  if (!mapReady) return;
  ctx.imageSmoothingEnabled = true;
  ctx.drawImage(mapImg, 0, 0, MAP.dw, MAP.dh,
    cam.x + cam.k * VIEW.dx, cam.y + cam.k * VIEW.dy, cam.k * VIEW.dw, cam.k * VIEW.dh);
}
// freshwater lakes: EU4 paints them with the ocean indices, so the raster leaves them the blue sea
// gradient — tint each lake polygon a distinct green-teal so lakes read as fresh water, not ocean.
function drawLakes() {
  ctx.save(); ctx.fillStyle = "rgba(74,150,128,0.42)";
  for (const p of P) if (p.type === "LAKE" && p.rings && provOnScreen(p)) ctx.fill(provPath(p));
  ctx.restore();
}
// surface plots only — underground provinces are relit by drawUnderworld on the Underworld plane.
function drawSurfacePlots() { drawPlots(isSurface); }
// province outlines (surface only; underground gets its lit rim from drawUnderworld). They FADE OUT
// below the province zoom so the coarser tier boundaries take over: gone below ~7.5×, full by ~10×.
function drawProvinceBorders() {
  const pbA = bandAlpha(kBand([7.5, 10]));
  if (pbA <= 0.01) return;
  ctx.save();
  ctx.globalAlpha = pbA;
  ctx.strokeStyle = "rgba(190,205,230,.18)"; ctx.lineWidth = 0.8;
  for (const p of P) if (isSurface(p) && p.rings && provOnScreen(p)) ctx.stroke(provPath(p));
  ctx.restore();
}
// hovered province highlight (polygon if we have one, else a centroid ring for seas)
function drawHoverHighlight() {
  if (S.hoverProv && S.hoverProv.rings) {
    const hp = provPath(S.hoverProv);
    ctx.fillStyle = "rgba(231,236,244,.12)"; ctx.fill(hp);
    ctx.strokeStyle = "#eef2f8"; ctx.lineWidth = 1.6; ctx.stroke(hp);
  } else if (S.hoverProv) {
    ctx.beginPath(); ctx.arc(px(S.hoverProv.lon), py(S.hoverProv.lat), 6, 0, 7);
    ctx.strokeStyle = "#eef2f8"; ctx.lineWidth = 1.4; ctx.stroke();
  }
}
// selected province: a persistent accent outline while its detail fills the sidebar
function drawSelectedHighlight() {
  if (S.selectedProv && S.selectedProv.rings) {
    const sp = provPath(S.selectedProv);
    ctx.fillStyle = "rgba(232,183,106,.12)"; ctx.fill(sp);
    ctx.strokeStyle = cssVar("--accent") || "#e8b76a"; ctx.lineWidth = 2.2; ctx.stroke(sp);
  } else if (S.selectedProv) {
    ctx.beginPath(); ctx.arc(px(S.selectedProv.lon), py(S.selectedProv.lat), 7, 0, 7);
    ctx.strokeStyle = cssVar("--accent") || "#e8b76a"; ctx.lineWidth = 2; ctx.stroke();
  }
}
// Render one world-copy: lazily pull the tier geometry as we approach its zoom, then paint the
// LAYERS registry in order. The registry (layers.mjs) is the single place to change draw order,
// gating, or band mapping — this function just runs it.
function renderScene() {
  if (cam.k < 10) ensureTiers(draw);   // tier geometry lazy-load (data, not a draw layer)
  renderLayers();
}

// The Underworld plane (docs/underworld.md), folded into the z=−1 layer set (docs/zoom-bands.md
// §Z-levels): each of these is a first-class registry entry gated z:[-1] in layers.mjs, so the
// underground gets the same reorder/regate seam as the surface. Order preserved from the old
// monolithic drawUnderworld: veil → cave floors → per-plot cave terrain → amber rims. Per
// world-copy, the veil is the raster's own rect, so adjacent copies abut (no double-darkening).

// veil this copy's map extent so the surface above recedes to a faint ghost
function drawUnderworldVeil() {
  ctx.save();
  ctx.fillStyle = "rgba(6,5,11,0.72)";
  ctx.fillRect(cam.x + cam.k*VIEW.dx, cam.y + cam.k*VIEW.dy, cam.k*VIEW.dw, cam.k*VIEW.dh);
  ctx.restore();
}
// a warm flat cave floor on every underground polygon — the overview look, and the fallback beneath
// the per-plot layer for provinces whose plots haven't streamed in yet
function drawCavernFloors() {
  ctx.save();
  ctx.fillStyle = "rgba(60,46,40,0.92)";
  for (const p of P) if (isUnderground(p) && p.rings && provOnScreen(p)) ctx.fill(provPath(p));
  ctx.restore();
}
// zoomed in: relight the underground provinces' real per-plot cave terrain over the veil (physical
// view only — the political overlays suppress plots, same as the surface)
function drawCavernPlots() {
  if (cam.k >= K_PLOT && !isPolitical()) drawPlots(isUnderground);
}
// an amber rim on every underground province, at all zooms, so the caves read as lit
function drawCavernRims() {
  ctx.save();
  ctx.strokeStyle = "rgba(230,180,120,0.6)"; ctx.lineWidth = 1.0;
  for (const p of P) if (isUnderground(p) && p.rings && provOnScreen(p)) ctx.stroke(provPath(p));
  ctx.restore();
}
const isSurface = p => !isUnderground(p);

// On the Overworld, underground provinces are hidden — so mark the cave entrances: where a
// surface province borders a hidden underground one (a descent point / gate-hold like Marrhold),
// draw a small amber cave-mouth glyph on their shared border. Lets you see, from the surface,
// that a neighbour lies underground. See docs/underworld.md.
// cave entrance/exit glyph: an outer disc with a dark mouth. The teleporter marker reuses these
// radii at TELEPORT_SCALE× so a portal reads as a much larger version of the same cave-mouth motif.
const CAVE_MOUTH_R = 4.5, CAVE_MOUTH_IN = 1.9, TELEPORT_SCALE = 4;
function drawCaveEntrances() {
  ctx.save();
  for (const p of P) {
    if (isUnderground(p) || !p.nb || !p.rings || !provOnScreen(p)) continue;
    for (const nbId of p.nb) {
      const nb = Pby.get(nbId);
      if (!nb || !isUnderground(nb)) continue;
      // the shared border is ~midway between the two centroids; bias toward the cave side
      const mx = px(p.lon) * 0.45 + px(nb.lon) * 0.55, my = py(p.lat) * 0.45 + py(nb.lat) * 0.55;
      ctx.beginPath(); ctx.arc(mx, my, CAVE_MOUTH_R, 0, 7);
      ctx.fillStyle = "rgba(232,183,106,0.9)"; ctx.fill();
      ctx.beginPath(); ctx.arc(mx, my, CAVE_MOUTH_IN, 0, 7);
      ctx.fillStyle = "rgba(18,10,6,0.92)"; ctx.fill();   // the dark cave mouth
      S.markers.push({ x: mx, y: my, r: CAVE_MOUTH_R + 4,
        label: `<b>Cave entrance</b><br><span class="r">↧ ${nb.name}</span>` });
    }
  }
  ctx.restore();
}

// EU4-style red dotted connection lines for the special adjacencies (straits, canals, lake
// crossings, Dwarovar tunnels) between provinces that are not visually adjacent. Surface
// adjacencies draw on the Overworld; tunnels (an underground endpoint) draw on the Underworld,
// where the caves they link are lit. The dotted line spans each pair's NEAREST coasts (closest
// ring vertices), not their centroids, so a strait touches the two shores it bridges. See docs.
const ADJ_RED = "rgba(224,66,52,0.9)";   // EU4 strait/connection red
const ADJ_MIN_ZOOM = 10;                 // only draw connection lines once zoomed to a region
// closest pair of ring vertices between two provinces, in SOURCE px, cached per pair (camera-
// independent). A brute-force nearest-vertex search over the (simplified) rings; ring-less pairs
// return null and fall back to centroids.
const adjEndsCache = new Map();
function nearestEdgePair(a, b) {
  const key = a.id < b.id ? a.id + "_" + b.id : b.id + "_" + a.id;
  if (adjEndsCache.has(key)) return adjEndsCache.get(key);
  let best = Infinity, ax = 0, ay = 0, bx = 0, by = 0;
  if (a.rings && b.rings)
    for (const ra of a.rings) for (const pa of ra)
      for (const rb of b.rings) for (const pb of rb) {
        const dx = pa[0] - pb[0], dy = pa[1] - pb[1], d = dx * dx + dy * dy;
        if (d < best) { best = d; ax = pa[0]; ay = pa[1]; bx = pb[0]; by = pb[1]; }
      }
  const e = best < Infinity ? { ax, ay, bx, by } : null;
  adjEndsCache.set(key, e);
  return e;
}
function drawAdjacencies() {
  const adj = BUNDLE.adjacencies;
  if (!adj || !adj.length || cam.k < ADJ_MIN_ZOOM) return;   // hidden at world/continent view
  const under = S.plane === "underworld";
  ctx.save();
  ctx.lineWidth = 1.4;
  ctx.strokeStyle = ADJ_RED;
  for (const [fromId, toId, , teleport] of adj) {
    const a = Pby.get(fromId), b = Pby.get(toId);
    if (!a || !b) continue;
    const tunnel = isUnderground(a) || isUnderground(b);
    if (under ? !tunnel : tunnel) continue;   // show tunnels only underground, straits only above
    if (teleport) {
      // too far for a sensible line — a teleporter: mark each endpoint instead (cave-entrance style),
      // each labelled with the province it warps to
      teleportMark(px(a.lon), py(a.lat), b.name);
      teleportMark(px(b.lon), py(b.lat), a.name);
      continue;
    }
    // span the two provinces' nearest coasts; centroids only if a ring is missing
    const e = nearestEdgePair(a, b);
    const x1 = e ? pxr(e.ax) : px(a.lon), y1 = e ? pyr(e.ay) : py(a.lat);
    const x2 = e ? pxr(e.bx) : px(b.lon), y2 = e ? pyr(e.by) : py(b.lat);
    if (Math.max(x1, x2) < 0 || Math.min(x1, x2) > VIEW.w
        || Math.max(y1, y2) < 0 || Math.min(y1, y2) > VIEW.h) continue;   // off-screen cull
    ctx.setLineDash([5, 4]);
    ctx.beginPath(); ctx.moveTo(x1, y1); ctx.lineTo(x2, y2); ctx.stroke();
    ctx.setLineDash([]);
  }
  ctx.restore();
}
// a teleporter endpoint marker — the cave-mouth motif at TELEPORT_SCALE× (a large red disc with a
// dark centre), so a portal reads as a much bigger version of an underworld entrance/exit.
function teleportMark(x, y, dest) {
  const R = CAVE_MOUTH_R * TELEPORT_SCALE, m = R + 4;
  if (x < -m || x > VIEW.w + m || y < -m || y > VIEW.h + m) return;
  ctx.beginPath(); ctx.arc(x, y, R, 0, 7);
  ctx.fillStyle = ADJ_RED; ctx.fill();
  ctx.beginPath(); ctx.arc(x, y, CAVE_MOUTH_IN * TELEPORT_SCALE, 0, 7);
  ctx.fillStyle = "rgba(18,6,6,0.92)"; ctx.fill();
  if (dest) S.markers.push({ x, y, r: R, label: `<b>Portal</b><br><span class="r">⇄ ${dest}</span>` });
}

// place province name labels over the map with a halo, skipping any that would
// overflow the stage or collide with one already placed (priority: origin first,
// then destinations, then the largest context provinces).
function zoomAt(mx, my, factor) {
  const k2 = Math.max(1, Math.min(K_MAX, cam.k * factor));   // deep enough to read individual plots
  if (k2 === cam.k) return;
  const f = k2 / cam.k;
  cam.x = mx - f * (mx - cam.x);     // keep the point under (mx,my) fixed
  cam.y = my - f * (my - cam.y);
  cam.k = k2;
  clampPan(); S.baseVersion++; draw();
}
// ---- deep link: index.html#p=<provinceId>&z=<zoom> focuses a province at a zoom ----
const Pby = new Map(P.map(p => [p.id, p]));
function focusProvince(id, k) {
  const p = Pby.get(id); if (!p) return;
  cam.k = Math.max(1, Math.min(K_MAX, k || 18));
  cam.x = VIEW.w / 2 - cam.k * baseXr(sxSrc(p.lon));
  cam.y = VIEW.h / 2 - cam.k * baseYr(sySrc(p.lat));
  clampPan(); S.baseVersion++; draw();
}
// Zoom so the WHOLE province fits the viewport (double-click), centred on its bounding box:
// pick the scale that fits the box's screen extent within a margin, clamped to the zoom range,
// and centre on the box centre. Falls back to a fixed zoom when the province has no polygon.
function focusProvinceFit(id) {
  const p = Pby.get(id); if (!p) return;
  const box = provSrcBox(p);
  if (!box) return focusProvince(id, 40);                               // ring-less province: a deep fixed zoom
  const m = 0.9;                                                         // fill most of the canvas, a sliver of air
  const wSrc = Math.max(1, (box.x1 - box.x0) / (MAP.x1 - MAP.x0) * VIEW.dw);   // province width in base screen px
  const hSrc = Math.max(1, (box.y1 - box.y0) / (MAP.y1 - MAP.y0) * VIEW.dh);
  cam.k = Math.max(1, Math.min(K_MAX, Math.min(VIEW.w * m / wSrc, VIEW.h * m / hSrc)));
  cam.x = VIEW.w / 2 - cam.k * baseXr((box.x0 + box.x1) / 2);
  cam.y = VIEW.h / 2 - cam.k * baseYr((box.y0 + box.y1) / 2);
  clampPan(); S.baseVersion++; draw();
}
// Deep link: focus a province from the URL. Accepts a QUERY string (?p=<id>&z=<zoom> — the
// production/shareable form; on Azure SWA the navigationFallback rewrites /worldmap → index.html
// so the pretty path works too) OR the #p=<id>&z=<zoom> hash (back-compat). The query wins when
// both are present. z is optional (defaults to a deep texture zoom).
function readDeepLink() {
  const qs = new URLSearchParams(location.search);
  let p = qs.get("p"), z = qs.get("z");
  if (p == null) { const m = /(?:^|[#&])p=(\d+)/.exec(location.hash); if (m) p = m[1]; }
  if (z == null) { const m = /(?:^|[#&])z=(\d+(?:\.\d+)?)/.exec(location.hash); if (m) z = m[1]; }
  return { p: p == null || p === "" ? null : +p, z: z == null || z === "" ? null : +z };
}
function hasDeepLink() { return readDeepLink().p != null; }
function applyHash() {
  const { p, z } = readDeepLink();
  if (p == null || Number.isNaN(p)) return;
  if (z != null && !Number.isNaN(z)) focusProvince(p, z);   // explicit ?z= → that exact zoom
  else focusProvinceFit(p);                                 // no zoom given → frame the whole province, centred
}
window.addEventListener("hashchange", applyHash);
window.addEventListener("popstate", applyHash);   // browser back/forward between deep links
initMinimap(draw);   // bottom-left minimap; drawMinimap() (called from paint) keeps it in sync
// Canvas text does not trigger webfont loading the way laid-out DOM text does, so the first
// paint would use the sans fallback until some later redraw. Explicitly fetch the bundled
// map-label faces (see core.LABEL_FONT / styles.css) and redraw once they are ready. Guarded
// for browsers without the Font Loading API (they just keep the CSS fallback).
if (typeof document !== "undefined" && document.fonts && document.fonts.load) {
  Promise.all([
    document.fonts.load('400 16px "Jost"'),
    document.fonts.load('700 16px "Jost"'),
  ]).then(() => draw()).catch(() => {});
}

export { draw, zoomAt, resize, focusProvince, focusProvinceFit, applyHash, hasDeepLink };
// scene-layer draw fns, consumed by the LAYERS registry in layers.mjs (they stay here because they
// close over main's raster/camera state and the Pby/hatch helpers)
export { drawRaster, drawLakes, drawSeaCells, drawGapHatch, drawImpassable, drawSurfacePlots,
         drawProvinceBorders, drawUnderworldVeil, drawCavernFloors, drawCavernPlots, drawCavernRims,
         drawCaveEntrances, drawAdjacencies,
         drawHoverHighlight, drawSelectedHighlight };
