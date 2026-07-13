import { BUNDLE, P, fmtInt, cam, VIEW, stage, pxr, pyr, px, py, cssVar, terrainRgb, worldW, provPath, provBoxHas, clampPan, provGeo, polOf, isPolitical, TRADE_GOODS, S } from "./core.mjs";
import { draw, zoomAt, resize, focusProvinceFit, applyHash, hasDeepLink } from "./main.mjs";
import { atLeast, BAND } from "./bands.mjs";
import { loadPlots, bonusIconRect } from "./plots.mjs";
import { renderPolLegend, focusEntity, coverage, overlayEntity, politicsBlock, ensurePolitical, politicalReady } from "./overlays/political.mjs";
import { startLive, stopLive, liveToBackground, liveActive, liveState, controlLive, LIVE_RATES } from "./overlays/live.mjs";
import { createSearchBox } from "./searchbox.mjs";
import { techMatches, techRowHtml, pickTech } from "./techtree.mjs";
stage.addEventListener("wheel", e => {
  e.preventDefault();
  const r = stage.getBoundingClientRect();
  zoomAt(e.clientX - r.left, e.clientY - r.top, Math.exp(-e.deltaY * 0.0016));
}, { passive: false });
let lastX = 0, lastY = 0, panMoved = false;
stage.addEventListener("mousedown", e => {
  if (e.button !== 0) return;
  S.dragging = true; panMoved = false; lastX = e.clientX; lastY = e.clientY;
  stage.classList.add("grabbing");
});
window.addEventListener("mousemove", e => {
  if (!S.dragging) return;
  const dx = e.clientX - lastX, dy = e.clientY - lastY;
  if (Math.abs(dx) + Math.abs(dy) > 2) panMoved = true;
  cam.x += dx; cam.y += dy; lastX = e.clientX; lastY = e.clientY;
  clampPan(); S.baseVersion++; draw();
});
window.addEventListener("mouseup", () => { if (S.dragging) { S.dragging = false; stage.classList.remove("grabbing"); draw(); } });

// --- touch: one finger drags to pan, two fingers pinch to zoom (mobile has no wheel/mouse) ---
stage.style.touchAction = "none";                       // stop the browser scrolling/zooming the page
let touchMode = 0, pinchDist = 0, pinchCX = 0, pinchCY = 0;   // 0 none · 1 pan · 2 pinch
stage.addEventListener("touchstart", e => {
  if (e.touches.length === 1) {
    touchMode = 1; S.dragging = true; panMoved = false;
    lastX = e.touches[0].clientX; lastY = e.touches[0].clientY;
  } else if (e.touches.length >= 2) {
    const a = e.touches[0], b = e.touches[1], r = stage.getBoundingClientRect();
    touchMode = 2; S.dragging = true; panMoved = true;
    pinchDist = Math.hypot(a.clientX - b.clientX, a.clientY - b.clientY);
    pinchCX = (a.clientX + b.clientX) / 2 - r.left; pinchCY = (a.clientY + b.clientY) / 2 - r.top;
  }
}, { passive: true });
stage.addEventListener("touchmove", e => {
  e.preventDefault();                                   // we own the gesture (touch-action:none too)
  if (touchMode === 1 && e.touches.length >= 1) {
    const t = e.touches[0], dx = t.clientX - lastX, dy = t.clientY - lastY;
    if (Math.abs(dx) + Math.abs(dy) > 2) panMoved = true;
    cam.x += dx; cam.y += dy; lastX = t.clientX; lastY = t.clientY;
    clampPan(); S.baseVersion++; draw();
  } else if (touchMode === 2 && e.touches.length >= 2) {
    const a = e.touches[0], b = e.touches[1], r = stage.getBoundingClientRect();
    const d = Math.hypot(a.clientX - b.clientX, a.clientY - b.clientY);
    const cx = (a.clientX + b.clientX) / 2 - r.left, cy = (a.clientY + b.clientY) / 2 - r.top;
    cam.x += cx - pinchCX; cam.y += cy - pinchCY;       // pan by the pinch centre's movement…
    if (pinchDist > 0) zoomAt(cx, cy, d / pinchDist);   // …then zoom by the finger-distance ratio (zoomAt draws)
    else { clampPan(); S.baseVersion++; draw(); }
    pinchDist = d; pinchCX = cx; pinchCY = cy;
  }
}, { passive: false });
function endTouch(e) {
  if (e.touches && e.touches.length === 1) {            // lifted one of two fingers → resume single-finger pan
    touchMode = 1; lastX = e.touches[0].clientX; lastY = e.touches[0].clientY; return;
  }
  if (e.touches && e.touches.length > 0) return;
  touchMode = 0; S.dragging = false; draw();
}
stage.addEventListener("touchend", endTouch);
stage.addEventListener("touchcancel", endTouch);

