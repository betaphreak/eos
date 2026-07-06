import { BUNDLE, MAP, VIEW, cam, ctx, cv, stage, P, J, heatColor, provPath, px, py, journeyPos, lerpField, fmtInt, clampPan, sxSrc, sySrc, baseXr, baseYr, fitView, K_PLOT, cssVar, S } from "./core.mjs";
import { drawPlots, drawCostOverlay } from "./plots.mjs";
import { drawLabels } from "./labels.mjs";
// the baked dark terrain raster (a real image asset), drawn under everything
const mapImg = new Image();
let mapReady = false;
mapImg.onload = () => { mapReady = true; draw(); };
mapImg.src = MAP.src;
function resize() {
  const r = stage.getBoundingClientRect(), dpr = Math.min(window.devicePixelRatio||1, 2);
  cv.width = r.width*dpr; cv.height = r.height*dpr; VIEW.dpr = dpr;
  fitView(r.width, r.height); clampPan(); draw();
}
function draw() {
  const w=VIEW.w, h=VIEW.h, dpr=VIEW.dpr;
  ctx.setTransform(dpr,0,0,dpr,0,0);
  ctx.clearRect(0,0,w,h);

  // dark void + the baked terrain raster, aligned to the crop rectangle under the camera
  ctx.fillStyle="#090d14"; ctx.fillRect(0,0,w,h);
  if (mapReady) {
    ctx.imageSmoothingEnabled=true;
    ctx.drawImage(mapImg, 0,0,MAP.dw,MAP.dh,
      cam.x + cam.k*VIEW.dx, cam.y + cam.k*VIEW.dy, cam.k*VIEW.dw, cam.k*VIEW.dh);
  }
  drawPlots();   // crisp per-plot Civ4 terrain over the blurred raster when zoomed in
  drawCostOverlay();   // elevation movement-cost heat over the terrain, when toggled on

  // choropleth: a full caravan-days overview while zoomed out, but once the terrain
  // plots/textures show (cam.k >= K_PLOT) only the hovered province is shaded — the
  // static heat would otherwise hide the real terrain colours under it.
  if (S.showHeat && S.mode === "caravan") {
    if (cam.k < K_PLOT) { for (const p of P) if (p.rings && p.days) { ctx.fillStyle=heatColor(p.days); ctx.fill(provPath(p)); } }
    else if (S.hoverProv && S.hoverProv.rings && S.hoverProv.days) { ctx.fillStyle=heatColor(S.hoverProv.days); ctx.fill(provPath(S.hoverProv)); }
  }
  // province outlines
  ctx.strokeStyle="rgba(190,205,230,.18)"; ctx.lineWidth=0.8;
  for (const p of P) if (p.rings) ctx.stroke(provPath(p));
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

  // caravan overlay (routes, origin, moving bands) — only in Caravan mode
  if (S.mode === "caravan") {
  // routes (dim when another is selected), with a soft shadow to read over terrain
  J.forEach(j => {
    const dim = S.selected!==null && S.selected!==j.idx;
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
    const dim = S.selected!==null && S.selected!==j.idx;
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
  } // end caravan overlay

  drawLabels();
}

// place province name labels over the map with a halo, skipping any that would
// overflow the stage or collide with one already placed (priority: origin first,
// then destinations, then the largest context provinces).
function drawStar(cx,cy,r,color){
  ctx.beginPath();
  for(let i=0;i<10;i++){ const a=Math.PI/5*i - Math.PI/2, rr=i%2?r*0.44:r; const x=cx+Math.cos(a)*rr, y=cy+Math.sin(a)*rr; i?ctx.lineTo(x,y):ctx.moveTo(x,y); }
  ctx.closePath(); ctx.fillStyle=color; ctx.fill();
  ctx.strokeStyle=cssVar("--panel-2"); ctx.lineWidth=1.2; ctx.stroke();
}
function zoomAt(mx, my, factor) {
  const k2 = Math.max(1, Math.min(64, cam.k * factor));   // deep enough to read individual plots
  if (k2 === cam.k) return;
  const f = k2 / cam.k;
  cam.x = mx - f * (mx - cam.x);     // keep the point under (mx,my) fixed
  cam.y = my - f * (my - cam.y);
  cam.k = k2;
  clampPan(); S.viewVersion++; draw();
}
// ---- deep link: index.html#p=<provinceId>&z=<zoom> focuses a province at a zoom ----
const Pby = new Map(P.map(p => [p.id, p]));
function focusProvince(id, k) {
  const p = Pby.get(id); if (!p) return;
  cam.k = Math.max(1, Math.min(64, k || 18));
  cam.x = VIEW.w / 2 - cam.k * baseXr(sxSrc(p.lon));
  cam.y = VIEW.h / 2 - cam.k * baseYr(sySrc(p.lat));
  clampPan(); S.viewVersion++; draw();
}
function applyHash() {
  const p = /(?:^|[#&])p=(\d+)/.exec(location.hash);
  const z = /(?:^|[#&])z=(\d+(?:\.\d+)?)/.exec(location.hash);
  if (p) focusProvince(+p[1], z ? +z[1] : 18);
}
window.addEventListener("hashchange", applyHash);
export { draw, zoomAt, resize, focusProvince, applyHash };
