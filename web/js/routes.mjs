"use strict";
// Route draw layer — stamp the baked Civ4 route sprites per plot, auto-tiled (docs/route-rendering.md).
// For each routed plot it reads the orthogonal-neighbour mask, picks the connection piece + rotation
// (route-tiling.mjs), and draws the tier's registered square cell rotated into the plot's screen
// square, so straights/corners/tees/crosses meet at plot edges. A per-plot ground-detail layer, so it
// lives in the same band as terrain textures / feature sprites (fades in Province→Terrain).
//
// Data source: the engine's per-plot RouteType maps to a tier via ROUTES.byType. Until that per-plot
// channel reaches the client (gap B, docs/explorer-caravan.md §Phase 5), city-core plots — which the
// engine founds pre-paved (ProvincePlotPool) and which markUrbanPlots already flags `urban` — stand in
// as PAVED_ROAD, so a paved city core is visible on zoom-in with no new server data.
import { P, ctx, ROUTES, pxr, pyr, provOnScreen, isPolitical } from "./core.mjs";
import { draw } from "./main.mjs";
import { bandAlpha } from "./bands.mjs";
import { routePiece, neighbourMask } from "./route-tiling.mjs";

const TIERS = ["trail", "road", "rail"];
// one atlas Image per tier, loaded once; ready[tier] gates drawing (and a load repaints)
const atlas = {}, ready = {};
if (ROUTES) for (const k of TIERS) if (ROUTES[k]) {
  const im = new Image();
  im.onload = () => { ready[k] = true; draw(); };
  im.src = ROUTES[k].src;
  atlas[k] = im;
}

/** The route tier a plot draws in, or null. Prefers the engine's per-plot RouteType (`q.route`,
 *  gap B) via ROUTES.byType; falls back to treating urban city-core plots as paved road. */
function plotTier(q) {
  if (q.route && ROUTES.byType && ROUTES.byType[q.route]) return ROUTES.byType[q.route];
  if (q.urban) return "road";
  return null;
}

// draw one square atlas cell into a plot's screen square, rotated rot·90° CW about its centre
function stampCell(img, rect, dx, dy, size, rot) {
  ctx.save();
  ctx.translate(dx + size / 2, dy + size / 2);
  ctx.rotate(rot * Math.PI / 2);
  ctx.drawImage(img, rect[0], rect[1], rect[2], rect[3], -size / 2, -size / 2, size, size);
  ctx.restore();
}

/** Stamp auto-tiled route sprites over the routed plots of every on-screen province. */
export function drawRoutes() {
  if (!ROUTES || isPolitical()) return;
  const a = bandAlpha([3.5, 4.5]);   // fade in Province→Terrain, then hold — per-plot ground detail
  if (a <= 0.01) return;
  const plotPx = pxr(1) - pxr(0);
  if (!(plotPx > 0.5)) return;
  ctx.save();
  ctx.globalAlpha = a;
  ctx.imageSmoothingEnabled = true;
  for (const p of P) {
    if (!p._plots || !p._plots.length || !provOnScreen(p)) continue;
    // index this province's routed plots by tier, for the neighbour connection test
    const routed = new Map();
    for (const q of p._plots) { const t = plotTier(q); if (t) routed.set(q.x + "," + q.y, t); }
    if (!routed.size) continue;
    for (const q of p._plots) {
      const key = q.x + "," + q.y, tier = routed.get(key);
      if (!tier || !ready[tier]) continue;
      const meta = ROUTES[tier], img = atlas[tier];
      // connect only to same-tier neighbours (a trail doesn't fuse into a paved road)
      const mask = neighbourMask((dx, dy) => routed.get((q.x + dx) + "," + (q.y + dy)) === tier);
      const { piece, rot } = routePiece(mask);
      const rect = meta.cell[piece];
      if (rect) stampCell(img, rect, pxr(q.x), pyr(q.y), plotPx, rot);
    }
  }
  ctx.restore();
}
