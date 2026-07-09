import { J, P, BUNDLE, px, py, pxr, pyr, cam, VIEW, ctx, destSet, cssVar, S } from "./core.mjs";

const LABEL_FAM = "system-ui,'Segoe UI',sans-serif";

// EU4-style province labels: the name is laid on the polygon's own long axis (angled) and scaled
// to span it, instead of horizontal beside the centroid. Phase (a) uses a STRAIGHT baseline — the
// principal axis of the shape (PCA over its ring vertices) — which a curved medial-axis spline can
// later replace, since drawTextOnPath already walks an arbitrary polyline.

// principal-axis baseline of a province, in SOURCE-pixel space (view-independent, so cached once).
// Returns {cx,cy,ux,uy,half,thick}: the oriented centre, the unit long-axis direction, and the
// half-extents along / across it. null for a ring-less (sea/lake) province.
function labelAxis(p) {
  if (p._axis !== undefined) return p._axis;
  if (!p.rings) return (p._axis = null);
  let n = 0, cx = 0, cy = 0;
  for (const ring of p.rings) for (const pt of ring) { cx += pt[0]; cy += pt[1]; n++; }
  if (n < 3) return (p._axis = null);
  cx /= n; cy /= n;
  let sxx = 0, syy = 0, sxy = 0;
  for (const ring of p.rings) for (const pt of ring) {
    const dx = pt[0] - cx, dy = pt[1] - cy;
    sxx += dx * dx; syy += dy * dy; sxy += dx * dy;
  }
  const ang = 0.5 * Math.atan2(2 * sxy, sxx - syy);      // principal eigenvector angle
  const ux = Math.cos(ang), uy = Math.sin(ang), vx = -uy, vy = ux;
  let tmin = 1e9, tmax = -1e9, smin = 1e9, smax = -1e9;
  for (const ring of p.rings) for (const pt of ring) {
    const dx = pt[0] - cx, dy = pt[1] - cy;
    const t = dx * ux + dy * uy, s = dx * vx + dy * vy;
    if (t < tmin) tmin = t; if (t > tmax) tmax = t;
    if (s < smin) smin = s; if (s > smax) smax = s;
  }
  const mid = (tmin + tmax) / 2;
  return (p._axis = {
    cx: cx + mid * ux, cy: cy + mid * uy, ux, uy,
    half: (tmax - tmin) / 2, thick: (smax - smin) / 2,
  });
}

