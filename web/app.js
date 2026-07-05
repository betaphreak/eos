"use strict";
const BUNDLE = window.BUNDLE;
const ROUTE_COLORS = ["#d9603b", "#3d9bd1", "#57b368", "#9b7bd4", "#d98cae", "#c9a227"];

// ---- data prep ----
const J = BUNDLE.journeys.map((j, i) => ({ ...j, color: ROUTE_COLORS[i % ROUTE_COLORS.length], idx: i }));
const P = BUNDLE.provinces;
const day = s => Date.UTC(+s.slice(0,4), +s.slice(5,7)-1, +s.slice(8,10)) / 864e5;
const t0 = day(BUNDLE.meta.dateStart), t1 = day(BUNDLE.meta.dateEnd);
const fmtDate = t => { const d = new Date(t*864e5); return d.toLocaleDateString("en-GB",{day:"numeric",month:"short",year:"numeric",timeZone:"UTC"}); };
const fmtInt = n => Math.round(n).toLocaleString("en-US");
J.forEach(j => j.keys.forEach(k => k.t = day(k.date)));

// projection: lon/lat -> the exact source pixel on terrain.bmp (the inverse of the
// maps ProvinceExporter used) -> the baked crop's fit rectangle -> screen, with a
// pan/zoom camera (cam.k scale, cam.x/cam.y translate) applied last. Using the same
// source-pixel formulas the build baked with keeps dots/rings pinned to the map.
const MAP = BUNDLE.map;
const sxSrc = lon => (lon + 180) / 360 * (MAP.W - 1);
const sySrc = lat => { const r = lat * Math.PI / 180; return (1 - Math.log(Math.tan(r / 2 + Math.PI / 4)) / Math.PI) / 2 * MAP.H; };
let VIEW = { w:0, h:0, dx:0, dy:0, dw:0, dh:0, dpr:1 };
let viewVersion = 0;   // bumped whenever projection or camera changes, to invalidate cached paths
const cam = { k: 1, x: 0, y: 0 };
function fitView(w, h) {
  const cw = MAP.x1 - MAP.x0, ch = MAP.y1 - MAP.y0;   // crop extent in source px
  const s = Math.min(w / cw, h / ch);                 // contain: whole crop visible at k=1
  VIEW.w = w; VIEW.h = h; VIEW.dw = cw * s; VIEW.dh = ch * s;
  VIEW.dx = (w - VIEW.dw) / 2; VIEW.dy = (h - VIEW.dh) / 2;
  viewVersion++;
}
// base (unzoomed) screen coords, then the camera
const baseXr = sp => VIEW.dx + (sp - MAP.x0) / (MAP.x1 - MAP.x0) * VIEW.dw;
const baseYr = sp => VIEW.dy + (sp - MAP.y0) / (MAP.y1 - MAP.y0) * VIEW.dh;
const pxr = sp => cam.x + cam.k * baseXr(sp);
const pyr = sp => cam.y + cam.k * baseYr(sp);
const px = lon => pxr(sxSrc(lon));
const py = lat => pyr(sySrc(lat));

// the baked dark terrain raster (a real image asset), drawn under everything
const mapImg = new Image();
let mapReady = false;
mapImg.onload = () => { mapReady = true; draw(); };
mapImg.src = MAP.src;

// ---- province polygons: choropleth heat by caravan-days, cached per view ----
let showHeat = true;
const MAXD = BUNDLE.meta.maxDays;
const lerp = (a,b,t) => a + (b-a)*t;
function heatColor(days) {                      // amber (low) -> red (high), over dark terrain
  const t = Math.pow(days/MAXD, 0.5);
  return `rgba(${lerp(236,228,t)|0},${lerp(178,96,t)|0},${lerp(96,56,t)|0},${(0.12+0.6*t).toFixed(3)})`;
}
function provPath(p) {                           // Path2D of a province's rings, rebuilt on view change
  if (p._pv === viewVersion) return p._path;
  const path = new Path2D();
  for (const ring of p.rings) {
    ring.forEach((pt,i)=>{ const x=pxr(pt[0]), y=pyr(pt[1]); i?path.lineTo(x,y):path.moveTo(x,y); });
    path.closePath();
  }
  p._path = path; p._pv = viewVersion; return path;
}

