"use strict";
// The MAP tooltip + picking: what happens when the pointer is over the map. Hovering reads the
// province/plot under the cursor into the floating tip; clicking selects a province (or
// deselects it); double-clicking zooms in on the point.
//
// Split out of panel.mjs (see clock.mjs's header). The seam is "what is under the cursor" —
// the complement of input.mjs's "where is the camera". It reaches OUT to the rail (a click
// selects, and the rail is what a selection renders) but nothing reaches back in.
import { stage, polOf, isPolitical, TRADE_GOODS, S, switchRealm, cam } from "./core.mjs";
import { draw } from "./repaint.mjs";
import { zoomAt } from "./main.mjs";
import { provinceAt, plotAt } from "./hittest.mjs";
import { prettyKey, plotTip } from "./plotlabel.mjs";
import { consumePanMoved } from "./input.mjs";
import { selectProvince } from "./rail.mjs";
import { openCaravanRail } from "./caravan-detail.mjs";
import { districtAt } from "./districts.mjs";
import { buildingsOf } from "./district-plots.mjs";
import { buildingName } from "./build-catalog.mjs";
import { openCityScreen } from "./city-screen.mjs";
import { liveColony } from "./overlays/live.mjs";

// ---- interaction: hover province ----
const tip=document.getElementById("tip");
// a plot's resource label for the tooltip, or null: its bonus (or polar sea ice), Title Cased
function resourceLabel(q){
  return q.bonus ? prettyKey(q.bonus) : null;   // the ◆ resource line; terrain/feature (incl. ice) live in plotTip
}
// What the live colony has standing (or rising) on this plot, as tooltip lines: the buildings by
// name, and anything under construction with how far along it is. Empty for a plot the live colony
// doesn't hold (and for every plot when no session is running) — the static map knows no buildings.
function buildingLines(q) {
  const dist = districtAt(q.x, q.y);
  if (!dist) return "";
  const out = [];
  for (const b of buildingsOf(dist))
    out.push(`<span class="r">▪ ${buildingName(b.id)}</span>`);
  for (const u of dist.underway || []) {
    const pct = u.cost > 0 ? Math.round(100 * Math.min(1, u.progress / u.cost)) : 0;
    out.push(`<span class="r" style="color:var(--gold,#c9a24a)">⚒ ${buildingName(u.id)} · ${pct}%</span>`);
  }
  return out.join("<br>");
}
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
// The tooltip is DOM and must track every mouse pixel; the CANVAS, though, only reads hover through
// S.hoverProv (main.drawHoverHighlight) — so a repaint is only worth it when the hovered PROVINCE
// changes. Drifting the cursor inside one province used to cost a full scene repaint per mousemove
// (~60/s) of a byte-identical scene, which was the single most frequent wasted paint in the app.
const repaintIfHoverMoved = before => { if (S.hoverProv !== before) draw(); };
stage.addEventListener("mousemove", e=>{
  if(S.dragging) return;                       // panning — skip hover work
  const before = S.hoverProv;
  const r=stage.getBoundingClientRect(), mx=e.clientX-r.left, my=e.clientY-r.top;
  const mk = markerAt(mx, my);                  // a cave-entrance/teleporter glyph takes priority
  if(mk){ S.hoverProv=null; tip.innerHTML=mk.label;
    tip.style.left=Math.min(mx+14, r.width-230)+"px"; tip.style.top=(my+14)+"px"; tip.classList.add("on");
    repaintIfHoverMoved(before); return; }
  const best = provinceAt(mx, my);
  const hit = plotAt(mx, my);                   // plot under cursor (texture zoom): name/terrain/feature/resource
  const plot = hit ? plotTip(hit) : "";
  const res = hit ? resourceLabel(hit) : null;
  const built = hit ? buildingLines(hit) : "";
  if(best || plot || res || built){ S.hoverProv=best;
    let html = best ? provTip(best) : "";
    if(plot) html += `${html?"<br>":""}${plot}`;
    if(res) html += `${html?"<br>":""}<span class="r">◆ ${res}</span>`;
    if(built) html += `${html?"<br>":""}${built}`;
    tip.innerHTML=html;
    tip.style.left=Math.min(mx+14, r.width-230)+"px"; tip.style.top=(my+14)+"px"; tip.classList.add("on");
  } else { S.hoverProv=null; tip.classList.remove("on"); }
  repaintIfHoverMoved(before);
});
stage.addEventListener("mouseleave", ()=>{ const before=S.hoverProv; S.hoverProv=null; tip.classList.remove("on"); repaintIfHoverMoved(before); });
stage.addEventListener("click", e=>{
  if (consumePanMoved()) return;               // this "click" was the end of a drag
  if(e.detail>1) return;                      // 2nd click of a double-click: dblclick zooms — don't
                                              // toggle the just-selected province back off (the flash)
  const r=stage.getBoundingClientRect(), mx=e.clientX-r.left, my=e.clientY-r.top;
  // a realm arrow takes the click: cross to the other realm, landing on the far portal at this zoom
  const mk = markerAt(mx, my);
  if (mk && mk.realm) { switchRealm(mk.realm, { province: mk.prov, zoom: cam.k }); return; }
  // a caravan icon takes the click: open the band's composition panel (docs/caravan.md)
  if (mk && mk.caravan) { openCaravanRail(mk.caravan); return; }
  // the live colony's CITY CENTER takes the click: open the settlement (docs/city-screen-plan.md).
  // The centre plot is the seat of the colony — clicking it is the natural "enter the city" verb,
  // and it beats the province selection that would otherwise swallow the click.
  const q = plotAt(mx, my), colony = liveColony();
  if (q && colony && q.x === colony.centerX && q.y === colony.centerY) { openCityScreen(); return; }
  // else the click selects the province under the cursor (toggles off if re-clicked)
  const prov = provinceAt(mx, my);
  if (prov) selectProvince(S.selectedProv===prov ? null : prov);
});
// double-click / double-tap zooms in, centred on the point (touch double-tap fires dblclick too)
stage.addEventListener("dblclick", e=>{
  const r=stage.getBoundingClientRect();
  zoomAt(e.clientX-r.left, e.clientY-r.top, 2.5);
});
