"use strict";
// Geographic-tier boundaries — region / super-region / continent outlines, precomputed in
// Java (TierBorderExporter, dissolved from the province raster) and served by the spectator
// server (GET /api/tiers, from the engine jar's map/tierborders.json). Drawn zoom-banded on the
// SAME bands as the tier labels (labels.GEO_TIERS): zooming out coarsens the visible boundaries
// (province → region → super-region → continent), and province borders fade out under them (see
// main.renderScene). Rings are absolute source pixels, so they reuse the province projection
// (pxr/pyr) and pin to the terrain 1:1.
import { ctx, pxr, pyr, S, apiUrl } from "../core.mjs";
import { bandAlpha, GEO_TIER_ENV } from "../bands.mjs";

// { continents:{key:[[[x,y]…]…]}, superRegions:{…}, regions:{…} } — fetched lazily on first
// approach to a tier zoom band, so the 90 KB (gzipped) asset never loads for a purely
// zoomed-in session.
let TIERS = null;
let loading = false;

// per tier: the stroke width and colour — coarser tiers read bolder/brighter. The visibility
// envelope comes from bands.GEO_TIER_ENV, the single source shared with the tier labels and the
// top-bar band caption (it was duplicated here verbatim from labels.mjs). The trapezoid +
// cross-fade math lives in bands.bandAlpha (was a local tierAlpha here).
const TIER_BANDS = [
  { tier: "continents",   env: GEO_TIER_ENV.continents,   width: 2.4, color: "232,237,247" },
  { tier: "superRegions", env: GEO_TIER_ENV.superRegions, width: 1.9, color: "205,217,234" },
  { tier: "regions",      env: GEO_TIER_ENV.regions,      width: 1.3, color: "174,188,210" },
];

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
  for (const t of TIER_BANDS) a = Math.max(a, bandAlpha(t.env));
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
  for (const { tier, env, width, color } of TIER_BANDS) {
    const a = bandAlpha(env);
    if (a <= 0.01) continue;
    ctx.globalAlpha = a;
    ctx.lineWidth = width;
    ctx.strokeStyle = `rgb(${color})`;
    ctx.stroke(tierPath(tier));
  }
  ctx.restore();
}
