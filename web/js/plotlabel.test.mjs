import { test } from "node:test";
import assert from "node:assert/strict";
import { prettyKey, escHtml, plotTip } from "./plotlabel.mjs";

test("prettyKey title-cases and strips the type prefix", () => {
  assert.equal(prettyKey("TERRAIN_GRASSLAND"), "Grassland");
  assert.equal(prettyKey("TERRAIN_SHADOW_SWAMP"), "Shadow Swamp");
  assert.equal(prettyKey("FEATURE_FOREST"), "Forest");
  assert.equal(prettyKey("BONUS_IRON"), "Iron");
});

test("escHtml escapes the three significant characters", () => {
  assert.equal(escHtml("A & B <c>"), "A &amp; B &lt;c&gt;");
  assert.equal(escHtml("Kraków"), "Kraków"); // non-ASCII untouched
});

test("plotTip composes name + terrain · feature", () => {
  assert.equal(
    plotTip({ name: "Kraków", terrain: "TERRAIN_GRASSLAND", feature: "FEATURE_FOREST" }),
    `<b>Kraków</b><br><span class="r">Grassland · Forest</span>`);
});

test("plotTip omits missing parts", () => {
  assert.equal(plotTip({ terrain: "TERRAIN_DESERT" }), `<span class="r">Desert</span>`);
  assert.equal(plotTip({ name: "Foo & Bar" }), `<b>Foo &amp; Bar</b>`);
  assert.equal(plotTip({}), "");
});
