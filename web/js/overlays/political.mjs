"use strict";
// Political overlay — the map render for the Nation/Culture/Faith overlays: province polygons
// coloured by the active dimension (core.polOf / S.overlay), zoom-banded so the map yields to the
// physical terrain as you dive in, plus the legend/search spotlight. The chrome (legend, entity
// search, sidebar Politics block) lives in panel.mjs; this module owns only the canvas render.
import { ctx, cam, P, provPath, polOf, isPolitical, COUNTRIES, CULTURES, RELIGIONS, K_PLOT, K_TEX, lerp, S } from "../core.mjs";
import { draw, focusProvinceFit } from "../main.mjs";

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
  if (cam.k < K_TEX) {
    const a = cam.k < K_PLOT ? 0.58
      : lerp(0.5, 0.15, (cam.k - K_PLOT) / (K_TEX - K_PLOT));
    for (const p of P) if (p.rings) {
      const e = polOf(p).e;
      if (e) { ctx.fillStyle = hexA(e.color, a); ctx.fill(provPath(p)); }
    }
  } else {
    ctx.lineWidth = 1.4;
    for (const p of P) if (p.rings) {
      const e = polOf(p).e;
      if (e) { ctx.strokeStyle = hexA(e.color, 0.9); ctx.stroke(provPath(p)); }
    }
    const hi = S.hoverProv, he = hi && hi.rings && polOf(hi).e;
    if (he) { ctx.fillStyle = hexA(he.color, 0.35); ctx.fill(provPath(hi)); }
  }
  // spotlight one polity (hovered in the legend / picked from search): brighten its provinces
  if (S.polHi) {
    ctx.save(); ctx.lineWidth = 2;
    for (const p of P) if (p.rings && polOf(p).key === S.polHi) {
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

// per-overlay province coverage counts (key -> #provinces), cached until the overlay changes;
// shared by the legend and the entity search so neither rescans P on every keystroke
let _cov = { overlay: null, map: null };
export function coverage() {
  if (_cov.overlay === S.overlay && _cov.map) return _cov.map;
  const m = new Map();
  for (const p of P) { const k = polOf(p).key; if (k) m.set(k, (m.get(k) || 0) + 1); }
  _cov = { overlay: S.overlay, map: m };
  return m;
}
const polTable = () => S.overlay === "culture" ? CULTURES : S.overlay === "faith" ? RELIGIONS : COUNTRIES;

// the coverage-ranked, scrollable legend (Nations/Cultures/Religions); hides when not political
export function renderPolLegend() {
  if (!isPolitical()) { polLegend.hidden = true; return; }
  const table = polTable(), cov = coverage();
  const rows = [...cov.entries()].filter(([k]) => table[k]).sort((a, b) => b[1] - a[1] || table[a[0]].name.localeCompare(table[b[0]].name));
  const title = S.overlay === "culture" ? "Cultures" : S.overlay === "faith" ? "Religions" : "Nations";
  let html = `<div class="lg-h">${title} · ${rows.length}</div><div class="leg-scroll">`;
  for (const [k, n] of rows) { const e = table[k];
    html += `<button class="legrow" data-key="${k}"><span class="sw" style="background:${e.color}"></span><span class="leg-nm">${e.name}</span><span class="km">${n}</span></button>`;
  }
  polLegend.innerHTML = html + `</div>`;
  polLegend.hidden = false;
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
  _cov = { overlay: null, map: null };   // province keys just changed — invalidate the coverage cache
}
