"use strict";
// Caravan overlay — the map render for the Caravan replay: the caravan-days choropleth, the six
// route polylines, the origin star, and the moving caravan markers with cargo halos. The chrome
// (journey legend, timeline, per-journey rail) lives in panel.mjs; this module owns only the render.
import { ctx, cam, P, provPath, S, K_PLOT, heatColor, px, py, journeyPos, lerpField, J, cssVar, BUNDLE } from "../core.mjs";

// a five-point origin star
function drawStar(cx, cy, r, color) {
  ctx.beginPath();
  for (let i = 0; i < 10; i++) { const a = Math.PI/5*i - Math.PI/2, rr = i%2 ? r*0.44 : r; const x = cx+Math.cos(a)*rr, y = cy+Math.sin(a)*rr; i?ctx.lineTo(x,y):ctx.moveTo(x,y); }
  ctx.closePath(); ctx.fillStyle = color; ctx.fill();
  ctx.strokeStyle = cssVar("--panel-2"); ctx.lineWidth = 1.2; ctx.stroke();
}

// caravan-days choropleth: a full overview while zoomed out, but once the terrain plots/textures
// show (cam.k >= K_PLOT) only the hovered province is shaded (the static heat would otherwise hide
// the real terrain colours). Called by main.renderScene when the heat toggle is on in Caravan mode.
export function drawCaravanHeat() {
  if (cam.k < K_PLOT) { for (const p of P) if (p.rings && p.days) { ctx.fillStyle = heatColor(p.days); ctx.fill(provPath(p)); } }
  else if (S.hoverProv && S.hoverProv.rings && S.hoverProv.days) { ctx.fillStyle = heatColor(S.hoverProv.days); ctx.fill(provPath(S.hoverProv)); }
}

// routes, origin star and the moving caravans. Called by main.renderScene in Caravan mode.
export function drawCaravan() {
  // routes (dim when another is selected), with a soft shadow to read over terrain
  J.forEach(j => {
    const dim = S.selected !== null && S.selected !== j.idx;
    ctx.beginPath();
    j.keys.forEach((k,i)=>{ const x=px(k.lon), y=py(k.lat); i?ctx.lineTo(x,y):ctx.moveTo(x,y); });
    ctx.strokeStyle=j.color; ctx.globalAlpha=dim?.14:(S.selected===j.idx?.98:.72);
    ctx.lineWidth=S.selected===j.idx?2.8:1.8; ctx.lineJoin="round"; ctx.lineCap="round";
    ctx.shadowColor="rgba(4,7,12,.55)"; ctx.shadowBlur=3; ctx.stroke(); ctx.shadowBlur=0;
    ctx.globalAlpha=1;
  });

  // origin star
  drawStar(px(BUNDLE.meta.origin.lon), py(BUNDLE.meta.origin.lat), 7, cssVar("--accent"));

  // destinations + moving caravans
  J.forEach(j => {
    const dim = S.selected !== null && S.selected !== j.idx;
    const dest = j.keys[j.keys.length-1];
    const dx=px(dest.lon), dy=py(dest.lat);
    ctx.beginPath(); ctx.arc(dx,dy,3.6,0,7); ctx.fillStyle=j.color; ctx.globalAlpha=dim?.28:1; ctx.fill();
    ctx.lineWidth=1.4; ctx.strokeStyle="rgba(9,13,20,.9)"; ctx.stroke(); ctx.globalAlpha=1;
    const pos = journeyPos(j, S.curT);
    if (pos.started) {
      const x=px(pos.lon), y=py(pos.lat);
      const cargo = lerpField(j, S.curT, "cargo");
      ctx.globalAlpha=dim?.3:1;
      const hr = 6 + (cargo/490)*7;                       // cargo halo
      ctx.beginPath(); ctx.arc(x,y,hr,0,7); ctx.strokeStyle=j.color; ctx.globalAlpha=dim?.12:.32; ctx.lineWidth=2.5; ctx.stroke();
      ctx.globalAlpha=dim?.3:1;
      ctx.beginPath(); ctx.arc(x,y,4.6,0,7); ctx.fillStyle=j.color; ctx.fill();
      ctx.beginPath(); ctx.arc(x,y,4.6,0,7); ctx.strokeStyle="#0b0f16"; ctx.lineWidth=1.6; ctx.stroke();
      ctx.globalAlpha=1;
    }
  });
}
