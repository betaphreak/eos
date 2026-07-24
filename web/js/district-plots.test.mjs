"use strict";
// Unit tests for the pure built-plot ranking (district-plots.mjs). Run: node --test web/js/
// (the web/ direction is node:test — see docs/urban-plots.md, web-unit-tests-wanted).
import { test } from "node:test";
import assert from "node:assert/strict";
import { nearestPlots } from "./district-plots.mjs";

const sx = q => q.x, sy = q => q.y;                    // identity projection
const grid = (w, h) => {                               // w×h plots at integer coords
  const out = [];
  for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) out.push({ x, y });
  return out;
};

test("a partly-built core lights only its district count", () => {
  // the shape of the reported bug: 74 urban plots, 30 districts — 30 live, 44 abandoned
  const plots = grid(10, 8).slice(0, 74);
  const built = nearestPlots(plots, 30, 5, 4, sx, sy);
  assert.equal(built.size, 30);
  assert.equal(plots.filter(q => !built.has(q)).length, 44);
});

test("the built plots are the ones nearest the colony centre", () => {
  const plots = grid(5, 5);
  const built = nearestPlots(plots, 5, 2, 2, sx, sy);     // centre plot + its 4 orthogonals
  const at = (x, y) => plots.find(q => q.x === x && q.y === y);
  for (const q of [at(2, 2), at(2, 1), at(1, 2), at(3, 2), at(2, 3)]) assert.ok(built.has(q));
  assert.ok(!built.has(at(0, 0)), "a corner plot is outside a 5-district core");
});

test("a fully-built core reports null — every plot is live", () => {
  const plots = grid(3, 3);
  assert.equal(nearestPlots(plots, 9, 1, 1, sx, sy), null);
  assert.equal(nearestPlots(plots, 12, 1, 1, sx, sy), null, "more districts than plots still means all");
});

test("a colony with no districts lights nothing", () => {
  const built = nearestPlots(grid(3, 3), 0, 1, 1, sx, sy);
  assert.equal(built.size, 0);                            // a camp has no built centre
});

test("the pick is stable — equidistant plots break ties on (y, x)", () => {
  const plots = grid(4, 4);
  const a = [...nearestPlots(plots, 6, 1.5, 1.5, sx, sy)].map(q => `${q.x},${q.y}`);
  const b = [...nearestPlots(plots.slice().reverse(), 6, 1.5, 1.5, sx, sy)].map(q => `${q.x},${q.y}`);
  assert.deepEqual(a.slice().sort(), b.slice().sort(), "input order must not change the pick");
});

test("a district's buildings read the same in either server shape", async () => {
  const { buildingsOf } = await import("./district-plots.mjs");
  // the shape the city screen ships — a house also carries its owning household's surname
  assert.deepEqual(buildingsOf({ buildings: [
    { id: "BUILDING_HOUSING_BARK_HUTS", owner: "HOUSEHOLD", ownerName: "Giurovici" }] }),
    [{ id: "BUILDING_HOUSING_BARK_HUTS", owner: "HOUSEHOLD", ownerName: "Giurovici" }]);
  // a building with no household behind it — ownerName defaults to null
  assert.deepEqual(buildingsOf({ buildings: [{ id: "BUILDING_CASTLE", owner: "RULER" }] }),
    [{ id: "BUILDING_CASTLE", owner: "RULER", ownerName: null }]);
  // the shape an older server (a deploy behind the static site) still sends: bare id strings
  assert.deepEqual(buildingsOf({ buildings: ["BUILDING_CASTLE"] }),
    [{ id: "BUILDING_CASTLE", owner: "NONE", ownerName: null }]);
  // and the empty cases nobody should have to guard at the call site
  assert.deepEqual(buildingsOf({}), []);
  assert.deepEqual(buildingsOf(null), []);
});

test("indexDistricts keys by plot and skips coordinate-less entries", async () => {
  const { indexDistricts, plotKey } = await import("./district-plots.mjs");
  const idx = indexDistricts([{ x: 4012, y: 888, buildings: [] }, { index: 3, buildings: [] }]);
  assert.equal(idx.size, 1, "an older server's index-only entry cannot be placed, so it is skipped");
  assert.ok(idx.get(plotKey(4012, 888)));
});
