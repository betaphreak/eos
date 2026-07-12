"use strict";
// Political overlay — the map render for the Nation/Culture/Faith overlays: province polygons
// coloured by the active dimension (core.polOf / S.overlay), zoom-banded so the map yields to the
// physical terrain as you dive in, plus the legend/search spotlight. The chrome (legend, entity
// search, sidebar Politics block) lives in panel.mjs; this module owns only the canvas render.
import { ctx, cam, P, provPath, provOnScreen, polOf, isPolitical, isUnderground, COUNTRIES, CULTURES, RELIGIONS, K_PLOT, K_TEX, lerp, VIEW, provSrcBox, pxr, pyr, worldW, S } from "../core.mjs";
import { draw, focusProvinceFit } from "../main.mjs";
import { bandAlpha, kBand } from "../bands.mjs";

// "#rrggbb" + alpha -> an rgba() string, memoised (the nation/culture/faith fills)
const _rgbaCache = {};
export function hexA(hex, a) {
  const key = hex + "|" + a.toFixed(3);
  return _rgbaCache[key] || (_rgbaCache[key] =
    `rgba(${parseInt(hex.slice(1,3),16)},${parseInt(hex.slice(3,5),16)},${parseInt(hex.slice(5,7),16)},${a.toFixed(3)})`);
}

// Draw the active political overlay. Called by main.renderScene only while isPolitical() is true.
// Below K_PLOT it is a full-opacity overview; through K_PLOT→K_TEX the fill fades as the terrain
// plots appear; past K_TEX only coloured borders remain (plus the hovered province), letting the
// per-plot terrain read underneath. A province with no value for the dimension never fills.
export function drawPolitical() {
  // on the Overworld the underground provinces are hidden, so they take no political colour;
  // on the Underworld plane they may (they carry owner/culture/faith too)
  const shown = p => S.plane === "underworld" || !isUnderground(p);
  // Political overlays no longer fade to reveal per-plot terrain (main.renderScene suppresses plots
  // under them), so the fill stays readable at EVERY zoom — a gentle taper past K_PLOT keeps some
  // terrain context — and crisp coloured borders are added once zoomed in for province legibility.
  const a = cam.k < K_PLOT ? 0.58 : lerp(0.52, 0.42, bandAlpha(kBand([K_PLOT, K_TEX])));
  for (const p of P) if (p.rings && provOnScreen(p) && shown(p)) {
    const e = polOf(p).e;
    if (e) { ctx.fillStyle = hexA(e.color, a); ctx.fill(provPath(p)); }
  }
  if (cam.k >= K_PLOT) {
    ctx.lineWidth = 1.4;
    for (const p of P) if (p.rings && provOnScreen(p) && shown(p)) {
      const e = polOf(p).e;
      if (e) { ctx.strokeStyle = hexA(e.color, 0.9); ctx.stroke(provPath(p)); }
    }
  }
  // spotlight one polity (hovered in the legend / picked from search): brighten its provinces
  if (S.polHi) {
    ctx.save(); ctx.lineWidth = 2;
    for (const p of P) if (p.rings && provOnScreen(p) && polOf(p).key === S.polHi) {
      ctx.fillStyle = "rgba(255,255,255,.16)"; ctx.fill(provPath(p));
      ctx.strokeStyle = "#fff"; ctx.stroke(provPath(p));
    }
    ctx.restore();
  }
}

// ---- political chrome: legend, entity search, sidebar block, and the lazy political.js loader.
// The panel (panel.mjs) orchestrates when these run (setOverlay/search/provinceRail); this module
// owns the political UI itself. draw/focusProvinceFit come from main.mjs (hoisted — the main<->overlay
// import cycle is safe). ----
const polLegend = document.getElementById("polLegend");

// the active plane restricts which provinces the political panel counts: the Overworld ledger
// lists only surface polities, the Underworld ledger only underground ones. (Underground provinces
// share the surface's coordinates — a second map plane — so without this the panel on either plane
// would include the other's nations.) Matches drawPolitical's per-plane fill.
const planeShows = p => S.plane === "underworld" ? isUnderground(p) : !isUnderground(p);

