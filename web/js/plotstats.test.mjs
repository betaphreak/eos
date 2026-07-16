import { test } from "node:test";
import assert from "node:assert/strict";
import { landPlots, plotsPending, plotsOf, urbanCount, majorityTerrain } from "./plotstats.mjs";

// Both rules below were implemented WRONG first time and only surfaced by driving the real page —
// these lock them down. See docs/zoom-bands.md §Band caption.

test("landPlots subtracts water — `plots` counts water too, so a sea is not a landmass", () => {
  // real shapes from the shipped bundle: the biggest ocean dwarfs the biggest landmass by `plots`
  const ocean = { plots: 80234, waterPlots: 80234, type: "SEA" };     // North Eltchamas
  const land  = { plots: 5025,  waterPlots: 143,   type: "LAND" };    // Gusedin
  assert.equal(landPlots(ocean), 0, "an all-water province has no land");
  assert.equal(landPlots(land), 4882);
  // the regression this guards: ranking by the raw `plots` field picks the ocean
  assert.ok(ocean.plots > land.plots, "precondition: the ocean wins on raw plots");
  assert.ok(landPlots(land) > landPlots(ocean), "…but loses on land plots, which is the point");
});

test("landPlots is defensive about missing/absent counts", () => {
  assert.equal(landPlots(null), 0);
  assert.equal(landPlots({}), 0);
  assert.equal(landPlots({ plots: 10 }), 10);              // no waterPlots field → all land
  assert.equal(landPlots({ plots: 3, waterPlots: 9 }), 0); // never negative
});

test("plotsPending distinguishes 'not fetched' from 'fetched and empty'", () => {
  assert.equal(plotsPending({}), true, "undefined _plots = still streaming");
  assert.equal(plotsPending({ _plots: [] }), false, "[] = fetched, genuinely empty (deep ocean)");
  assert.equal(plotsPending({ _plots: [{ terrain: "TERRAIN_LUSH" }] }), false);
  assert.equal(plotsPending(null), true);
});

test("plotsOf returns plots only when there are some", () => {
  assert.equal(plotsOf({}), null);
  assert.equal(plotsOf({ _plots: [] }), null);
  assert.deepEqual(plotsOf({ _plots: [{ x: 1 }] }), [{ x: 1 }]);
});

test("majorityTerrain picks the most common terrain, Title Cased", () => {
  const p = { _plots: [
    { terrain: "TERRAIN_SEA_TROPICAL" }, { terrain: "TERRAIN_SEA_TROPICAL" },
    { terrain: "TERRAIN_SEA_TROPICAL" }, { terrain: "TERRAIN_TUNDRA" },
  ] };
  assert.equal(majorityTerrain(p), "Sea Tropical");
});

test("majorityTerrain returns null when there is nothing to report", () => {
  assert.equal(majorityTerrain({}), null, "pending");
  assert.equal(majorityTerrain({ _plots: [] }), null, "fetched-empty — the caller says 'Open ocean'");
  assert.equal(majorityTerrain({ _plots: [{ urban: true }] }), null, "plots carry no terrain");
});

test("urbanCount counts urban plots, 0 when none or not streamed", () => {
  assert.equal(urbanCount({ _plots: [{ urban: true }, { urban: false }, { urban: true }] }), 2);
  assert.equal(urbanCount({ _plots: [] }), 0);
  assert.equal(urbanCount({}), 0);
});