document.getElementById("zoomIn").onclick = () => zoomAt(VIEW.w/2, VIEW.h/2, 1.5);
document.getElementById("zoomOut").onclick = () => zoomAt(VIEW.w/2, VIEW.h/2, 1/1.5);
// reset to the whole world (keyboard 0 / Home — the corner-icon button is now fullscreen)
function resetView() { cam.k = 1; cam.x = 0; cam.y = 0; clampPan(); S.baseVersion++; draw(); }
// fullscreen the map stage (canvas + its overlay controls); the resize handler re-fits on change
function toggleFullscreen() {
  if (document.fullscreenElement) document.exitFullscreen();
  else stage.requestFullscreen?.();
}
document.getElementById("zoomReset").onclick = toggleFullscreen;
document.getElementById("zoomLevel").onclick = resetView;   // top-left readout doubles as reset-to-world
// the title acts as "home": reset to the world view and re-open the server picker (index.html's
// boot flow exposes it on window.__picker) as a dismissable overlay over the live map — Esc or a
// click outside returns to the map; picking a different server reloads into it. Falls back to a
// reload-to-picker if the overlay isn't available (defensive; it always is after boot).
const brandEl = document.getElementById("brand");
if (brandEl) {
  const home = () => {
    resetView();
    if (window.__picker && window.__picker.open) window.__picker.open();
    else location.href = location.pathname;
  };
  brandEl.onclick = home;
  brandEl.onkeydown = e => { if (e.key === "Enter" || e.key === " ") { e.preventDefault(); home(); } };
}
document.addEventListener("fullscreenchange", resize);
// The global keyboard shortcuts (pan / zoom / reset / fullscreen / play / Escape) are
// dispatched centrally by js/shortcuts.mjs, which calls the actions exported below.
// ---- clock: EU4-style date/time + play-pause + speed chevrons (top bar) ----
const cDate=document.getElementById("cDate");
const playBtn=document.getElementById("playBtn"), playIcon=document.getElementById("playIcon");
const speedBox=document.getElementById("speed");
// speeds 1..5 = the live session's tick rate (in-game days per real second); see live.LIVE_RATES.
// Paused is the un-playing state.
const SPEEDS = [null,
  { name: "1 day / s" },
  { name: "2 days / s" },
  { name: "4 days / s" },
  { name: "10 days / s" },
  { name: "Max" }];
let speed = 1, playing = false;
const PAUSE_ICON = '<path d="M6 5h4v14H6zM14 5h4v14h-4z"/>', PLAY_ICON = '<path d="M8 5v14l11-7z"/>';

function renderSpeed(){
  [...speedBox.children].forEach((c, i) => c.classList.toggle("on", (i + 1) <= speed));
  speedBox.classList.toggle("paused", !playing);
}
/**
 * The clock/play/speed drive the LIVE hosted session — the only thing time means now that the
 * recorded replay is gone (its data came from the server; see docs/client-server.md). Play/pause
 * toggles the session over /control; the play icon reflects the server's state from the feed.
 * The controls are inert (and the clock hidden) outside the Caravans view.
 */
// controlling playback requires a signed-in user (docs/authentication.md); auth.mjs marks the
// body when anonymous, gating both the click and the keyboard-shortcut paths
function canControl(){ return !document.body.classList.contains("auth-anon"); }
function togglePlay(){
  if (liveActive() && canControl()) controlLive(liveState() === "RUNNING" ? "pause" : "resume");
}
/** Force paused — modals call this on open; the live session keeps ticking, so this is a no-op. */
function pausePlayback(){}
// a speed chevron sets the live session's tick rate (in-game days per second) over /control
function onSpeed(level){
  if (!liveActive() || !canControl()) return;
  speed = Math.max(1, Math.min(5, level));
  controlLive("rate", LIVE_RATES[speed]);
  renderSpeed();
}
// reflect the live session's control state (and date) on the transport UI, per snapshot
function syncLiveTransport(state, date){
  const running = state === "RUNNING";
  playing = running;
  playIcon.innerHTML = running ? PAUSE_ICON : PLAY_ICON;
  playBtn.setAttribute("aria-label", running ? "Pause" : "Play");
  if (date != null) cDate.textContent = date;
  renderSpeed();
}
// show the clock (live controls) only in the Caravans/live view; clear it otherwise
function showClock(on){
  document.getElementById("clock").style.display = on ? "" : "none";
  if (!on) cDate.textContent = "";
}
playBtn.addEventListener("click", togglePlay);
// the speed selector: five chevrons ( › .. ›››››  ), the active level lit; clicking one sets the rate
SPEEDS.slice(1).forEach((sp, i) => {
  const b = document.createElement("button");
  b.className = "chev"; b.textContent = "›";
  b.setAttribute("data-tip", sp.name); b.setAttribute("aria-label", sp.name);
  b.onclick = () => onSpeed(i + 1);
  speedBox.appendChild(b);
});
renderSpeed();

