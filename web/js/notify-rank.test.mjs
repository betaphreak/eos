"use strict";
// Unit tests for the board's rank rule (notify-rank.mjs). Run: npm test --prefix web
import { test } from "node:test";
import assert from "node:assert/strict";
import { RANK, UNRANKED, VIEWER_RANK_DEFAULT, visibleTo, prominentTo } from "./notify-rank.mjs";

test("the ladder mirrors the engine's Rank levels", () => {
  // these numbers are the wire contract (LogLine.rankLevel = Rank.level()); if the engine's ladder
  // moves, this is what should fail rather than the board quietly misfiltering
  assert.equal(RANK.HOUSEHOLD, 0);
  assert.equal(RANK.CARAVAN, 1);
  assert.equal(RANK.HOLDING, 2);
  assert.equal(RANK.VILLAGE, 3);
  assert.equal(RANK.CITY, 4);
  assert.equal(RANK.HEGEMONY, 15);
  const levels = Object.values(RANK);
  assert.deepEqual(levels, [...levels].sort((a, b) => a - b), "declared in ladder order");
  assert.equal(new Set(levels).size, levels.length, "no two ranks share a level");
});

test("a viewer sees their own level and everything above it", () => {
  for (const r of [RANK.VILLAGE, RANK.CITY, RANK.DUCHY, RANK.HEGEMONY])
    assert.ok(visibleTo(r, RANK.VILLAGE), `a ruler sees rank ${r}`);
});

test("a viewer sees exactly one rung below — their vassals — and no further", () => {
  // THE rule. A ruler (VILLAGE 3) hears how their noble houses (HOLDING 2) are doing...
  assert.ok(visibleTo(RANK.HOLDING, RANK.VILLAGE), "a ruler sees their holdings");
  // ...but not one peasant family's affairs (HOUSEHOLD 0), two rungs down
  assert.ok(!visibleTo(RANK.HOUSEHOLD, RANK.VILLAGE), "a ruler does not hear about one family");
  // a mayor (CITY 4) sees the villages under them, but not a single holding
  assert.ok(visibleTo(RANK.VILLAGE, RANK.CITY), "a mayor sees a village");
  assert.ok(!visibleTo(RANK.HOLDING, RANK.CITY), "a mayor does not see a holding");
});

test("the board declutters as you climb the ladder", () => {
  // the same HOUSEHOLD event: everything to a captain, nothing to a mayor. This is the sprawl
  // answer — it is one event ranked once, filtered per viewer, not a per-colony mute list.
  const poiDeath = RANK.HOUSEHOLD;
  assert.ok(visibleTo(poiDeath, RANK.CARAVAN), "a captain knows every family in the band");
  assert.ok(!visibleTo(poiDeath, RANK.VILLAGE), "a ruler has grown past it");
  assert.ok(!visibleTo(poiDeath, RANK.CITY), "and a mayor never hears it");
});

test("prominence is the same axis: your business is a card, a vassal's is a one-liner", () => {
  assert.equal(prominentTo(RANK.VILLAGE, RANK.VILLAGE), true, "your own colony's news is a full card");
  assert.equal(prominentTo(RANK.DUCHY, RANK.VILLAGE), true, "so is the wider world's");
  assert.equal(prominentTo(RANK.HOLDING, RANK.VILLAGE), false, "a vassal's news is dim");
  // ...and the dim rung is exactly the one that is still visible, so nothing is both shown and unrankable
  assert.ok(visibleTo(RANK.HOLDING, RANK.VILLAGE) && prominentTo(RANK.HOLDING, RANK.VILLAGE) === false);
});

test("an unranked line is shown, and defers on prominence", () => {
  // a plain log.info predates the rank; show it rather than silently drop it, and let the server's
  // curated flag decide how loud it is
  for (const viewer of [RANK.HOUSEHOLD, RANK.VILLAGE, RANK.HEGEMONY])
    assert.ok(visibleTo(UNRANKED, viewer), `unranked survives a viewer at ${viewer}`);
  assert.equal(prominentTo(UNRANKED, RANK.VILLAGE), null, "no rank to judge by → fall back to curated");
});

test("visibleTo and prominentTo agree: anything prominent is visible", () => {
  for (const viewer of Object.values(RANK))
    for (const event of Object.values(RANK))
      if (prominentTo(event, viewer))
        assert.ok(visibleTo(event, viewer), `rank ${event} prominent to ${viewer} must also be visible`);
});

test("the default viewer rank is the live demo's own captain", () => {
  // the demo band is led by a Captain (Rank.CARAVAN), so the spectator sees the whole story down to
  // HOUSEHOLD — the filter only starts biting once a colony climbs
  assert.equal(VIEWER_RANK_DEFAULT, RANK.CARAVAN);
  assert.ok(visibleTo(RANK.HOUSEHOLD, VIEWER_RANK_DEFAULT), "nothing is hidden from the demo by default");
});
