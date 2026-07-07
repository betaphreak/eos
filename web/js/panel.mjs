import { BUNDLE, P, J, t0, t1, fmtDate, fmtInt, cam, VIEW, stage, pxr, pyr, px, py, cssVar, terrainRgb, PLOT_INDEX, K_TEX, lerpField, journeyPos, worldW, MAXD, heatColor, provPath, clampPan, destSet, S } from "./core.mjs";
import { draw, zoomAt, resize, focusProvinceFit, applyHash, hasDeepLink } from "./main.mjs";
import { loadPlots, bonusIconRect } from "./plots.mjs";
stage.addEventListener("wheel", e => {
  e.preventDefault();
  const r = stage.getBoundingClientRect();
  camBeforeFocus = null;                               // manual zoom discards the focus-return point
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
  if (Math.abs(dx) + Math.abs(dy) > 2) { panMoved = true; camBeforeFocus = null; }   // dragging discards the focus-return point
  cam.x += dx; cam.y += dy; lastX = e.clientX; lastY = e.clientY;
  clampPan(); S.baseVersion++; draw();
});
window.addEventListener("mouseup", () => { if (S.dragging) { S.dragging = false; stage.classList.remove("grabbing"); draw(); } });

// --- touch: one finger drags to pan, two fingers pinch to zoom (mobile has no wheel/mouse) ---
stage.style.touchAction = "none";                       // stop the browser scrolling/zooming the page
let touchMode = 0, pinchDist = 0, pinchCX = 0, pinchCY = 0;   // 0 none · 1 pan · 2 pinch
stage.addEventListener("touchstart", e => {
  camBeforeFocus = null;
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
document.addEventListener("fullscreenchange", resize);
// keyboard: WASD / arrows pan · +/- zoom · 0 or Home reset to world · F fullscreen
window.addEventListener("keydown", e => {
  if (e.metaKey || e.ctrlKey || e.altKey) return;
  if (e.target instanceof HTMLElement && e.target.matches("input, textarea")) return;   // don't hijack typing
  const step = Math.max(40, Math.min(VIEW.w, VIEW.h) * 0.12);
  switch (e.key) {
    case "Escape": if (closePanel()) { e.preventDefault(); } return;   // collapse the sidebar + restore camera
    case "w": case "W": case "ArrowUp":    cam.y += step; break;
    case "s": case "S": case "ArrowDown":  cam.y -= step; break;
    case "a": case "A": case "ArrowLeft":  cam.x += step; break;
    case "d": case "D": case "ArrowRight": cam.x -= step; break;
    case "+": case "=":  e.preventDefault(); camBeforeFocus = null; return zoomAt(VIEW.w/2, VIEW.h/2, 1.5);
    case "-": case "_":  e.preventDefault(); camBeforeFocus = null; return zoomAt(VIEW.w/2, VIEW.h/2, 1/1.5);
    case "0": case "Home":  e.preventDefault(); camBeforeFocus = null; return resetView();
    case "f": case "F":  e.preventDefault(); return toggleFullscreen();
    default: return;                                   // leave other keys alone
  }
  camBeforeFocus = null;                               // manual pan discards the focus-return point
  e.preventDefault(); clampPan(); S.baseVersion++; draw();
});
// ---- timeline ----
const scrub=document.getElementById("scrub"), dNow=document.getElementById("dNow");
document.getElementById("dLo").textContent = fmtDate(t0);
document.getElementById("dHi").textContent = fmtDate(t1);
function setT(t, fromInput){
  S.curT = Math.max(t0, Math.min(t1, t));
  dNow.textContent = fmtDate(S.curT);
  if(!fromInput) scrub.value = Math.round((S.curT-t0)/(t1-t0)*1000);
  draw();
  if (S.selected!==null) updateDetailLive();
}
scrub.addEventListener("input", ()=> setT(t0 + (+scrub.value/1000)*(t1-t0), true));

let playing=false, speed=2, raf=0, lastTs=0;
const DAYS_PER_SEC = {1:120, 2:340, 3:900};
const playBtn=document.getElementById("playBtn"), playIcon=document.getElementById("playIcon");
function tick(ts){
  if(!playing) return;
  if(lastTs){ const dt=(ts-lastTs)/1000; setT(S.curT + dt*DAYS_PER_SEC[speed]); if(S.curT>=t1){ pause(); } }
  lastTs=ts; raf=requestAnimationFrame(tick);
}
function play(){ if(S.curT>=t1) setT(t0); playing=true; lastTs=0; playIcon.innerHTML='<path d="M6 5h4v14H6zM14 5h4v14h-4z"/>'; playBtn.setAttribute("aria-label","Pause"); raf=requestAnimationFrame(tick); }
function pause(){ playing=false; cancelAnimationFrame(raf); playIcon.innerHTML='<path d="M8 5v14l11-7z"/>'; playBtn.setAttribute("aria-label","Play"); }
playBtn.addEventListener("click", ()=> playing?pause():play());
const reduce = matchMedia("(prefers-reduced-motion: reduce)").matches;

const speedBox=document.getElementById("speed");
[[1,"0.5×"],[2,"1×"],[3,"2×"]].forEach(([v,lab])=>{
  const b=document.createElement("button"); b.textContent=lab; b.setAttribute("aria-pressed", v===speed);
  b.onclick=()=>{ speed=v; [...speedBox.children].forEach(c=>c.setAttribute("aria-pressed", c===b)); };
  speedBox.appendChild(b);
});

// ---- legend ----
const legend=document.getElementById("legend");
legend.innerHTML = '<div class="lg-h">Caravans → destination</div>';
J.forEach(j=>{
  const b=document.createElement("button"); b.className="legrow"; b.setAttribute("aria-pressed","false");
  b.innerHTML = `<span class="dot" style="background:${j.color}"></span><span>${j.dest}</span><span class="km">${j.provinceCount} prov</span>`;
  b.onclick=()=> selectJourney(S.selected===j.idx?null:j.idx);
  j._leg=b; legend.appendChild(b);
});
// choropleth key
const heatKey=document.createElement("div"); heatKey.className="heatkey"; heatKey.id="heatkey";
heatKey.innerHTML=`<div class="lg-h">Caravan-days · shading</div><div class="hk-bar"></div>
  <div class="hk-scale"><span>less</span><span>${MAXD}</span></div>`;
legend.appendChild(heatKey);

// ---- heat toggle ----
const heatBtn=document.getElementById("heatBtn");
heatBtn.setAttribute("aria-pressed","true");
heatBtn.onclick=()=>{ S.showHeat=!S.showHeat; heatBtn.setAttribute("aria-pressed",S.showHeat);
  heatBtn.style.opacity=S.showHeat?"":".5"; heatKey.style.display=S.showHeat?"":"none"; draw(); };

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
    for(const p of P){ if(p.rings && pointInProv(p, sx, my)) return p; }   // exact polygon hit
  }
  let best=null, bd=1e9;                                                    // else nearest centroid
  for(let m=mMin; m<=mMax; m++){
    const sx = mx - m*period;
    // land + coastal sea/lake all carry rings now (they hover/select alike); a province with no
    // outline (deep ocean, never shipped) has none and is skipped
    for(const p of P){ if(!p.rings) continue; const dx=px(p.lon)-sx, dy=py(p.lat)-my, d=dx*dx+dy*dy; if(d<bd){bd=d;best=p;} }
  }
  return bd<90 ? best : null;
}
// the plot under the cursor at texture zoom (where the per-province plot canvases + their grids
// exist), across the E-W wrap copies — used for the resource tooltip. Ring-less sea provinces are
// found too, so coastal resources tooltip like land ones. Returns the plot record, or null.
function plotAt(mx, my){
  if(cam.k < K_TEX) return null;
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
stage.addEventListener("mousemove", e=>{
  if(S.dragging) return;                       // panning — skip hover work
  const r=stage.getBoundingClientRect(), mx=e.clientX-r.left, my=e.clientY-r.top;
  const best = provinceAt(mx, my);
  const hit = plotAt(mx, my);                   // resourced plot under cursor (texture zoom)
  const res = hit ? resourceLabel(hit) : null;
  if(best || res){ S.hoverProv=best;
    let html = "";
    if(best) html = `<b>${best.name}</b> <span class="r">${best.type.toLowerCase()}</span><br><span class="r">${(best.region||"—").replace(/_/g," ").replace(" region","")} · ${best.plots} plots${best.days?` · ${best.days} caravan-days`:""}</span>`;
    if(res) html += `${best?"<br>":""}<span class="r">◆ ${res}</span>`;
    tip.innerHTML=html;
    tip.style.left=Math.min(mx+14, r.width-230)+"px"; tip.style.top=(my+14)+"px"; tip.classList.add("on");
  } else { S.hoverProv=null; tip.classList.remove("on"); }
  draw();
});
stage.addEventListener("mouseleave", ()=>{ S.hoverProv=null; tip.classList.remove("on"); draw(); });
stage.addEventListener("click", e=>{
  if(panMoved){ panMoved=false; return; }    // this "click" was the end of a drag
  const r=stage.getBoundingClientRect(), mx=e.clientX-r.left, my=e.clientY-r.top;
  // in caravan mode a click near a route's current marker selects that journey
  if (S.mode==="caravan") {
    let best=null,bd=1e9;
    J.forEach(j=>{ const d=j.keys[j.keys.length-1]; const dx=px(d.lon)-mx,dy=py(d.lat)-my,dd=dx*dx+dy*dy; if(dd<bd){bd=dd;best=j;} });
    if(best && bd<160){ selectJourney(S.selected===best.idx?null:best.idx); return; }
  }
  // otherwise the click selects the province under the cursor (toggles off if re-clicked)
  const prov = provinceAt(mx, my);
  if (prov) selectProvince(S.selectedProv===prov ? null : prov);
});
// double-click zooms so the whole province fits the viewport, centred on it
stage.addEventListener("dblclick", e=>{
  const r=stage.getBoundingClientRect(), mx=e.clientX-r.left, my=e.clientY-r.top;
  const prov = provinceAt(mx, my);
  if (prov) goToProvinceFit(prov);
});

// ---- rail ----
const rail=document.getElementById("rail");
const railwrap=document.getElementById("railwrap");
// open/collapse the right sidebar; the top-right controls shift left (.rail-open) to clear it
function showRail(open){ railwrap.classList.toggle("open", !!open); stage.classList.toggle("rail-open", !!open); }
function selectJourney(idx){
  S.selectedProv=null;               // journey selection replaces any province detail
  S.selected=idx;
  J.forEach(j=> j._leg.setAttribute("aria-pressed", j.idx===idx));
  showRail(idx!=null);               // a picked journey shows its detail; deselect collapses
  renderRail(); draw();
}
function railMeta(){
  const m=BUNDLE.meta;
  const span = ((t1-t0)/365.25).toFixed(1);
  return `<div class="runmeta">
    <div class="rm-title serif" style="font-size:16px">Run summary</div>
    <div class="rm-sub"><code class="mono" style="color:var(--accent)">${m.scenario}</code> · seed ${m.seed}</div>
    <div class="metagrid">
      <div class="metacell"><div class="k">Caravans</div><div class="v">${J.length}</div></div>
      <div class="metacell"><div class="k">Provinces mapped</div><div class="v">${P.length}</div></div>
      <div class="metacell"><div class="k">Origin</div><div class="v" style="font-size:15px">${m.origin.name}</div></div>
      <div class="metacell"><div class="k">Span</div><div class="v">${span}<small> yrs</small></div></div>
    </div></div>`;
}
function sparkline(j, field, color, opts={}){
  const ks=j.keys, W=300, H=96, pad=6;
  const xs=ks.map(k=>k.t), ys=ks.map(k=>k[field]);
  const xmin=xs[0], xmax=xs[xs.length-1], ymax=Math.max(opts.ymax||0, ...ys), ymin=0;
  const X=t=> pad + (t-xmin)/(xmax-xmin)*(W-2*pad);
  const Y=v=> H-pad - (v-ymin)/((ymax-ymin)||1)*(H-2*pad);
  let d="", area="";
  ks.forEach((k,i)=>{ const x=X(k.t),y=Y(k[field]); d+=(i?"L":"M")+x.toFixed(1)+" "+y.toFixed(1)+" "; });
  area = d + `L ${X(xmax).toFixed(1)} ${(H-pad)} L ${X(xmin).toFixed(1)} ${(H-pad)} Z`;
  // now marker
  const nv = lerpField(j, S.curT, field), nx = X(Math.max(xmin,Math.min(xmax,S.curT))), ny=Y(nv);
  const grid=[0.5].map(f=>`<line x1="${pad}" y1="${(Y(ymax*f)).toFixed(1)}" x2="${W-pad}" y2="${(Y(ymax*f)).toFixed(1)}" stroke="var(--line-soft)" stroke-width="1"/>`).join("");
  return `<svg viewBox="0 0 ${W} ${H}" preserveAspectRatio="none">
    <defs><linearGradient id="g${field}${j.idx}" x1="0" x2="0" y1="0" y2="1">
      <stop offset="0" stop-color="${color}" stop-opacity="0.28"/><stop offset="1" stop-color="${color}" stop-opacity="0"/>
    </linearGradient></defs>
    ${grid}
    <path d="${area}" fill="url(#g${field}${j.idx})"/>
    <path d="${d}" fill="none" stroke="${color}" stroke-width="2" stroke-linejoin="round"/>
    <line x1="${nx.toFixed(1)}" y1="${pad}" x2="${nx.toFixed(1)}" y2="${H-pad}" stroke="var(--ink-faint)" stroke-width="1" stroke-dasharray="2 3"/>
    <circle cx="${nx.toFixed(1)}" cy="${ny.toFixed(1)}" r="3.5" fill="${color}" stroke="var(--panel-2)" stroke-width="1.5"/>
  </svg>`;
}
function parseCarrying(s){
  if(!s) return {items:[], more:0};
  const more = (s.match(/\(\+(\d+) more\)/)||[])[1];
  const items = s.replace(/\s*\(\+\d+ more\)/,"").split(";").map(x=>x.trim()).filter(Boolean).map(x=>{
    const m=x.match(/^(.*?)\s+(\d+)$/); return m?{name:m[1].replace(/_/g," "), n:+m[2]}:{name:x,n:0};
  });
  return {items, more: more?+more:0};
}
function worldRail(){
  return `<div class="runmeta">
    <div class="rm-title serif" style="font-size:16px">WorldMap</div>
    <div class="rm-sub">Anbennar · the whole world, real Civ4 terrain</div>
    <div class="metagrid">
      <div class="metacell"><div class="k">Land provinces</div><div class="v">${P.length}</div></div>
      <div class="metacell"><div class="k">Zoom</div><div class="v" style="font-size:15px">to the plot</div></div>
    </div></div>
    <p class="footnote">The full world, rendered from the engine's real terrain. Drag to pan, scroll to zoom — keep zooming past the continent view to resolve any province into its terrain plot by plot (textures, hillshade from the heightmap, rivers, features). Hover the map to read a province. Switch to <b>Caravan</b> mode to replay the six-band migration from Dhenijansar.</p>`;
}
// show/hide the caravan-only chrome for a mode
function setMode(m){
  S.mode = m;
  document.querySelectorAll("#modeToggle button").forEach(b=> b.setAttribute("aria-pressed", b.dataset.mode===m));
  const cara = m === "caravan";
  legend.style.display = cara ? "" : "none";
  document.querySelector(".transport").style.display = cara ? "" : "none";
  heatBtn.style.display = cara ? "" : "none";
  if (!cara) { pause(); S.selected = null; }
  S.selectedProv = null;             // start each mode on its own overview
  showRail(cara);                    // caravan mode opens the panel on its overview; world starts collapsed
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
  if (p && p.hasPlots !== false && !p._plots) loadPlots(p);   // stream in terrain for the breakdown
  showRail(!!p);                     // selecting opens the info panel; deselecting collapses it
  renderRail(); draw();
}
function provinceRail(p) {
  const g = p.geo || {};
  // each tier is [displayName, rawClausewitzKey]; show the key in parentheses after the name
  const crumbs = [g.continent, g.superRegion, g.region, g.area].filter(t => t && t[0])
    .map(t=>`<span>${t[0]}${t[1]?` <span class="pv-key">(${t[1]})</span>`:''}</span>`)
    .join('<span class="crumb-sep">›</span>');
  const coord = `${Math.abs(p.lat).toFixed(2)}°${p.lat>=0?"N":"S"}, ${Math.abs(p.lon).toFixed(2)}°${p.lon>=0?"E":"W"}`;
  let terrainHtml;
  if (p._plots) {
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
  } else if (p.hasPlots === false || !PLOT_INDEX[p.id]) {
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
        ${p.days?`<div class="metacell"><div class="k">Caravan-days</div><div class="v">${p.days}</div></div>`:""}
      </div>
      ${terrainHtml}
    </div>`;
  document.getElementById("backProv").onclick = ()=>{ S.selectedProv=null; showRail(S.mode==="caravan"); renderRail(); draw(); };
}
function renderRail(){
  if (S.selectedProv) { provinceRail(S.selectedProv); return; }
  if (S.mode === "world") { rail.innerHTML = worldRail(); return; }
  if(S.selected===null){
    const rows = J.map(j=>`<tr class="click" data-idx="${j.idx}">
        <td><div class="destcell"><span class="dot" style="background:${j.color}"></span>${j.dest}</div></td>
        <td class="num">${j.provinceCount}</td>
        <td class="num">${(j.days/365.25).toFixed(1)}y</td>
        <td class="num" style="color:var(--cargo)">${j.cargoFinal}</td>
      </tr>`).join("");
    rail.innerHTML = railMeta() + `
      <div>
        <p class="sectlabel">The six journeys</p>
        <table class="cmp">
          <thead><tr><th>Destination</th><th class="num">Prov</th><th class="num">Time</th><th class="num">Cargo</th></tr></thead>
          <tbody>${rows}</tbody>
        </table>
      </div>
      <p class="footnote">All six bands leave <b>${BUNDLE.meta.origin.name}</b> on <span class="mono">${BUNDLE.meta.dateStart}</span> and march the province graph one daylight-bounded leg per day, foraging food and gathering trade goods into a capacity-capped cargo. Drag to pan, scroll to zoom — keep zooming past the continent view to resolve each province into its real Civ4 terrain, plot by plot. Hover the map to read a province; pick a route on the map or a row below to follow one band. Scrub or press play to watch them travel.</p>`;
    rail.querySelectorAll("tr.click").forEach(tr=> tr.onclick=()=> selectJourney(+tr.dataset.idx));
  } else {
    const j=J[S.selected];
    rail.innerHTML = `
      <button class="backbtn" id="back">← All caravans</button>
      <div class="detail">
        <div class="d-head"><span class="route-dot" style="background:${j.color}"></span>
          <h2 class="serif">${j.dest}</h2></div>
        <div class="rm-sub" style="color:var(--ink-soft);margin-top:-8px">from ${BUNDLE.meta.origin.name} · <span class="mono">${j.startDate}</span> → <span class="mono">${j.endDate}</span></div>
        <div class="statrow">
          <div class="stat"><div class="k">Provinces</div><div class="v">${j.provinceCount}</div></div>
          <div class="stat"><div class="k">Duration</div><div class="v">${(j.days/365.25).toFixed(1)}<small style="font-size:11px;color:var(--ink-soft)"> yr</small></div></div>
          <div class="stat"><div class="k">Band now</div><div class="v" id="bandNow">–</div></div>
        </div>
        <div class="chart">
          <div class="c-head"><span class="c-title">Cargo hauled</span><span class="c-now mono" id="cargoNow" style="color:var(--cargo)"></span></div>
          ${sparkline(j,"cargo",cssVar("--cargo"),{ymax:500})}
        </div>
        <div class="chart">
          <div class="c-head"><span class="c-title">Larder (food carried)</span><span class="c-now mono" id="larderNow" style="color:var(--good)"></span></div>
          ${sparkline(j,"larder",cssVar("--good"))}
        </div>
        <div>
          <p class="sectlabel">Cargo on arrival · ${j.cargoFinal} units</p>
          <div class="cargo-list" id="cargoList"></div>
        </div>
        <p class="footnote">Foraging refills the larder while daylight allows; surplus daylight is spent gathering the trade goods the band crosses — only those whose reveal-tech it knows — into <code>Cargo</code>, capped at hold capacity. This band arrives at <b>${j.dest}</b> carrying the goods below.</p>
      </div>`;
    document.getElementById("back").onclick=()=> selectJourney(null);
    const {items,more}=parseCarrying(j.carryingFinal);
    const mx=Math.max(...items.map(i=>i.n),1);
    document.getElementById("cargoList").innerHTML = items.map(it=>`
      <div class="cargo-item"><span class="cname" title="${it.name}">${it.name}</span>
        <span class="cargo-bar"><i style="width:${(it.n/mx*100).toFixed(0)}%"></i></span>
        <span class="cval">${it.n}</span></div>`).join("") + (more?`<div class="cargo-more">+ ${more} more goods</div>`:"");
    updateDetailLive();
  }
}
function updateDetailLive(){
  const j=J[S.selected]; if(!j) return;
  const cn=document.getElementById("cargoNow"), ln=document.getElementById("larderNow"), bn=document.getElementById("bandNow");
  if(cn) cn.textContent = fmtInt(lerpField(j,S.curT,"cargo"))+" u";
  if(ln) ln.textContent = fmtInt(lerpField(j,S.curT,"larder"));
  if(bn) bn.textContent = Math.round(lerpField(j,S.curT,"band"));
  // re-render sparkline now-markers cheaply by redrawing rail charts' vertical line:
  const charts=rail.querySelectorAll(".chart svg");
  const fields=["cargo","larder"];
  charts.forEach((svg,i)=>{
    const ks=j.keys, W=300,H=96,pad=6, xmin=ks[0].t, xmax=ks[ks.length-1].t;
    const X=t=> pad+(t-xmin)/(xmax-xmin)*(W-2*pad);
    const ymax=(fields[i]==="cargo")?500:Math.max(...ks.map(k=>k.larder));
    const Y=v=> H-pad-(v/((ymax)||1))*(H-2*pad);
    const nx=X(Math.max(xmin,Math.min(xmax,S.curT))), nv=lerpField(j,S.curT,fields[i]), ny=Y(nv);
    const line=svg.querySelector("line[stroke-dasharray]"), c=svg.querySelector("circle");
    if(line){ line.setAttribute("x1",nx.toFixed(1)); line.setAttribute("x2",nx.toFixed(1)); }
    if(c){ c.setAttribute("cx",nx.toFixed(1)); c.setAttribute("cy",ny.toFixed(1)); }
  });
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
let searchMatches = [], searchActive = -1;
let camBeforeFocus = null;        // camera snapshot to unwind with Esc after a focus
function goToProvince(p) {
  camBeforeFocus = { ...cam };    // remember where we were so Esc can return
  focusProvinceFit(p.id);         // frame the whole province (centred, filling most of the canvas)
  selectProvince(p);              // and open its detail panel
}
function goToProvinceFit(p) {      // double-click: zoom so the whole province fits the viewport
  camBeforeFocus = { ...cam };    // remember where we were so Esc can return
  focusProvinceFit(p.id);
  selectProvince(p);
}
function unfocusProvince() {       // restore the pre-focus zoom/pan; returns false if nothing to undo
  if (!camBeforeFocus) return false;
  Object.assign(cam, camBeforeFocus); camBeforeFocus = null;
  clampPan(); S.baseVersion++; draw();
  return true;
}
// Esc / close button: collapse the sidebar and drop any selection, and restore a focused camera.
// Returns true if it actually did something (so the caller can swallow the key).
function closePanel() {
  const acted = railwrap.classList.contains("open") || S.selectedProv || S.selected != null || camBeforeFocus;
  unfocusProvince();
  S.selectedProv = null; S.selected = null;
  showRail(false); renderRail(); draw();
  return !!acted;
}
document.getElementById("railClose").onclick = closePanel;
function runSearch(raw) {
  const q = raw.trim().toLowerCase();
  searchClear.hidden = !q;
  if (!q) { searchResults.hidden = true; searchMatches = []; return; }
  const isNum = /^\d+$/.test(q);
  const scored = [];
  for (const p of P) {
    if (p.type !== "LAND") continue;
    let score = -1;
    if (isNum) { const ids = String(p.id); if (ids === q) score = 100; else if (ids.startsWith(q)) score = 55; }
    if (score < 0) {
      const name = p.name.toLowerCase();
      if (name === q) score = 90; else if (name.startsWith(q)) score = 70; else if (name.includes(q)) score = 40;
    }
    if (score >= 0) scored.push({ p, score });
  }
  scored.sort((a, b) => b.score - a.score || b.p.plots - a.p.plots || a.p.name.localeCompare(b.p.name));
  searchMatches = scored.slice(0, 12).map(s => s.p);
  searchActive = searchMatches.length ? 0 : -1;
  renderSearchResults();
}
function renderSearchResults() {
  if (!searchMatches.length) {
    searchResults.innerHTML = `<div class="search-empty">No province matches.</div>`;
    searchResults.hidden = false; return;
  }
  searchResults.innerHTML = searchMatches.map((p, i) => {
    const reg = (p.geo && p.geo.region && p.geo.region[0]) || "";
    return `<div class="search-row${i === searchActive ? " active" : ""}" role="option" data-i="${i}">
      <span class="sr-name">${p.name}</span><span class="sr-id">#${p.id}</span>
      <span class="sr-meta">${reg}</span></div>`;
  }).join("");
  searchResults.hidden = false;
  searchResults.querySelectorAll(".search-row").forEach(row =>
    row.addEventListener("mousedown", e => { e.preventDefault(); pickSearch(+row.dataset.i); }));
}
function pickSearch(i) {
  const p = searchMatches[i]; if (!p) return;
  searchResults.hidden = true; searchInput.blur();
  goToProvince(p);
}
searchInput.addEventListener("input", () => runSearch(searchInput.value));
searchInput.addEventListener("focus", () => { if (searchInput.value.trim()) runSearch(searchInput.value); });
searchInput.addEventListener("blur", () => setTimeout(() => { searchResults.hidden = true; }, 150));
searchInput.addEventListener("keydown", e => {
  if (e.key === "Escape") {
    if (closePanel()) { e.preventDefault(); return; }        // first Esc collapses the panel / returns the camera
    searchInput.value = ""; runSearch(""); searchInput.blur(); return;
  }
  if (searchResults.hidden || !searchMatches.length) return;
  if (e.key === "ArrowDown") { e.preventDefault(); searchActive = Math.min(searchActive + 1, searchMatches.length - 1); renderSearchResults(); }
  else if (e.key === "ArrowUp") { e.preventDefault(); searchActive = Math.max(searchActive - 1, 0); renderSearchResults(); }
  else if (e.key === "Enter") { e.preventDefault(); if (searchActive >= 0) pickSearch(searchActive); }
});
searchClear.addEventListener("click", () => { searchInput.value = ""; runSearch(""); searchInput.focus(); });
// ---- mode toggle ----
document.querySelectorAll("#modeToggle button").forEach(b =>
  b.addEventListener("click", () => setMode(b.dataset.mode)));

export { renderRail };

export function boot() {
  window.addEventListener("resize", resize);
  resize();
  setT(t0);
  setMode(S.mode);            // paints the rail + chrome for the active mode (default: world)
  // apply the ?p=/#p= deep link AFTER first layout — focusProvince needs a sized VIEW, so calling
  // it inline at boot (before the stage has laid out) silently no-ops
  requestAnimationFrame(() => requestAnimationFrame(applyHash));
  if (S.mode === "caravan" && !reduce && !hasDeepLink()) setTimeout(play, 650);
}