// ---- a small reusable spinner for secondary async loads ----
const spinnerEl=document.getElementById("spinner");
function showSpinner(text){ if(!spinnerEl) return; spinnerEl.querySelector(".sp-txt").textContent = text||"Loading…"; spinnerEl.hidden=false; }
function hideSpinner(){ if(spinnerEl) spinnerEl.hidden=true; }

// ---- movement-cost overlay toggle (terrain-zoom elevation difficulty) ----
const costBtn=document.getElementById("costBtn"), costKey=document.getElementById("costKey");
costBtn.setAttribute("aria-pressed","false"); costBtn.style.opacity=".5";
costBtn.onclick=()=>{ S.showCost=!S.showCost; costBtn.setAttribute("aria-pressed",S.showCost);
  costBtn.style.opacity=S.showCost?"":".5"; costKey.style.display=S.showCost?"":"none"; draw(); };

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
  // the map wraps east-west, so test the cursor against each on-screen world copy by
  // shifting it into that copy's primary space (mx - m·period)
  const period = worldW();
  const L = cam.x + cam.k*VIEW.dx;
  const mMin = period>0 ? Math.floor((0-L)/period) : 0;
  const mMax = period>0 ? Math.floor((VIEW.w-L)/period) : 0;
  for(let m=mMin; m<=mMax; m++){
    const sx = mx - m*period;
    // cheap bbox pre-filter before the full point-in-polygon: a bbox miss can't be a polygon hit,
    // so this skips all but the few provinces actually under the cursor (same projection space, so
    // the result is identical). provBoxHas is a strict superset of pointInProv.
    for(const p of P){ if(p.rings && provBoxHas(p, sx, my) && pointInProv(p, sx, my)) return p; }
  }
  let best=null, bd=1e9;                                                    // else nearest centroid
  for(let m=mMin; m<=mMax; m++){
    const sx = mx - m*period;
    // land + coastal sea/lake all carry rings now (they hover/select alike); a province with no
    // outline (deep ocean, never shipped) has none and is skipped. Only a province whose bbox
    // (grown by the 9.5px centroid radius, √90) reaches the cursor can win, so cull by that first.
    for(const p of P){ if(!p.rings || !provBoxHas(p, sx, my, 10)) continue; const dx=px(p.lon)-sx, dy=py(p.lat)-my, d=dx*dx+dy*dy; if(d<bd){bd=d;best=p;} }
  }
  return bd<90 ? best : null;
}
// the plot under the cursor at texture zoom (where the per-province plot canvases + their grids
// exist), across the E-W wrap copies — used for the resource tooltip. Ring-less sea provinces are
// found too, so coastal resources tooltip like land ones. Returns the plot record, or null.
function plotAt(mx, my){
  if(!atLeast(BAND.TERRAIN)) return null;
  const period = worldW();
  const L = cam.x + cam.k*VIEW.dx;
  const mMin = period>0 ? Math.floor((0-L)/period) : 0;
  const mMax = period>0 ? Math.floor((VIEW.w-L)/period) : 0;
  for(let m=mMin; m<=mMax; m++){
    const sx = mx - m*period;
    for(const p of P){
      if(!p._grid || !p._tbox) continue;                       // only provinces whose texture canvas is built
      const b=p._tbox, X0=pxr(b.x0), X1=pxr(b.x0+b.w), Y0=pyr(b.y0), Y1=pyr(b.y0+b.h);
      if(sx<X0 || sx>=X1 || my<Y0 || my>=Y1) continue;
      const spx = b.x0 + Math.floor((sx-X0)/(X1-X0)*b.w);
      const spy = b.y0 + Math.floor((my-Y0)/(Y1-Y0)*b.h);
      // prefer a resource ICON under the cursor: the glyph is large and anchored at its plot's
      // bottom-left, so it often overlaps a neighbouring cell — scan a small neighbourhood (owner is
      // at-or-left, at-or-below the cursor) and return the plot whose icon rect covers (sx,my).
      for(let gy=spy-1; gy<=spy+2; gy++) for(let gx=spx-2; gx<=spx; gx++){
        const c = p._grid.get(gx*1e5 + gy); if(!c || !c.bonus) continue;
        const rc = bonusIconRect(c);
        if(rc && sx>=rc[0] && sx<=rc[2] && my>=rc[1] && my<=rc[3]) return c;
      }
      const q = p._grid.get(spx*1e5 + spy);
      if(q) return q;                                          // land plot found first; else the sea shelf plot
    }
  }
  return null;
}
// a plot's resource label for the tooltip, or null: its bonus (or polar sea ice), Title Cased
function resourceLabel(q){
  if(q.bonus) return prettyKey(q.bonus);
  if(q.feature === "FEATURE_ICE") return "Ice";
  return null;
}
const prettyKey = t => t.replace(/^(BONUS|FEATURE)_/,"").toLowerCase().replace(/_/g," ").replace(/\b\w/g,c=>c.toUpperCase());
// hover tooltip body — the info shown depends on the active overlay: physical (region · plots)
// or political (the active dimension + region)
function provTip(best){
  const reg = (best.region||"—").replace(/_/g," ").replace(" region","");
  let h = `<b>${best.name}</b> <span class="r">${best.type.toLowerCase()}</span>`;
  if (isPolitical()){
    const e = polOf(best).e;
    const label = S.overlay==="culture"?"Culture" : S.overlay==="faith"?"Faith" : "Nation";
    h += `<br><span class="r">${e ? `<span class="dot" style="background:${e.color}"></span>${label} · ${e.name}` : `${label} · —`}</span>`
       + `<br><span class="r">${reg}</span>`;
  } else {
    h += `<br><span class="r">${reg} · ${best.plots} plots</span>`;
    // a city (Anbennar city_terrain) — its concentrated development, in gold (see docs/urban-plots.md)
    if (best.city) h += `<br><span class="r" style="color:var(--gold,#c9a24a)">City · development ${best.dev || 0}</span>`;
    // per-province trade good (physical view), with its colour dot — mirrors the political dimension line
    const g = TRADE_GOODS && TRADE_GOODS.prov[best.id] && TRADE_GOODS.goods[TRADE_GOODS.prov[best.id]];
    if (g) h += `<br><span class="r"><span class="dot" style="background:${g.color}"></span>${g.name}</span>`;
  }
  return h;
}
// a cave-entrance / teleporter glyph under the cursor, from the markers the last frame recorded
// (main.paint resets S.markers, drawCaveEntrances/teleportMark push them). Its own hit-test since
// the glyphs are drawn over the map, not province polygons.
function markerAt(mx, my){
  const m = S.markers; if(!m) return null;
  for(const k of m){ const dx=mx-k.x, dy=my-k.y; if(dx*dx+dy*dy <= k.r*k.r) return k; }
  return null;
}
stage.addEventListener("mousemove", e=>{
  if(S.dragging) return;                       // panning — skip hover work
  const r=stage.getBoundingClientRect(), mx=e.clientX-r.left, my=e.clientY-r.top;
  const mk = markerAt(mx, my);                  // a cave-entrance/teleporter glyph takes priority
  if(mk){ S.hoverProv=null; tip.innerHTML=mk.label;
    tip.style.left=Math.min(mx+14, r.width-230)+"px"; tip.style.top=(my+14)+"px"; tip.classList.add("on");
    draw(); return; }
  const best = provinceAt(mx, my);
  const hit = plotAt(mx, my);                   // resourced plot under cursor (texture zoom)
  const res = hit ? resourceLabel(hit) : null;
  if(best || res){ S.hoverProv=best;
    let html = best ? provTip(best) : "";
    if(res) html += `${best?"<br>":""}<span class="r">◆ ${res}</span>`;
    tip.innerHTML=html;
    tip.style.left=Math.min(mx+14, r.width-230)+"px"; tip.style.top=(my+14)+"px"; tip.classList.add("on");
  } else { S.hoverProv=null; tip.classList.remove("on"); }
  draw();
});
stage.addEventListener("mouseleave", ()=>{ S.hoverProv=null; tip.classList.remove("on"); draw(); });
stage.addEventListener("click", e=>{
  if(panMoved){ panMoved=false; return; }    // this "click" was the end of a drag
  if(e.detail>1) return;                      // 2nd click of a double-click: dblclick zooms — don't
                                              // toggle the just-selected province back off (the flash)
  const r=stage.getBoundingClientRect(), mx=e.clientX-r.left, my=e.clientY-r.top;
  // the click selects the province under the cursor (toggles off if re-clicked)
  const prov = provinceAt(mx, my);
  if (prov) selectProvince(S.selectedProv===prov ? null : prov);
});
// double-click / double-tap zooms in, centred on the point (touch double-tap fires dblclick too)
stage.addEventListener("dblclick", e=>{
  const r=stage.getBoundingClientRect();
  zoomAt(e.clientX-r.left, e.clientY-r.top, 2.5);
});