// ---- canvas ----
const cv = document.getElementById("map"), ctx = cv.getContext("2d");
const stage = document.getElementById("stage");
function resize() {
  const r = stage.getBoundingClientRect(), dpr = Math.min(window.devicePixelRatio||1, 2);
  cv.width = r.width*dpr; cv.height = r.height*dpr; VIEW.dpr = dpr;
  fitView(r.width, r.height); clampPan(); draw();
}
const cssVar = n => getComputedStyle(document.documentElement).getPropertyValue(n).trim();

let hoverProv = null;
let selected = null;          // journey idx or null
let curT = t0;

function journeyPos(j, t) {
  const ks = j.keys;
  if (t <= ks[0].t) return { lon: ks[0].lon, lat: ks[0].lat, k: ks[0], arrived: false, started: t >= t0 };
  const last = ks[ks.length-1];
  if (t >= last.t) return { lon: last.lon, lat: last.lat, k: last, arrived: true, started: true };
  let i = 0; while (i < ks.length-1 && ks[i+1].t <= t) i++;
  const a = ks[i], b = ks[i+1], f = (t - a.t) / Math.max(1, b.t - a.t);
  return { lon: a.lon + (b.lon-a.lon)*f, lat: a.lat + (b.lat-a.lat)*f, k: a, arrived: false, started: true };
}
// interpolate a scalar telemetry field along keyframes
function lerpField(j, t, field) {
  const ks = j.keys;
  if (t <= ks[0].t) return ks[0][field];
  const last = ks[ks.length-1]; if (t >= last.t) return last[field];
  let i=0; while (i<ks.length-1 && ks[i+1].t<=t) i++;
  const a=ks[i], b=ks[i+1], f=(t-a.t)/Math.max(1,b.t-a.t);
  return a[field] + (b[field]-a[field])*f;
}

// context labels: the largest land provinces in view (origin/destinations aside),
// thinned by the collision test so only the biggest that fit get named.
const destSet = new Set(J.map(j=>j.destId));
const CONTEXT = P.filter(p=> p.type==="LAND" && p.id!==BUNDLE.meta.origin.id && !destSet.has(p.id))
  .sort((a,b)=> b.plots-a.plots).slice(0, 30);

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

  // choropleth: shade each province by the caravan-days spent in it
  if (showHeat) for (const p of P) { if (p.rings && p.days) { ctx.fillStyle=heatColor(p.days); ctx.fill(provPath(p)); } }
  // province outlines
  ctx.strokeStyle="rgba(190,205,230,.18)"; ctx.lineWidth=0.8;
  for (const p of P) if (p.rings) ctx.stroke(provPath(p));
  // hovered province highlight (polygon if we have one, else a centroid ring for seas)
  if (hoverProv && hoverProv.rings) {
    const hp = provPath(hoverProv);
    ctx.fillStyle="rgba(231,236,244,.12)"; ctx.fill(hp);
    ctx.strokeStyle="#eef2f8"; ctx.lineWidth=1.6; ctx.stroke(hp);
  } else if (hoverProv) {
    ctx.beginPath(); ctx.arc(px(hoverProv.lon), py(hoverProv.lat), 6, 0, 7);
    ctx.strokeStyle="#eef2f8"; ctx.lineWidth=1.4; ctx.stroke();
  }

  // routes (dim when another is selected), with a soft shadow to read over terrain
  J.forEach(j => {
    const dim = selected!==null && selected!==j.idx;
    ctx.beginPath();
    j.keys.forEach((k,i)=>{ const x=px(k.lon), y=py(k.lat); i?ctx.lineTo(x,y):ctx.moveTo(x,y); });
    ctx.strokeStyle=j.color; ctx.globalAlpha=dim?.14:(selected===j.idx?.98:.72);
    ctx.lineWidth=selected===j.idx?2.8:1.8; ctx.lineJoin="round"; ctx.lineCap="round";
    ctx.shadowColor="rgba(4,7,12,.55)"; ctx.shadowBlur=3; ctx.stroke(); ctx.shadowBlur=0;
    ctx.globalAlpha=1;
  });

  // origin star
  drawStar(px(BUNDLE.meta.origin.lon), py(BUNDLE.meta.origin.lat), 7, cssVar("--accent"));

  // destinations + moving caravans
  J.forEach(j => {
    const dim = selected!==null && selected!==j.idx;
    const dest = j.keys[j.keys.length-1];
    const dx=px(dest.lon), dy=py(dest.lat);
    ctx.beginPath(); ctx.arc(dx,dy,3.6,0,7); ctx.fillStyle=j.color; ctx.globalAlpha=dim?.28:1; ctx.fill();
    ctx.lineWidth=1.4; ctx.strokeStyle="rgba(9,13,20,.9)"; ctx.stroke(); ctx.globalAlpha=1;
    const pos = journeyPos(j, curT);
    if (pos.started) {
      const x=px(pos.lon), y=py(pos.lat);
      const cargo = lerpField(j, curT, "cargo");
      ctx.globalAlpha=dim?.3:1;
      const hr = 6 + (cargo/490)*7;                       // cargo halo
      ctx.beginPath(); ctx.arc(x,y,hr,0,7); ctx.strokeStyle=j.color; ctx.globalAlpha=dim?.12:.32; ctx.lineWidth=2.5; ctx.stroke();
      ctx.globalAlpha=dim?.3:1;
      ctx.beginPath(); ctx.arc(x,y,4.6,0,7); ctx.fillStyle=j.color; ctx.fill();
      ctx.beginPath(); ctx.arc(x,y,4.6,0,7); ctx.strokeStyle="#0b0f16"; ctx.lineWidth=1.6; ctx.stroke();
      ctx.globalAlpha=1;
    }
  });

  drawLabels();
}

