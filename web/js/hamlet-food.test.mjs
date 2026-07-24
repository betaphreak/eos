import test from "node:test";
import assert from "node:assert/strict";

import { isFed, larderChip, farmChip } from "./hamlet-food.mjs";

test("a village at or above its floor is fed", () => {
  assert.equal(isFed({ larder: 12, larderFloor: 10 }), true);
  assert.equal(isFed({ larder: 10, larderFloor: 10 }), true, "exactly at the floor counts as fed");
  assert.equal(isFed({ larder: 9.9, larderFloor: 10 }), false);
});

test("a village with nobody to feed is trivially fed", () => {
  assert.equal(isFed({ larder: 0, larderFloor: 0 }), true);
  assert.equal(isFed({}), true, "and a district with no larder fields at all does not read hungry");
});

test("the larder chip marks a hungry village", () => {
  const fed = larderChip({ larder: 40, larderFloor: 10 }, true);
  assert.match(fed, /city-larder/);
  assert.doesNotMatch(fed, /hungry/, "a fed village carries no hungry class or wording");

  const hungry = larderChip({ larder: 2, larderFloor: 10 }, true);
  assert.match(hungry, /city-larder hungry/, "a short larder is flagged in the class");
  assert.match(hungry, /going hungry/, "and said in words in the tooltip");
});

test("the larder chip shows fine detail only when the village is running low", () => {
  assert.match(larderChip({ larder: 123.4, larderFloor: 10 }, true), /🍞123/);
  assert.match(larderChip({ larder: 0.44, larderFloor: 10 }, true), /🍞0\.4/,
    "near zero, the decimal is the difference between starving tonight and not");
});

test("no chip for a plot that is no hamlet, or one with no larder", () => {
  assert.equal(larderChip({ larder: 50, larderFloor: 10 }, false), "",
    "the city center is the civic core, not a village");
  assert.equal(larderChip({ larder: 0, larderFloor: 0 }, true), "",
    "a colony not running village larders shows nothing rather than an empty pantry");
  assert.equal(larderChip({}, true), "");
});

test("the farm chip counts the village's own farms and says it in singular", () => {
  assert.equal(farmChip({ farms: 0 }), "");
  assert.equal(farmChip({}), "");
  assert.match(farmChip({ farms: 1 }), /🌾1/);
  assert.match(farmChip({ farms: 1 }), /1 necessity farm works this village/);
  assert.match(farmChip({ farms: 3 }), /3 necessity farms work this village/);
});
