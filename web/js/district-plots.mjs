"use strict";
// Which of a city's urban plots are actually BUILT — the pure ranking behind the district view
// (districts.mjs). A colony of N districts occupies N of its province's urban plots; the rest of
// the urban core is unclaimed ground and still reads as ABANDONED (docs/urban-plots.md). Kept in
// its own module (no core.mjs import) so it unit-tests under node.

/**
 * The `n` plots nearest (cx, cy), as a Set. `sx`/`sy` project a plot into the space the center is
 * given in. Returns null when `n` covers the whole list — the caller's signal that every plot is
 * built, so it can skip the per-plot test. Ties break on (y, x), so the pick is stable frame to
 * frame rather than flickering between equidistant plots.
 */
export function nearestPlots(plots, n, cx, cy, sx, sy) {
  if (n >= plots.length) return null;
  if (n <= 0) return new Set();
  const d = q => (sx(q) - cx) ** 2 + (sy(q) - cy) ** 2;
  const ranked = plots.slice().sort((a, b) => d(a) - d(b) || a.y - b.y || a.x - b.x);
  return new Set(ranked.slice(0, n));
}