// place province name labels over the map with a halo, skipping any that would
// overflow the stage or collide with one already placed (priority: origin first,
// then destinations, then the largest context provinces).
function drawLabels() {
  const placed = [];
  const fits = b => {
    if (b.x < 3 || b.y < 3 || b.x+b.w > VIEW.w-3 || b.y+b.h > VIEW.h-3) return false;
    return !placed.some(q => b.x < q.x+q.w && b.x+b.w > q.x && b.y < q.y+q.h && b.y+b.h > q.y);
  };
  const label = (name, ax, ay, o) => {
    ctx.font = o.font;
    const tw = ctx.measureText(name).width, gap = o.dot ? 9 : 5;
    for (const side of [1, -1]) {
      const bx = side>0 ? ax+gap : ax-gap-tw;
      const box = { x: bx, y: ay-o.size/2-1, w: tw, h: o.size+2 };
      if (!fits(box)) continue;
      placed.push(box);
      if (o.dot) {
        ctx.beginPath(); ctx.arc(ax,ay,o.dotR||3,0,7); ctx.fillStyle=o.dot; ctx.fill();
        ctx.lineWidth=1.2; ctx.strokeStyle="rgba(9,13,20,.9)"; ctx.stroke();
      }
      ctx.textAlign="left"; ctx.textBaseline="middle";
      ctx.lineJoin="round"; ctx.lineWidth=3.4; ctx.strokeStyle="rgba(8,12,19,.92)";
      ctx.strokeText(name, bx, ay);
      ctx.fillStyle=o.color; ctx.fillText(name, bx, ay);
      return;
    }
  };
  const F1="600 12px system-ui,'Segoe UI',sans-serif", F2="500 10.5px system-ui,'Segoe UI',sans-serif";
  label(BUNDLE.meta.origin.name, px(BUNDLE.meta.origin.lon), py(BUNDLE.meta.origin.lat),
    { font:F1, size:12, color:cssVar("--accent") });
  J.forEach(j => {
    if (selected!==null && selected!==j.idx) return;
    const d = j.keys[j.keys.length-1];
    label(j.dest, px(d.lon), py(d.lat), { font:F1, size:12, color:"#eaf0f8", dot:j.color, dotR:3.6 });
  });
  if (selected===null)
    CONTEXT.forEach(p => label(p.name, px(p.lon), py(p.lat), { font:F2, size:10.5, color:"#9fb0c8" }));
}
function drawStar(cx,cy,r,color){
  ctx.beginPath();
  for(let i=0;i<10;i++){ const a=Math.PI/5*i - Math.PI/2, rr=i%2?r*0.44:r; const x=cx+Math.cos(a)*rr, y=cy+Math.sin(a)*rr; i?ctx.lineTo(x,y):ctx.moveTo(x,y); }
  ctx.closePath(); ctx.fillStyle=color; ctx.fill();
  ctx.strokeStyle=cssVar("--panel-2"); ctx.lineWidth=1.2; ctx.stroke();
}