// per-overlay province coverage counts (key -> #provinces), cached until the overlay OR plane
// changes; shared by the legend and the entity search so neither rescans P on every keystroke
let _cov = { overlay: null, plane: null, map: null };
export function coverage() {
  if (_cov.overlay === S.overlay && _cov.plane === S.plane && _cov.map) return _cov.map;
  const m = new Map();
  for (const p of P) { if (!planeShows(p)) continue; const k = polOf(p).key; if (k) m.set(k, (m.get(k) || 0) + 1); }
  _cov = { overlay: S.overlay, plane: S.plane, map: m };
  return m;
}
const polTable = () => S.overlay === "culture" ? CULTURES : S.overlay === "faith" ? RELIGIONS : COUNTRIES;

// whether a province's polygon is at least partly on-screen right now (its source-pixel bbox,
// projected through the camera, intersects the viewport). Accounts for the cylindrical E-W wrap.
function inViewport(p) {
  const box = provSrcBox(p);
  if (!box) return false;
  const x0 = pxr(box.x0), x1 = pxr(box.x1), y0 = pyr(box.y0), y1 = pyr(box.y1);
  const sy0 = Math.min(y0, y1), sy1 = Math.max(y0, y1);
  if (sy1 < 0 || sy0 > VIEW.h) return false;
  let sx0 = Math.min(x0, x1), sx1 = Math.max(x0, x1);
  const w = worldW();
  for (let k = -1; k <= 1; k++) if (sx1 + k * w >= 0 && sx0 + k * w <= VIEW.w) return true;
  return false;
}

// province coverage counts restricted to the current viewport (the ledger lists only what's visible)
function viewportCoverage() {
  const m = new Map();
  for (const p of P) { const k = polOf(p).key; if (k && planeShows(p) && inViewport(p)) m.set(k, (m.get(k) || 0) + 1); }
  return m;
}

// rebuild the legend a beat after the camera settles (called from main.draw on every paint); the
// timer resets while panning so the (DOM-heavy) rebuild runs once movement stops. Gated on
// baseVersion: the ledger's rows depend only on the VIEWPORT (which polities are visible), so a
// redraw that didn't move the camera — e.g. spotlighting a row on hover — must NOT rebuild the DOM.
// Rebuilding under the cursor re-fires the row's mouseleave/mouseenter, which draw()s again, which
// would reschedule this — the runaway redraw loop this guard breaks.
let legendTimer = 0, legendVersion = -1;
export function scheduleLegendRefresh() {
  if (!isPolitical() || polLegend.hidden) return;
  if (S.baseVersion === legendVersion) return;   // viewport unchanged since the last build → nothing to redo
  clearTimeout(legendTimer);
  legendTimer = setTimeout(renderPolLegend, 140);
}

// collapsed state persists across re-renders; defaults collapsed on small screens (a docked bottom
// sheet there) so the polity list doesn't cover the map — expand it by tapping the header
let legendCollapsed = null;

// the coverage-ranked (province count, descending), scrollable, collapsible legend
// (Nations/Cultures/Religions); hides when not political
export function renderPolLegend() {
  if (!isPolitical()) { polLegend.hidden = true; return; }
  legendVersion = S.baseVersion;                        // the legend now reflects this viewport (see scheduleLegendRefresh)
  const table = polTable();
  // the ledger LISTS the polities visible in the viewport, but the count shown per row is each
  // polity's TOTAL province count across the whole map (coverage()), not just the visible tally —
  // so a nation you've zoomed in on still reads its true size, not "1 of the 50 it owns".
  const vis = viewportCoverage(), total = coverage();
  const rows = [...vis.keys()].filter(k => table[k])
    .map(k => [k, total.get(k) || 0])
    .sort((a, b) => b[1] - a[1] || table[a[0]].name.localeCompare(table[b[0]].name));
  const title = S.overlay === "culture" ? "Cultures" : S.overlay === "faith" ? "Religions" : "Nations";
  if (legendCollapsed === null) legendCollapsed = matchMedia("(max-width: 640px)").matches;
  let html = `<button class="lg-h lg-toggle" aria-expanded="${!legendCollapsed}">${title} · ${rows.length}<span class="lg-caret" aria-hidden="true">▾</span></button><div class="leg-scroll">`;
  for (const [k, n] of rows) { const e = table[k];
    html += `<button class="legrow" data-key="${k}"><span class="sw" style="background:${e.color}"></span><span class="leg-nm">${e.name}</span><span class="km">${n}</span></button>`;
  }
  polLegend.innerHTML = html + `</div>`;
  polLegend.hidden = false;
  polLegend.classList.toggle("collapsed", legendCollapsed);
  const toggle = polLegend.querySelector(".lg-toggle");
  toggle.addEventListener("click", () => {
    legendCollapsed = !legendCollapsed;
    polLegend.classList.toggle("collapsed", legendCollapsed);
    toggle.setAttribute("aria-expanded", !legendCollapsed);
  });
  polLegend.querySelectorAll(".legrow").forEach(b => {
    b.addEventListener("mouseenter", () => { S.polHi = b.dataset.key; draw(); });
    b.addEventListener("mouseleave", () => { if (S.polHi === b.dataset.key) { S.polHi = null; draw(); } });
    b.addEventListener("click", () => focusEntity(b.dataset.key));
  });
}

