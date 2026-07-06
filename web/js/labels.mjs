import { J, P, BUNDLE, px, py, cam, VIEW, ctx, destSet, cssVar, S } from "./core.mjs";
function drawLabels() {
  drawGeoLabels();          // zoom-banded continent/super-region/region tiers, behind the rest
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
  if (S.mode === "caravan") {
    label(BUNDLE.meta.origin.name, px(BUNDLE.meta.origin.lon), py(BUNDLE.meta.origin.lat),
      { font:F1, size:12, color:cssVar("--accent") });
    J.forEach(j => {
      if (S.selected!==null && S.selected!==j.idx) return;
      const d = j.keys[j.keys.length-1];
      label(j.dest, px(d.lon), py(d.lat), { font:F1, size:12, color:"#eaf0f8", dot:j.color, dotR:3.6 });
    });
  }
  // province names fade in only once zoomed in (below that the geographic tiers own the
  // map): label the provinces actually on screen, largest first and collision-culled, so
  // names resolve wherever you zoom rather than only for the globally-biggest few
  if (S.selected===null) {
    const pa = Math.min(1, Math.max(0, (cam.k - 6.5) / 2));   // fade in over cam.k 6.5 -> 8.5
    if (pa > 0.01) {
      const inView = [];
      for (const p of P) {
        if (p.type!=="LAND") continue;
        // origin/destinations carry their own journey labels in Caravan mode; in World mode
        // they are ordinary provinces and get named like the rest
        if (S.mode==="caravan" && (p.id===BUNDLE.meta.origin.id || destSet.has(p.id))) continue;
        const x = px(p.lon), y = py(p.lat);
        if (x < -40 || y < -20 || x > VIEW.w+40 || y > VIEW.h+20) continue;   // cull to viewport
        inView.push({ p, x, y });
      }
      inView.sort((a,b)=> b.p.plots - a.p.plots);
      ctx.save(); ctx.globalAlpha = pa;
      for (let i=0; i<inView.length && i<90; i++)
        label(inView[i].p.name, inView[i].x, inView[i].y, { font:F2, size:10.5, color:"#9fb0c8" });
      ctx.restore();
    }
  }
}

// ---- zoom-banded geographic tier labels (continent -> super-region -> region) ----
// Each tier is visible across a cam.k band and cross-fades at the seams, so zooming out
// coarsens the labelling (province -> region -> super-region -> continent) and zooming in
// refines it. Anchors + names come from BUNDLE.geo (built by build.mjs). k = [fadeIn0,
// full, holdTo, fadeOut1].
const GEO_TIERS = [
  { arr:"continents",   k:[0.9,1.0,1.5,2.3], size:16, weight:"800", color:"#e6edf7", halo:4.2, track:"3px", upper:true },
  { arr:"superRegions", k:[1.7,2.2,3.4,4.7], size:13, weight:"700", color:"#cdd9ea", halo:3.7, track:"1.5px", upper:true },
  { arr:"regions",      k:[3.6,4.7,7.0,9.5], size:11, weight:"600", color:"#aebcd2", halo:3.3, track:"0px", upper:false },
];
// trapezoidal visibility envelope: 0 outside [k0,k3], ramps up over [k0,k1], holds to k2, down to k3
function tierAlpha(k, [k0,k1,k2,k3]) {
  if (k <= k0 || k >= k3) return 0;
  if (k < k1) return (k - k0) / (k1 - k0);
  if (k <= k2) return 1;
  return (k3 - k) / (k3 - k2);
}
function drawGeoLabels() {
  const G = BUNDLE.geo; if (!G) return;
  for (const t of GEO_TIERS) {
    const a = tierAlpha(cam.k, t.k);
    if (a <= 0.01) continue;
    const items = G[t.arr] || [];
    const placed = [];               // per-tier collision, so tiers can cross-fade over each other
    ctx.save();
    ctx.globalAlpha = a;
    ctx.font = `${t.weight} ${t.size}px system-ui,'Segoe UI',sans-serif`;
    ctx.letterSpacing = t.track;
    ctx.textAlign = "center"; ctx.textBaseline = "middle";
    ctx.lineJoin = "round";
    for (const g of items) {         // pre-sorted largest-first = priority
      const name = t.upper ? g.name.toUpperCase() : g.name;
      const cx = px(g.lon), cy = py(g.lat), tw = ctx.measureText(name).width;
      const box = { x: cx - tw/2, y: cy - t.size/2 - 1, w: tw, h: t.size + 2 };
      if (box.x < 3 || box.y < 3 || box.x+box.w > VIEW.w-3 || box.y+box.h > VIEW.h-3) continue;
      if (placed.some(q => box.x < q.x+q.w && box.x+box.w > q.x && box.y < q.y+q.h && box.y+box.h > q.y)) continue;
      placed.push(box);
      ctx.lineWidth = t.halo; ctx.strokeStyle = "rgba(8,12,19,.9)"; ctx.strokeText(name, cx, cy);
      ctx.fillStyle = t.color; ctx.fillText(name, cx, cy);
    }
    ctx.restore();
  }
  ctx.letterSpacing = "0px";
}
export { drawLabels, drawGeoLabels };