// ---- rail ----
const rail=document.getElementById("rail");
const railwrap=document.getElementById("railwrap");
const appEl=document.querySelector(".app");
// open/collapse the right sidebar. `.rail-open` on .app pushes the stage's right edge in so the map
// RESIZES to fit beside the panel (styles.css) instead of being covered; the ResizeObserver below
// refits the canvas as it animates.
function showRail(open){ railwrap.classList.toggle("open", !!open); appEl.classList.toggle("rail-open", !!open); }

// --- user-resizable panel width: drag the .rail-resize handle on the panel's left edge. The map
// (the stage) shrinks/grows with it live; the chosen width persists across sessions. ---
(function initRailResize(){
  const handle=document.getElementById("railResize");
  if(!handle) return;
  const MIN=300, MAX=680;
  const saved=parseInt(localStorage.getItem("railWidth")||"",10);
  if(saved>=MIN && saved<=MAX) appEl.style.setProperty("--rail-w", saved+"px");
  let startX=0, startW=0;
  function onMove(e){
    const w=Math.max(MIN, Math.min(MAX, startW + (startX - e.clientX)));   // drag left → wider
    appEl.style.setProperty("--rail-w", w+"px");
  }
  function onUp(){
    appEl.classList.remove("rail-resizing");
    window.removeEventListener("pointermove", onMove);
    window.removeEventListener("pointerup", onUp);
    localStorage.setItem("railWidth", String(Math.round(railwrap.getBoundingClientRect().width)));
  }
  handle.addEventListener("pointerdown", e=>{
    e.preventDefault();
    startX=e.clientX; startW=railwrap.getBoundingClientRect().width;
    appEl.classList.add("rail-resizing");
    window.addEventListener("pointermove", onMove);
    window.addEventListener("pointerup", onUp);
  });
})();
function worldRail(){
  return `<div class="runmeta">
    <div class="rm-title serif" style="font-size:16px">WorldMap</div>
    <div class="rm-sub">Anbennar · the whole world, real Civ4 terrain</div>
    <div class="metagrid">
      <div class="metacell"><div class="k">Land provinces</div><div class="v">${P.length}</div></div>
      <div class="metacell"><div class="k">Zoom</div><div class="v" style="font-size:15px">to the plot</div></div>
    </div></div>
    <p class="footnote">The full world, rendered from the engine's real terrain. Drag to pan, scroll to zoom — keep zooming past the continent view to resolve any province into its terrain plot by plot (textures, hillshade from the heightmap, rivers, features). Hover the map to read a province. Switch to <b>Caravans</b> to watch the live server session — the colony and its marching bands — in real time.</p>`;
}
// the map plane (Overworld/Underworld) — the physical base. Underworld dims the surface to
// a ghost and lights the underground CAVERN provinces in place (see main.drawUnderworld).
function setPlane(pl){
  S.plane = pl;
  S.baseVersion++;   // the political ledger is per-plane (surface vs underground) — force its rebuild
  // repaint every plane button (data-plane) wherever it lives now — the Globe advisor's
  // sub-control strip holds these buttons (see advisors.mjs), not the old #planeToggle spot
  document.querySelectorAll("[data-plane]").forEach(b=> b.setAttribute("aria-pressed", b.dataset.plane===pl));
  draw();
}
// the overlay (None / Nation / Culture / Faith / Caravans) — one at a time over the active plane.
// "Caravans" is the live server view (S.overlay === "live"): the running colony + its caravans.
function setOverlay(ov){
  const was = S.overlay;
  const live = ov === "live";
  S.overlay = ov;
  // repaint every overlay button (data-ov) wherever it lives — the Foreign advisor's sub-control
  // strip carries the Nation/Culture buttons now (see advisors.mjs), not the old #overlayToggle
  document.querySelectorAll("[data-ov]").forEach(b=> b.setAttribute("aria-pressed", b.dataset.ov===ov));
  const pol = isPolitical();
  S.polHi = null;
  // the clock/play/speed only mean something in the live Caravans view — show them there, drive
  // the hosted session (togglePlay/onSpeed); connect/disconnect the SSE feed on enter/leave.
  showClock(live);
  // leaving the live overlay keeps the SSE feed alive in the background (its HUD/dots/clock go away)
  // so the advisor roster stays live in every advisor; only a server switch / full reset stops it
  if (was === "live" && !live) liveToBackground();
  if (live) { speed = 1; startLive(draw, syncLiveTransport); }
  S.selectedProv = null;                            // start each overlay on its own overview
  showRail(false);
  updateSearchContext();                            // the search box searches provinces / nations / cultures / faiths
  if (pol) {
    if (!politicalReady()) showSpinner("Loading political data…");  // the lazy political.js fetch
    ensurePolitical().then(() => { hideSpinner(); renderPolLegend(); renderRail(); draw(); })
      .catch(() => hideSpinner());
  } else {
    renderPolLegend();                              // not political → hide the political legend
  }
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
  S.selectedProv = p;
  if (p && !p._plots) loadPlots(p);   // stream in the server-generated terrain for the breakdown
  showRail(!!p);                     // selecting opens the info panel; deselecting collapses it
  renderRail(); draw();
}
function provinceRail(p) {
  const g = provGeo(p);
  // each tier is [displayName, rawClausewitzKey]; show the key in parentheses after the name
  const crumbs = [g.continent, g.superRegion, g.region, g.area].filter(t => t && t[0])
    .map(t=>`<span>${t[0]}${t[1]?` <span class="pv-key">(${t[1]})</span>`:''}</span>`)
    .join('<span class="crumb-sep">›</span>');
  const coord = `${Math.abs(p.lat).toFixed(2)}°${p.lat>=0?"N":"S"}, ${Math.abs(p.lon).toFixed(2)}°${p.lon>=0?"E":"W"}`;
  let terrainHtml;
  if (p._plots && p._plots.length) {
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
  } else if (p._plots) {   // loaded but empty (deep ocean with no shelf)
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
        ${p.type==="SEA"||p.type==="LAKE"
          ? `<div class="stat"><div class="k">Water area</div><div class="v">${p.plots}</div></div>
             <div class="stat"><div class="k">Shelf plots</div><div class="v">${p._plots?p._plots.length:"—"}</div></div>`
          : `<div class="stat"><div class="k">Land plots</div><div class="v">${p.plots}</div></div>
             <div class="stat"><div class="k">Water plots</div><div class="v">${p.waterPlots||0}</div></div>`}
        <div class="stat"><div class="k">Neighbours</div><div class="v">${(p.nb||[]).length}</div></div>
      </div>
      <div class="metagrid" style="margin-top:8px">
        <div class="metacell"><div class="k">Coordinates</div><div class="v" style="font-size:13px">${coord}</div></div>
        <div class="metacell"><div class="k">Winter</div><div class="v" style="font-size:13px">${p.winter?prettyId(p.winter):"—"}</div></div>
      </div>
      ${politicsBlock(p)}
      ${terrainHtml}
    </div>`;
  document.getElementById("backProv").onclick = ()=>{ S.selectedProv=null; showRail(false); renderRail(); draw(); };
}
// The city info panel — shown in the rail when a city (an Anbennar city_terrain province,
// e.g. Dhenijansar) is selected, in place of the generic province detail. Reuses the same
// markup vocabulary as provinceRail (.detail / .d-head / .statrow / .metagrid / politicsBlock)
// so it matches the panel's look. See docs/urban-plots.md.
function cityRail(p) {
  const g = provGeo(p);
  const crumbs = [g.continent, g.superRegion, g.region, g.area].filter(t => t && t[0])
    .map(t => `<span>${t[0]}${t[1] ? ` <span class="pv-key">(${t[1]})</span>` : ''}</span>`)
    .join('<span class="crumb-sep">›</span>');
  const coord = `${Math.abs(p.lat).toFixed(2)}°${p.lat >= 0 ? "N" : "S"}, ${Math.abs(p.lon).toFixed(2)}°${p.lon >= 0 ? "E" : "W"}`;
  const dev = p.dev || 0;
  const rank = dev >= 30 ? "Metropolis" : dev >= 15 ? "City" : "Town";
  const tgKey = TRADE_GOODS && TRADE_GOODS.prov[p.id];
  const tg = tgKey && TRADE_GOODS.goods[tgKey];
  const tgHtml = tg ? `<span class="dot" style="background:${tg.color}"></span>${tg.name}` : "—";
  // the urban core size, once the province's plots have streamed in
  let coreHtml = "";
  if (p._plots && p._plots.length) {
    const urban = p._plots.filter(q => q.urban).length;
    coreHtml = `<div class="stat"><div class="k">Urban core</div><div class="v">${urban}<small style="font-size:11px;color:var(--ink-soft)"> plots</small></div></div>`;
  }
  rail.innerHTML = `
    <button class="backbtn" id="backProv">← Back</button>
    <div class="detail">
      <div class="d-head"><h2 class="serif">${p.name}</h2></div>
      <div class="rm-sub" style="color:var(--ink-soft);margin-top:-6px"><span class="r" style="color:var(--gold,#c9a24a)">${rank}</span> · city · province ${p.id}</div>
      ${crumbs ? `<div class="pv-crumbs">${crumbs}</div>` : ""}
      <div class="statrow" style="margin-top:12px">
        <div class="stat"><div class="k">Development</div><div class="v" style="color:var(--gold,#c9a24a)">${dev}</div></div>
        ${coreHtml}
        <div class="stat"><div class="k">Land plots</div><div class="v">${p.plots}</div></div>
      </div>
      <div class="metagrid" style="margin-top:8px">
        <div class="metacell"><div class="k">Trade good</div><div class="v" style="font-size:13px">${tgHtml}</div></div>
        <div class="metacell"><div class="k">Coordinates</div><div class="v" style="font-size:13px">${coord}</div></div>
      </div>
      ${politicsBlock(p)}
      <p class="footnote">A city of Anbennar — its EU4 development (ADM + DIP + MIL) concentrated in the province's built-up urban core. Zoom in to the plot to see the city itself.</p>
    </div>`;
  document.getElementById("backProv").onclick = () => { S.selectedProv = null; showRail(false); renderRail(); draw(); };
  // owner/culture/faith load lazily with the political layer — refresh once it's ready
  if (!politicalReady()) ensurePolitical().then(() => { if (S.selectedProv === p) renderRail(); });
}
function renderRail(){
  if (S.selectedProv) {
    if (S.selectedProv.city) { cityRail(S.selectedProv); return; }
    provinceRail(S.selectedProv); return;
  }
  rail.innerHTML = worldRail();
}
// ---- theme toggle ----
const themeBtn=document.getElementById("themeBtn");
themeBtn.onclick=()=>{
  const cur=document.documentElement.getAttribute("data-theme")
    || (matchMedia("(prefers-color-scheme: dark)").matches?"dark":"light");
  document.documentElement.setAttribute("data-theme", cur==="dark"?"light":"dark");
  draw(); if(S.selected!==null) renderRail();
};
matchMedia("(prefers-color-scheme: dark)").addEventListener("change", ()=>{ draw(); });
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
function goToProvince(p) {
  focusProvinceFit(p.id);         // frame the whole province (centred, filling most of the canvas)
  selectProvince(p);              // and open its detail panel
}
// Esc / close button: collapse the sidebar and drop any selection. The camera stays put — closing
// the panel never moves or rezooms the map (selecting/deselecting is decoupled from the camera).
// Returns true if it actually did something (so the caller can swallow the key).
function closePanel() {
  const acted = railwrap.classList.contains("open") || S.selectedProv || S.selected != null;
  S.selectedProv = null; S.selected = null;
  showRail(false); renderRail(); draw();
  return !!acted;
}
document.getElementById("railClose").onclick = closePanel;
// the top-bar search: provinces by name/id, or — in a political overlay — nations / cultures /
// faiths. Behaviour (keyboard nav, dropdown, clear, blur) lives in the shared searchbox widget;
// here we supply just the query, the row markup and what a pick does.
const provinceSearch = createSearchBox({
  input: searchInput, results: searchResults, clear: searchClear,
  search(q) {
    if (S.advisor === "technology")                     // §5: the Technology advisor searches techs
      return techMatches(q).map(t => ({ kind: "tech", t }));
    q = q.toLowerCase();
    const ent = overlayEntity();
    if (ent) {                                          // nations / cultures / religions
      if (!Object.keys(ent.table).length) return [];    // political layer still loading (see renderEmpty)
      const cov = coverage(), scored = [];
      for (const [key, e] of Object.entries(ent.table)) {
        const name = e.name.toLowerCase(); let score = -1;
        if (name === q) score = 90; else if (name.startsWith(q)) score = 70; else if (name.includes(q)) score = 40;
        if (score >= 0) scored.push({ kind: ent.kind, key, name: e.name, color: e.color, n: cov.get(key) || 0, score });
      }
      scored.sort((a, b) => b.score - a.score || b.n - a.n || a.name.localeCompare(b.name));
      return scored.slice(0, 12);
    }
    const isNum = /^\d+$/.test(q), scored = [];        // province search (by name or id)
    for (const p of P) {
      if (p.type !== "LAND") continue;
      let score = -1;
      if (isNum) { const ids = String(p.id); if (ids === q) score = 100; else if (ids.startsWith(q)) score = 55; }
      if (score < 0) { const name = p.name.toLowerCase(); if (name === q) score = 90; else if (name.startsWith(q)) score = 70; else if (name.includes(q)) score = 40; }
      if (score >= 0) scored.push({ p, score });
    }
    scored.sort((a, b) => b.score - a.score || b.p.plots - a.p.plots || a.p.name.localeCompare(b.p.name));
    return scored.slice(0, 12).map(s => ({ kind: "province", p: s.p }));
  },
  renderRow(m, i, active) {
    if (m.kind === "tech") return techRowHtml(m.t, i, active);
    const act = active ? " active" : "";
    if (m.kind === "province") {
      const reg = (provGeo(m.p).region || [])[0] || "";
      return `<div class="search-row${act}" role="option" data-i="${i}">
        <span class="sr-name">${m.p.name}</span><span class="sr-id">#${m.p.id}</span>
        <span class="sr-meta">${reg}</span></div>`;
    }
    return `<div class="search-row${act}" role="option" data-i="${i}">
      <span class="sw" style="background:${m.color}"></span><span class="sr-name">${m.name}</span>
      <span class="sr-meta">${m.n} prov</span></div>`;
  },
  onPick(m) {
    if (m.kind === "tech") { pickTech(m.t); return; }  // §5: select + centre the tech node
    if (m.kind === "province") goToProvince(m.p);
    else focusEntity(m.key);                           // zoom to the polity's largest province + spotlight it
  },
  renderEmpty() {
    const ent = overlayEntity();
    if (ent && !Object.keys(ent.table).length) return `<div class="search-empty">Loading…</div>`;
    return `<div class="search-empty">No matches.</div>`;
  },
  // first Esc collapses the panel / returns the camera; otherwise clear the box and blur
  onEscape(e, api) {
    if (closePanel()) { e.preventDefault(); return; }
    api.reset(); searchInput.blur();
  },
});
function updateSearchContext() {
  searchInput.placeholder = S.advisor === "technology" ? "Search techs…"
    : (overlayEntity() || {}).ph || "Find a province…";
  provinceSearch.refresh();
}
// ---- camera POV toggle (God / Timeline) ----
// the camera POV is always God today (Timeline is a future mode); the old #povToggle group was
// retired with the advisor-selector restructure, so this is a guarded no-op kept for boot()/parity.
const povToggle = document.getElementById("povToggle");
function setPov(pov){
  S.pov = pov;
  if (povToggle) povToggle.querySelectorAll("button").forEach(b => b.setAttribute("aria-pressed", b.dataset.pov === pov));
  draw();
}
if (povToggle) povToggle.querySelectorAll("button").forEach(b =>
  b.addEventListener("click", () => { if (!b.disabled) setPov(b.dataset.pov); }));

// ---- responsive controls menu: the hamburger tucks the toggle groups on narrow screens ----
const menuBtn = document.getElementById("menuBtn");
const mapControlsEl = document.getElementById("mapControls");
if (menuBtn) {
  menuBtn.addEventListener("click", e => {
    e.stopPropagation();
    const open = document.body.classList.toggle("menu-open");
    menuBtn.setAttribute("aria-expanded", open);
  });
  document.addEventListener("click", e => {          // click outside the dropdown closes it
    if (!document.body.classList.contains("menu-open")) return;
    if (mapControlsEl.contains(e.target) || menuBtn.contains(e.target)) return;
    document.body.classList.remove("menu-open"); menuBtn.setAttribute("aria-expanded", "false");
  });
}

// on phones the whole bar collapses to brand + hamburger — the search and clock move into the
// drawer alongside the toggle groups, and back to the top-right on wider screens
const searchBarEl = document.querySelector(".searchbar");
const clockEl = document.getElementById("clock");
const topRightEl = document.querySelector(".topright");
const phoneMq = matchMedia("(max-width: 520px)");
function applyBarLayout() {
  if (phoneMq.matches) { mapControlsEl.append(searchBarEl, clockEl); }
  else { topRightEl.append(searchBarEl, clockEl); }   // append restores the original trailing order
}
phoneMq.addEventListener("change", applyBarLayout);
applyBarLayout();

// ---- plane + overlay toggles ----
document.querySelectorAll("#planeToggle button").forEach(b =>
  b.addEventListener("click", () => setPlane(b.dataset.plane)));
document.querySelectorAll("#overlayToggle button").forEach(b =>
  b.addEventListener("click", () => setOverlay(b.dataset.ov)));
// top-bar buttons carry data-tip too — wire them into the same tooltip mechanism as the map buttons
document.querySelectorAll(".topbar [data-tip]").forEach(el => {
  el.addEventListener("mouseenter", () => { clearTimeout(tipTimer); tipTimer = setTimeout(() => showBtnTip(el), 320); });
  el.addEventListener("mouseleave", hideBtnTip);
  el.addEventListener("mousedown", hideBtnTip);
});

export { renderRail, resetView, toggleFullscreen, togglePlay, pausePlayback, closePanel,
         setOverlay, setPlane, updateSearchContext, showRail, selectProvince };

export function boot() {
  // Refit the canvas whenever the stage's box changes — window resize, fullscreen, AND the panel
  // opening/closing or being drag-resized (all of which shrink/grow the stage). One observer covers
  // every case, including live tracking during the width transition. (Replaces the window resize
  // listener; fullscreenchange still calls resize directly above as a belt-and-braces.)
  new ResizeObserver(() => resize()).observe(stage);
  resize();
  // floating controls over the map (zoom buttons, cost key, political legend) must not fall through
  // to the stage's pan/zoom/pick handlers — the same guard the minimap and live HUD/log bar use.
  document.querySelectorAll(".zoomctl, #costKey, #polLegend").forEach(elm =>
    ["pointerdown", "mousedown", "click", "touchstart", "wheel"].forEach(t =>
      elm.addEventListener(t, e => e.stopPropagation(), { passive: true })));
  setPov(S.pov);              // paints the camera-POV toggle (default: God)
  setPlane(S.plane);          // paints the plane toggle
  setOverlay(S.overlay);      // paints the overlay chrome/rail (default: none → plain Overworld)
  // apply the ?p=/#p= deep link AFTER first layout — focusProvince needs a sized VIEW, so calling
  // it inline at boot (before the stage has laid out) silently no-ops
  requestAnimationFrame(() => requestAnimationFrame(applyHash));
}