// frame a polity: focus its largest province and keep it spotlighted
export function focusEntity(key) {
  let best = null; for (const p of P) if (polOf(p).key === key && p.rings && (!best || p.plots > best.plots)) best = p;
  S.polHi = key;
  if (best) { S.camBeforeFocus = { ...cam }; focusProvinceFit(best.id); }
  draw();
}

// the entity-search context for the active overlay (or null → the province search), used by panel.search
export function overlayEntity() {
  if (S.overlay === "nation")  return { table: COUNTRIES, kind: "nation",  ph: "Find a nation…" };
  if (S.overlay === "culture") return { table: CULTURES,  kind: "culture", ph: "Find a culture…" };
  if (S.overlay === "faith")   return { table: RELIGIONS, kind: "faith",   ph: "Find a religion…" };
  return null;
}

// the political metagrid (nation / culture / faith) for a province's detail panel; empty for an
// unowned province (sea, uncolonized wasteland)
export function politicsBlock(p) {
  const owner = p.owner && COUNTRIES[p.owner];
  const cult = p.culture && CULTURES[p.culture];
  const relig = p.religion && RELIGIONS[p.religion];
  if (!owner && !cult && !relig) return "";
  const dot = c => `<span class="dot" style="background:${c.color}"></span>`;
  const cell = (k, v) => `<div class="metacell"><div class="k">${k}</div><div class="v" style="font-size:13px">${v}</div></div>`;
  return `<p class="sectlabel" style="margin-top:12px">Politics</p>
    <div class="metagrid">
      ${owner ? cell("Nation", `${dot(owner)}${owner.name}`) : cell("Nation", "Unclaimed")}
      ${cult ? cell("Culture", `${dot(cult)}${cult.name}`) : ""}
      ${relig ? cell("Faith", `${dot(relig)}${relig.name}`) : ""}
    </div>`;
}

// the political layer (owner/culture/religion + the country/culture/religion tables) lives in a
// separate web/political.js, fetched only the first time a political overlay is entered — so World
// and Caravan never download it. Once loaded it enriches the province objects in place, so the
// sidebar's nation/culture/faith detail then works in every overlay too.
let politicalLoaded = false, politicalLoading = null;
export function politicalReady() { return politicalLoaded; }
export function ensurePolitical() {
  if (politicalLoaded) return Promise.resolve();
  if (politicalLoading) return politicalLoading;
  politicalLoading = new Promise((resolve, reject) => {
    const s = document.createElement("script");
    s.src = "political.js";
    s.onload = () => { applyPolitical(window.POLITICAL || {}); politicalLoaded = true; resolve(); };
    s.onerror = () => { politicalLoading = null; reject(new Error("failed to load political.js")); };
    document.head.appendChild(s);
  });
  return politicalLoading;
}
function applyPolitical(POL) {
  Object.assign(COUNTRIES, POL.countries || {});
  Object.assign(CULTURES, POL.cultures || {});
  Object.assign(RELIGIONS, POL.religions || {});
  const byId = new Map(P.map(p => [p.id, p]));
  for (const r of (POL.provinces || [])) {
    const p = byId.get(r.id); if (!p) continue;
    p.owner = r.o; p.controller = r.ct || r.o; p.culture = r.c; p.religion = r.r;
  }
  _cov = { overlay: null, plane: null, map: null };   // province keys just changed — invalidate the coverage cache
}
