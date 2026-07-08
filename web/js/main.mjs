import { BUNDLE, MAP, VIEW, cam, ctx, cv, stage, P, provPath, provOnScreen, px, py, clampPan, worldW, sxSrc, sySrc, baseXr, baseYr, fitView, provSrcBox, K_PLOT, K_TEX, K_MAX, SEA, SEA_BANDS, isPolitical, latAtScreenY, cssVar, S } from "./core.mjs";
import { drawPlots, drawCostOverlay } from "./plots.mjs";
import { drawLabels } from "./labels.mjs";
import { drawPolitical, scheduleLegendRefresh } from "./overlays/political.mjs";
import { drawCaravanHeat, drawCaravan } from "./overlays/caravan.mjs";
// the baked terrain raster (a real image asset), drawn over the water; its ocean pixels are
// transparent so the sea layer below shows through, land is opaque.
// loading screen: show a random Anbennar splash (1:1, stage-cropped) until the map's first paint,
// held for at least MIN_LOADING_MS so a fast load doesn't flash the splash for a fraction of a second
const loadEl = document.getElementById("loading");
const loadStart = performance.now();
const MIN_LOADING_MS = 3000;
let loadingActive = false;
if (loadEl) {
  if (BUNDLE.loading && BUNDLE.loading.length) {
    loadEl.querySelector(".ld-art").src = BUNDLE.loading[Math.floor(Math.random() * BUNDLE.loading.length)];
    loadingActive = true;
  } else {
    loadEl.remove();                           // no art baked → no loading screen at all
  }
}
function hideLoading() {
  if (!loadingActive) return;
  loadingActive = false;
  const wait = Math.max(0, MIN_LOADING_MS - (performance.now() - loadStart));
  setTimeout(() => {
    loadEl.classList.add("gone");
    setTimeout(() => loadEl.remove(), 700);    // after the fade
  }, wait);
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
    const fade = 1 - Math.max(0, Math.min(1, (cam.k - K_PLOT) / (K_TEX - K_PLOT)));   // 1 ≤K_PLOT → 0 ≥K_TEX
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
  cv.width = r.width*dpr; cv.height = r.height*dpr; VIEW.dpr = dpr;
  fitView(r.width, r.height); clampPan(); draw();
}
const zoomLabelEl = document.getElementById("zoomLevel");   // top-left live magnification readout
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
  if (zoomLabelEl) zoomLabelEl.textContent = Math.round(cam.k) + "×";   // 1× (world) … 256× (max)
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
  if (!(period > 0)) { S.viewVersion = S.baseVersion * 16; renderScene(); ctx.restore(); return; }
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
}
function renderScene() {
  const w = VIEW.w, h = VIEW.h;
  if (mapReady) {
    ctx.imageSmoothingEnabled=true;
    ctx.drawImage(mapImg, 0,0,MAP.dw,MAP.dh,
      cam.x + cam.k*VIEW.dx, cam.y + cam.k*VIEW.dy, cam.k*VIEW.dw, cam.k*VIEW.dh);
  }
  // freshwater lakes: EU4 paints them with the ocean indices, so the raster leaves them the blue
  // sea gradient — tint each lake province's polygon a distinct green-teal over it so lakes read as
  // fresh water, not ocean. Uses the lake outlines now shipped in the bundle (docs/coastlines.md).
  ctx.save(); ctx.fillStyle = "rgba(74,150,128,0.42)";
  for (const p of P) if (p.type === "LAKE" && p.rings && provOnScreen(p)) ctx.fill(provPath(p));
  ctx.restore();
  drawPlots();   // crisp per-plot Civ4 terrain over the blurred raster when zoomed in
  drawCostOverlay();   // elevation movement-cost heat over the terrain, when toggled on

  if (S.showHeat && S.overlay === "caravan") drawCaravanHeat();   // caravan-days choropleth
  if (isPolitical()) drawPolitical();                             // nation/culture/faith fills
  // province outlines
  ctx.strokeStyle="rgba(190,205,230,.18)"; ctx.lineWidth=0.8;
  for (const p of P) if (p.rings && provOnScreen(p)) ctx.stroke(provPath(p));
  // hovered province highlight (polygon if we have one, else a centroid ring for seas)
  if (S.hoverProv && S.hoverProv.rings) {
    const hp = provPath(S.hoverProv);
    ctx.fillStyle="rgba(231,236,244,.12)"; ctx.fill(hp);
    ctx.strokeStyle="#eef2f8"; ctx.lineWidth=1.6; ctx.stroke(hp);
  } else if (S.hoverProv) {
    ctx.beginPath(); ctx.arc(px(S.hoverProv.lon), py(S.hoverProv.lat), 6, 0, 7);
    ctx.strokeStyle="#eef2f8"; ctx.lineWidth=1.4; ctx.stroke();
  }
  // selected province: a persistent accent outline while its detail fills the sidebar
  if (S.selectedProv && S.selectedProv.rings) {
    const sp = provPath(S.selectedProv);
    ctx.fillStyle="rgba(232,183,106,.12)"; ctx.fill(sp);
    ctx.strokeStyle=cssVar("--accent")||"#e8b76a"; ctx.lineWidth=2.2; ctx.stroke(sp);
  } else if (S.selectedProv) {
    ctx.beginPath(); ctx.arc(px(S.selectedProv.lon), py(S.selectedProv.lat), 7, 0, 7);
    ctx.strokeStyle=cssVar("--accent")||"#e8b76a"; ctx.lineWidth=2; ctx.stroke();
  }

  if (S.overlay === "caravan") drawCaravan();   // routes, origin star, moving caravans
  drawLabels();
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
export { draw, zoomAt, resize, focusProvince, focusProvinceFit, applyHash, hasDeepLink };