// ---- pan & zoom ----
// keep the map covering the viewport (or centred on an axis where it is smaller than
// the viewport); cam.x/cam.y are absolute screen translations added after the k-scale.
function clampAxis(camv, base, dim, viewDim) {
  const size = cam.k * dim, pos = camv + cam.k * base;
  if (size <= viewDim) return (viewDim - size) / 2 - cam.k * base;   // centre, no pan on this axis
  return Math.min(0, Math.max(viewDim - size, pos)) - cam.k * base;
}
function clampPan() {
  cam.x = clampAxis(cam.x, VIEW.dx, VIEW.dw, VIEW.w);
  cam.y = clampAxis(cam.y, VIEW.dy, VIEW.dh, VIEW.h);
}
function zoomAt(mx, my, factor) {
  const k2 = Math.max(1, Math.min(8, cam.k * factor));
  if (k2 === cam.k) return;
  const f = k2 / cam.k;
  cam.x = mx - f * (mx - cam.x);     // keep the point under (mx,my) fixed
  cam.y = my - f * (my - cam.y);
  cam.k = k2;
  clampPan(); viewVersion++; draw();
}
stage.addEventListener("wheel", e => {
  e.preventDefault();
  const r = stage.getBoundingClientRect();
  zoomAt(e.clientX - r.left, e.clientY - r.top, Math.exp(-e.deltaY * 0.0016));
}, { passive: false });

let dragging = false, lastX = 0, lastY = 0, panMoved = false;
stage.addEventListener("mousedown", e => {
  if (e.button !== 0) return;
  dragging = true; panMoved = false; lastX = e.clientX; lastY = e.clientY;
  stage.classList.add("grabbing");
});
window.addEventListener("mousemove", e => {
  if (!dragging) return;
  const dx = e.clientX - lastX, dy = e.clientY - lastY;
  if (Math.abs(dx) + Math.abs(dy) > 2) panMoved = true;
  cam.x += dx; cam.y += dy; lastX = e.clientX; lastY = e.clientY;
  clampPan(); viewVersion++; draw();
});
window.addEventListener("mouseup", () => { if (dragging) { dragging = false; stage.classList.remove("grabbing"); } });

document.getElementById("zoomIn").onclick = () => zoomAt(VIEW.w/2, VIEW.h/2, 1.5);
document.getElementById("zoomOut").onclick = () => zoomAt(VIEW.w/2, VIEW.h/2, 1/1.5);
document.getElementById("zoomReset").onclick = () => { cam.k = 1; cam.x = 0; cam.y = 0; clampPan(); viewVersion++; draw(); };

// ---- timeline ----
const scrub=document.getElementById("scrub"), dNow=document.getElementById("dNow");
document.getElementById("dLo").textContent = fmtDate(t0);
document.getElementById("dHi").textContent = fmtDate(t1);
function setT(t, fromInput){
  curT = Math.max(t0, Math.min(t1, t));
  dNow.textContent = fmtDate(curT);
  if(!fromInput) scrub.value = Math.round((curT-t0)/(t1-t0)*1000);
  draw();
  if (selected!==null) updateDetailLive();
}
scrub.addEventListener("input", ()=> setT(t0 + (+scrub.value/1000)*(t1-t0), true));

