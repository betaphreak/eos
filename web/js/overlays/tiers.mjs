"use strict";
// Geographic-tier boundaries — region / super-region / continent outlines, precomputed in
// Java (TierBorderExporter, dissolved from the province raster) and served by the spectator
// server (GET /api/tiers, from the engine jar's map/tierborders.json). Drawn zoom-banded on the
// SAME bands as the tier labels (labels.GEO_TIERS): zooming out coarsens the visible boundaries
// (province → region → super-region → continent), and province borders fade out under them (see
// main.renderScene). Rings are absolute source pixels, so they reuse the province projection
// (pxr/pyr) and pin to the terrain 1:1.
import { ctx, cam, pxr, pyr, S, apiUrl } from "../core.mjs";

// { continents:{key:[[[x,y]…]…]}, superRegions:{…}, regions:{…} } — fetched lazily on first
// approach to a tier zoom band, so the 90 KB (gzipped) asset never loads for a purely
// zoomed-in session.
let TIERS = null;
let loading = false;

// per tier: the cam.k visibility band [fadeIn0, full, holdTo, fadeOut1] (matching the labels),
// the stroke width and colour. Coarser tiers read bolder/brighter.
const TIER_BANDS = [
  { tier: "continents",   band: [0.9, 1.0, 1.5, 2.3], width: 2.4, color: "232,237,247" },
  { tier: "superRegions", band: [1.7, 2.2, 3.4, 4.7], width: 1.9, color: "205,217,234" },
  { tier: "regions",      band: [3.6, 4.7, 7.0, 9.5], width: 1.3, color: "174,188,210" },
];

// trapezoidal envelope: 0 outside [k0,k3], ramp up [k0,k1], hold to k2, down to k3 (as labels)
function tierAlpha(k, [k0, k1, k2, k3]) {
  if (k <= k0 || k >= k3) return 0;
  if (k < k1) return (k - k0) / (k1 - k0);
  if (k <= k2) return 1;
  return (k3 - k) / (k3 - k2);
}

/** True once the tier geometry is loaded (so a caller can decide to draw). */
export function tiersReady() { return TIERS !== null; }

/** Lazily fetch the tier geometry (idempotent); calls redraw() once it arrives. */
export function ensureTiers(redraw) {
  if (TIERS || loading) return;
  loading = true;
  fetch(apiUrl("/api/tiers"))
    .then(r => r.ok ? r.json() : Promise.reject(r.status))
    .then(d => { TIERS = d; loading = false; if (redraw) redraw(); })
    .catch(() => { loading = false; });   // no tiers → the map just keeps province borders
}

// cache one Path2D per tier, rebuilt when the projection/camera changes (S.viewVersion), and
// distinct per on-screen world copy — exactly like core.provPath
const cache = { continents: { pv: -1, path: null }, superRegions: { pv: -1, path: null }, regions: { pv: -1, path: null } };
function tierPath(tier) {
  const c = cache[tier];
  if (c.pv === S.viewVersion && c.path) return c.path;
  const path = new Path2D();
  const groups = TIERS[tier] || {};
  for (const key in groups)
    for (const ring of groups[key]) {
      ring.forEach((pt, i) => { const x = pxr(pt[0]), y = pyr(pt[1]); i ? path.lineTo(x, y) : path.moveTo(x, y); });
      path.closePath();
    }
  c.path = path; c.pv = S.viewVersion;
  return path;
}

/** The strongest tier alpha at the current zoom — used to fade province borders against it. */
export function activeTierAlpha() {
  let a = 0;
  for (const t of TIER_BANDS) a = Math.max(a, tierAlpha(cam.k, t.band));
  return a;
}

/** Draw the zoom-banded tier outlines. Called by main.renderScene per world copy. */
export function drawTiers() {
  if (!TIERS) return;
  ctx.save();
  ctx.lineJoin = "round";
  ctx.lineCap = "round";
  ctx.shadowColor = "rgba(6,9,14,.55)";
  ctx.shadowBlur = 2;
  for (const { tier, band, width, color } of TIER_BANDS) {
    const a = tierAlpha(cam.k, band);
    if (a <= 0.01) continue;
    ctx.globalAlpha = a;
    ctx.lineWidth = width;
    ctx.strokeStyle = `rgb(${color})`;
    ctx.stroke(tierPath(tier));
  }
  ctx.restore();
}
