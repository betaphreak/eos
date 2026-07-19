"use strict";
// The client's GLOBAL route index — the union of every loaded province's standing route layer, keyed
// by global plot "x,y" so the draw layer's neighbour mask fuses roads across province seams (a plot
// on province A's edge sees the trail entering from province B). Pure (no browser globals) so it
// unit-tests in node (route-index.test.mjs); routefetch.mjs fills it from the server, routes.mjs
// reads it. See docs/route-rendering.md §Viewport-windowed route persistence.
//
// Two provinces never share a plot, so each province owns its keys outright: a refetch REPLACES that
// province's contribution (an upgrade trail→road changes a type; a — rare — removal drops a key),
// leaving every other province's keys untouched.

const field = new Map();          // "x,y" -> ROUTE_* type
const provinceKeys = new Map();   // provId -> Set<"x,y"> the province contributed
const EMPTY = new Set();
let version = 0;

/** The ROUTE_* type on the plot at global (x, y), or null — the neighbour-mask oracle + plotTier source. */
export function routeType(x, y) {
  return field.get(x + "," + y) || null;
}

/** A monotonic version of the index, bumped on every change — the draw layer's tiling-cache key, so a
 *  province re-tiles (including its edge plots) when it OR a neighbour's layer moves. */
export function routeVersion() {
  return version;
}

/**
 * Replace a province's contribution with `plots` (`[{x, y, type}]`). Returns whether the field
 * actually changed (a same-rev refetch is caught upstream, but a genuine no-op still returns false so
 * the caller can skip a repaint).
 */
export function applyProvince(id, plots) {
  const old = provinceKeys.get(id) || EMPTY;
  const next = new Set();
  let changed = false;
  for (const rp of (plots || [])) {
    const k = rp.x + "," + rp.y;
    next.add(k);
    if (field.get(k) !== rp.type) { field.set(k, rp.type); changed = true; }
  }
  for (const k of old) if (!next.has(k)) { field.delete(k); changed = true; }
  provinceKeys.set(id, next);
  if (changed) version++;
  return changed;
}

/** Drop the whole index — a session switch (or leaving Live). Always bumps the version. */
export function clearRoutes() {
  field.clear();
  provinceKeys.clear();
  version++;
}
