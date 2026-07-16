"use strict";
// Pure statistics over a province's streamed plots + its plot counts. Extracted from bandcaption.mjs
// so it can be unit-tested without a DOM or a window.BUNDLE (core.mjs reads that at module-eval, so
// anything importing core is untestable in node). Depends only on plotlabel.prettyKey, which is
// pure too. See plotstats.test.mjs.
//
// Both functions here encode a rule that is easy to get wrong and was wrong first time round —
// hence the tests.
import { prettyKey } from "./plotlabel.mjs";

/**
 * A province's LAND plot count.
 *
 * `plots` (build.mjs) counts EVERY plot including water, so it is not a landmass measure: all 388
 * SEA provinces have plots > 0, and the largest province in the world by `plots` is an ocean
 * (North Eltchamas ≈ 80k) against ≈ 5k for the biggest land province. Subtracting waterPlots is what
 * makes "the biggest landmass on screen" mean what it says.
 */
export const landPlots = p => Math.max(0, ((p && p.plots) || 0) - ((p && p.waterPlots) || 0));

/**
 * Whether a province's plots are still on their way.
 *
 * The two empty states are different and conflating them misleads either way:
 *   `_plots === undefined` → never fetched          → pending; ask again when it arrives
 *   `_plots === []`        → fetched, truly empty   → a plotless deep-ocean province; never fills in
 * Deep-ocean provinces are enormous and so are exactly what a viewport crosshair tends to land on,
 * which is why treating [] as pending strands the caption on "Surveying…" forever.
 */
export const plotsPending = p => !p || p._plots === undefined;

/** A province's plots when there is at least one, else null (covers both empty states). */
export const plotsOf = p =>
  (p && Array.isArray(p._plots) && p._plots.length) ? p._plots : null;

/** How many of a province's plots are urban (0 when none / not yet streamed). */
export const urbanCount = p => { const qs = plotsOf(p); return qs ? qs.filter(q => q.urban).length : 0; };

/**
 * The most common terrain across a province's plots, Title Cased — "Sea Tropical", "Tundra".
 * null when there are no plots (pending or genuinely empty) or none carries a terrain.
 */
export function majorityTerrain(p) {
  const qs = plotsOf(p);
  if (!qs) return null;
  const tally = new Map();
  for (const q of qs) if (q.terrain) tally.set(q.terrain, (tally.get(q.terrain) || 0) + 1);
  let best = null, bn = 0;
  for (const [t, n] of tally) if (n > bn) { bn = n; best = t; }
  return best ? prettyKey(best) : null;
}