let playing=false, speed=2, raf=0, lastTs=0;
const DAYS_PER_SEC = {1:120, 2:340, 3:900};
const playBtn=document.getElementById("playBtn"), playIcon=document.getElementById("playIcon");
function tick(ts){
  if(!playing) return;
  if(lastTs){ const dt=(ts-lastTs)/1000; setT(curT + dt*DAYS_PER_SEC[speed]); if(curT>=t1){ pause(); } }
  lastTs=ts; raf=requestAnimationFrame(tick);
}
function play(){ if(curT>=t1) setT(t0); playing=true; lastTs=0; playIcon.innerHTML='<path d="M6 5h4v14H6zM14 5h4v14h-4z"/>'; playBtn.setAttribute("aria-label","Pause"); raf=requestAnimationFrame(tick); }
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
  b.onclick=()=> selectJourney(selected===j.idx?null:j.idx);
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
heatBtn.onclick=()=>{ showHeat=!showHeat; heatBtn.setAttribute("aria-pressed",showHeat);
  heatBtn.style.opacity=showHeat?"":".5"; heatKey.style.display=showHeat?"":"none"; draw(); };

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
  for(const p of P){ if(p.rings && pointInProv(p, mx, my)) return p; }   // exact polygon hit
  let best=null, bd=1e9;                                                  // else nearest centroid (seas)
  for(const p of P){ const dx=px(p.lon)-mx, dy=py(p.lat)-my, d=dx*dx+dy*dy; if(d<bd){bd=d;best=p;} }
  return bd<90 ? best : null;
}
stage.addEventListener("mousemove", e=>{
  if(dragging) return;                       // panning — skip hover work
  const r=stage.getBoundingClientRect(), mx=e.clientX-r.left, my=e.clientY-r.top;
  const best = provinceAt(mx, my);
  if(best){ hoverProv=best;
    tip.innerHTML=`<b>${best.name}</b> <span class="r">${best.type.toLowerCase()}</span><br><span class="r">${(best.region||"—").replace(/_/g," ").replace(" region","")} · ${best.plots} plots${best.days?` · ${best.days} caravan-days`:""}</span>`;
    tip.style.left=Math.min(mx+14, r.width-230)+"px"; tip.style.top=(my+14)+"px"; tip.classList.add("on");
  } else { hoverProv=null; tip.classList.remove("on"); }
  draw();
});
stage.addEventListener("mouseleave", ()=>{ hoverProv=null; tip.classList.remove("on"); draw(); });
stage.addEventListener("click", e=>{
  if(panMoved){ panMoved=false; return; }    // this "click" was the end of a drag
  const r=stage.getBoundingClientRect(), mx=e.clientX-r.left, my=e.clientY-r.top;
  // click near a route's current marker or destination selects it
  let best=null,bd=1e9;
  J.forEach(j=>{ const d=j.keys[j.keys.length-1]; const dx=px(d.lon)-mx,dy=py(d.lat)-my,dd=dx*dx+dy*dy; if(dd<bd){bd=dd;best=j;} });
  if(best && bd<160) selectJourney(selected===best.idx?null:best.idx);
});

// ---- rail ----
const rail=document.getElementById("rail");
function selectJourney(idx){
  selected=idx;
  J.forEach(j=> j._leg.setAttribute("aria-pressed", j.idx===idx));
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
  const nv = lerpField(j, curT, field), nx = X(Math.max(xmin,Math.min(xmax,curT))), ny=Y(nv);
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
function renderRail(){
  if(selected===null){
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
      <p class="footnote">All six bands leave <b>${BUNDLE.meta.origin.name}</b> on <span class="mono">${BUNDLE.meta.dateStart}</span> and march the province graph one daylight-bounded leg per day, foraging food and gathering trade goods into a capacity-capped cargo. Drag to pan, scroll to zoom; hover the map to read a province; pick a route on the map or a row below to follow one band. Scrub or press play to watch them travel.</p>`;
    rail.querySelectorAll("tr.click").forEach(tr=> tr.onclick=()=> selectJourney(+tr.dataset.idx));
  } else {
    const j=J[selected];
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
  const j=J[selected]; if(!j) return;
  const cn=document.getElementById("cargoNow"), ln=document.getElementById("larderNow"), bn=document.getElementById("bandNow");
  if(cn) cn.textContent = fmtInt(lerpField(j,curT,"cargo"))+" u";
  if(ln) ln.textContent = fmtInt(lerpField(j,curT,"larder"));
  if(bn) bn.textContent = Math.round(lerpField(j,curT,"band"));
  // re-render sparkline now-markers cheaply by redrawing rail charts' vertical line:
  const charts=rail.querySelectorAll(".chart svg");
  const fields=["cargo","larder"];
  charts.forEach((svg,i)=>{
    const ks=j.keys, W=300,H=96,pad=6, xmin=ks[0].t, xmax=ks[ks.length-1].t;
    const X=t=> pad+(t-xmin)/(xmax-xmin)*(W-2*pad);
    const ymax=(fields[i]==="cargo")?500:Math.max(...ks.map(k=>k.larder));
    const Y=v=> H-pad-(v/((ymax)||1))*(H-2*pad);
    const nx=X(Math.max(xmin,Math.min(xmax,curT))), nv=lerpField(j,curT,fields[i]), ny=Y(nv);
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
  draw(); if(selected!==null) renderRail();
};
matchMedia("(prefers-color-scheme: dark)").addEventListener("change", ()=>{ draw(); });

// ---- boot ----
window.addEventListener("resize", resize);
resize();
setT(t0);
renderRail();
if(!reduce) setTimeout(play, 650);
