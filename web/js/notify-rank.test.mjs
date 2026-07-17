"use strict";
// Unit tests for the board's rank rule (notify-rank.mjs). Run: npm test --prefix web
import { test } from "node:test";
import assert from "node:assert/strict";
import { RANK, UNRANKED, WINDOW, VIEWER_RANK_DEFAULT, visibleTo, prominentTo } from "./notify-rank.mjs";

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

test("the window is three rungs wide, centred on the viewer", () => {
  const viewer = RANK.VILLAGE; // a ruler
  assert.ok(visibleTo(RANK.HOLDING, viewer), "one below: their noble vassals");
  assert.ok(visibleTo(RANK.VILLAGE, viewer), "their own level");
  assert.ok(visibleTo(RANK.CITY, viewer), "one above: the tier they answer to");
  assert.ok(!visibleTo(RANK.CARAVAN, viewer), "two below is out of scope");
  assert.ok(!visibleTo(RANK.LEAGUE, viewer), "two above is out of scope");
  assert.equal(WINDOW, 1, "one rung either way");
});

test("a viewer sees exactly one rung below — their vassals — and no further", () => {
  // A ruler (VILLAGE 3) hears how their noble houses (HOLDING 2) are doing...
  assert.ok(visibleTo(RANK.HOLDING, RANK.VILLAGE), "a ruler sees their holdings");
  // ...but not one peasant family's affairs (HOUSEHOLD 0), two rungs down
  assert.ok(!visibleTo(RANK.HOUSEHOLD, RANK.VILLAGE), "a ruler does not hear about one family");
  // a mayor (CITY 4) sees the villages under them, but not a single holding
  assert.ok(visibleTo(RANK.VILLAGE, RANK.CITY), "a mayor sees a village");
  assert.ok(!visibleTo(RANK.HOLDING, RANK.CITY), "a mayor does not see a holding");
});

test("the window is bounded ABOVE too — a caravan has no use for politics", () => {
  // THE correction this rule exists for. Playing an adventurer company (CARAVAN 1) — which is where
  // single-player starts — you care about your band, its families, and the holding you deal with. A
  // duchy's war or a hegemony's diplomacy is news you can neither act on nor care about, and letting
  // it through would drown the band's own story.
  const band = RANK.CARAVAN;
  assert.ok(visibleTo(RANK.HOUSEHOLD, band), "your families");
  assert.ok(visibleTo(RANK.HOLDING, band), "the holding above you");
  for (const far of [RANK.VILLAGE, RANK.CITY, RANK.DUCHY, RANK.KINGDOM, RANK.HEGEMONY])
    assert.ok(!visibleTo(far, band), `a caravan is not told about rank ${far}`);
});

test("the window slides as you climb — the game declutters itself", () => {
  // the same HOUSEHOLD event: news to a captain, nothing to a ruler or a mayor. This is the sprawl
  // answer — one event ranked once, filtered per viewer, not a per-colony mute list.
  const poiDeath = RANK.HOUSEHOLD;
  assert.ok(visibleTo(poiDeath, RANK.CARAVAN), "a captain knows every family in the band");
  assert.ok(!visibleTo(poiDeath, RANK.VILLAGE), "a ruler has grown past it");
  assert.ok(!visibleTo(poiDeath, RANK.CITY), "and a mayor never hears it");
  // ...and symmetrically, what the high ranks deal with never reached the captain in the first place.
  // NB the rung above a COUNTY (8) is a MARCH (9), not a DUCHY (10): the ladder alternates, so the
  // step up from a singular entity is the plural collective of it, and only the step after that
  // consolidates. "One rank above" is one RUNG, not one title you'd recognise from a map.
  assert.ok(visibleTo(RANK.MARCH, RANK.COUNTY), "a count's world is the march they sit in");
  assert.ok(!visibleTo(RANK.DUCHY, RANK.COUNTY), "the duchy above that is already too far");
  assert.ok(!visibleTo(RANK.DUCHY, RANK.CARAVAN), "and a caravan is nowhere near it");
});

test("every viewer sees exactly three rungs (fewer only at the ladder's ends)", () => {
  const levels = Object.values(RANK);
  for (const viewer of levels) {
    const seen = levels.filter(e => visibleTo(e, viewer));
    const atEnd = viewer === RANK.HOUSEHOLD || viewer === RANK.HEGEMONY;
    assert.equal(seen.length, atEnd ? 2 : 3, `a viewer at ${viewer} sees ${seen}`);
  }
});

test("prominence is the same axis: your business is a card, a vassal's is a one-liner", () => {
  assert.equal(prominentTo(RANK.VILLAGE, RANK.VILLAGE), true, "your own colony's news is a full card");
  assert.equal(prominentTo(RANK.CITY, RANK.VILLAGE), true, "so is the tier you answer to");
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

test("inside the window, exactly the vassal rung is dim", () => {
  // prominentTo is only ever asked about an event that PASSED visibleTo (notify.add returns early
  // otherwise), so the property to hold is about the window's three rungs — not about every rank,
  // where an out-of-window HEGEMONY line would read "prominent" to a caravan it never reaches.
  for (const viewer of Object.values(RANK))
    for (const event of Object.values(RANK)) {
      if (!visibleTo(event, viewer)) continue;
      assert.equal(prominentTo(event, viewer), event >= viewer,
        `rank ${event} to a viewer at ${viewer}: dim iff it is the rung below`);
    }
});

test("the default viewer rank is the live demo's own captain", () => {
  // the demo band is led by a Captain (Rank.CARAVAN), so the spectator sees the whole story down to
  // HOUSEHOLD — the filter only starts biting once a colony climbs
  assert.equal(VIEWER_RANK_DEFAULT, RANK.CARAVAN);
  assert.ok(visibleTo(RANK.HOUSEHOLD, VIEWER_RANK_DEFAULT), "nothing is hidden from the demo by default");
});