// lay `name` glyph-by-glyph along a screen-space polyline `pts` ([[x,y],…]), each glyph rotated to
// the local tangent and centred on the path, with a dark halo behind. Canvas has no text-on-path,
// so we advance by measured glyph widths. `track` = extra px between glyphs (spread to fill).
function drawTextOnPath(name, pts, size, track, weight, color) {
  const cum = [0];
  for (let i = 1; i < pts.length; i++)
    cum[i] = cum[i - 1] + Math.hypot(pts[i][0] - pts[i - 1][0], pts[i][1] - pts[i - 1][1]);
  const L = cum[cum.length - 1];
  ctx.font = `${weight} ${size}px ${LABEL_FAM}`;
  const chars = [...name];
  const adv = chars.map(c => ctx.measureText(c).width);
  const total = adv.reduce((a, b) => a + b, 0) + track * Math.max(0, chars.length - 1);
  const sample = d => {
    d = Math.max(0, Math.min(L, d));
    let i = 1; while (i < cum.length - 1 && cum[i] < d) i++;
    const seg = cum[i] - cum[i - 1] || 1, f = (d - cum[i - 1]) / seg;
    return {
      x: pts[i - 1][0] + (pts[i][0] - pts[i - 1][0]) * f,
      y: pts[i - 1][1] + (pts[i][1] - pts[i - 1][1]) * f,
      ang: Math.atan2(pts[i][1] - pts[i - 1][1], pts[i][0] - pts[i - 1][0]),
    };
  };
  ctx.textAlign = "center"; ctx.textBaseline = "middle"; ctx.lineJoin = "round";
  let s = (L - total) / 2;                                // centre the word on the path
  for (let i = 0; i < chars.length; i++) {
    const pt = sample(s + adv[i] / 2);
    ctx.save(); ctx.translate(pt.x, pt.y); ctx.rotate(pt.ang);
    ctx.lineWidth = Math.max(2, size * 0.28); ctx.strokeStyle = "rgba(8,12,19,.92)";
    ctx.strokeText(chars[i], 0, 0);
    ctx.fillStyle = color; ctx.fillText(chars[i], 0, 0);
    ctx.restore();
    s += adv[i] + track;
  }
}

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
  // EU4-style province label: the name laid on the shape's principal axis (angled) and scaled to
  // span it. Culls to the viewport and collides against `placed` via the label's own screen AABB,
  // so nothing is drawn for an off-screen or overlapped province.
  const drawProvLabel = (p, o) => {
    const ax = labelAxis(p);
    if (!ax) return;
    let Ax = pxr(ax.cx - ax.ux * ax.half), Ay = pyr(ax.cy - ax.uy * ax.half);
    let Bx = pxr(ax.cx + ax.ux * ax.half), By = pyr(ax.cy + ax.uy * ax.half);
    if (Bx < Ax) { const x = Ax, y = Ay; Ax = Bx; Ay = By; Bx = x; By = y; }   // keep text upright
    const L = Math.hypot(Bx - Ax, By - Ay);
    if (L < 14) return;                                     // too small on screen to read
    const thick = ax.thick * (L / (2 * ax.half || 1));      // shape half-thickness on screen
    // fit: cap glyph height by the thickness, fit width to ~88% of the axis, and spread the leftover
    // length as inter-letter tracking (EU4 tracks names out to span the region)
    const target = L * 0.88, n = [...p.name].length;
    ctx.font = `${o.weight} 100px ${LABEL_FAM}`;
    const w100 = p._nw ?? (p._nw = ctx.measureText(p.name).width);
    let size = Math.min(o.max, thick * 1.4), track = 0;
    const natW = w100 * size / 100;
    if (natW > target) size *= target / natW;               // shrink to fit the length
    else { track = (target - natW) / Math.max(1, n - 1); if (track > size * 0.5) track = size * 0.5; }
    if (size < o.min) return;                               // too cramped — leave it unlabeled
    // the oriented text ribbon → screen AABB (for the viewport cull + overlap test)
    const dxu = (Bx - Ax) / L, dyu = (By - Ay) / L, hh = size * 0.62;
    let minx = 1e9, miny = 1e9, maxx = -1e9, maxy = -1e9;
    for (const [bx, by] of [[Ax, Ay], [Bx, By]]) for (const sgn of [1, -1]) {
      const X = bx - dyu * hh * sgn, Y = by + dxu * hh * sgn;
      if (X < minx) minx = X; if (X > maxx) maxx = X; if (Y < miny) miny = Y; if (Y > maxy) maxy = Y;
    }
    const box = { x: minx, y: miny, w: maxx - minx, h: maxy - miny };
    if (!fits(box)) return;                                 // off-viewport (fits() rejects edge overflow) or overlapping
    placed.push(box);
    drawTextOnPath(p.name, [[Ax, Ay], [Bx, By]], size, track, o.weight, o.color);
  };
  const F1="600 12px system-ui,'Segoe UI',sans-serif";
  if (S.overlay === "caravan") {
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
        if (S.overlay==="caravan" && (p.id===BUNDLE.meta.origin.id || destSet.has(p.id))) continue;
        const x = px(p.lon), y = py(p.lat);
        if (x < -40 || y < -20 || x > VIEW.w+40 || y > VIEW.h+20) continue;   // cull to viewport
        inView.push({ p, x, y });
      }
      inView.sort((a,b)=> b.p.plots - a.p.plots);
      ctx.save(); ctx.globalAlpha = pa;
      for (let i=0; i<inView.length && i<90; i++)
        drawProvLabel(inView[i].p, { weight:600, min:8, max:26, color:"#9fb0c8" });
      ctx.restore();
      // sea/lake names: secondary, deeper in (they'd clutter the world view), a cool italic and
      // drawn AFTER land so land wins any label-collision. Uses the coastal water provinces now shipped.
      const wpa = Math.min(1, Math.max(0, (cam.k - 8.5) / 2));   // fade in over cam.k 8.5 -> 10.5
      if (wpa > 0.01) {
        const water = [];
        for (const p of P) {
          if (p.type!=="SEA" && p.type!=="LAKE") continue;
          const x = px(p.lon), y = py(p.lat);
          if (x < -40 || y < -20 || x > VIEW.w+40 || y > VIEW.h+20) continue;
          water.push({ p, x, y });
        }
        water.sort((a,b)=> b.p.plots - a.p.plots);
        ctx.save(); ctx.globalAlpha = wpa;
        const FW="italic 500 10px system-ui,'Segoe UI',sans-serif";
        for (let i=0; i<water.length && i<40; i++)
          label(water[i].p.name, water[i].x, water[i].y, { font:FW, size:10, color:"#82b2cc" });
        ctx.restore();
      }
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
