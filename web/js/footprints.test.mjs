"use strict";
// Unit tests for the pure footprint geometry (footprints.mjs). Run: node --test web/js/
import { test } from "node:test";
import assert from "node:assert/strict";
import { footprintCells, plotBlocks } from "./footprints.mjs";

test("no buildings, no boxes", () => {
  assert.deepEqual(footprintCells(0, 100), []);
  assert.deepEqual(footprintCells(3, 0), []);
});

test("every block stays inside its plot cell", () => {
  for (const n of [1, 2, 5, 9, 17]) {
    for (const box of footprintCells(n, 60, 10, 20)) {
      assert.ok(box.x >= 10 && box.y >= 20, `n=${n}: block starts inside the cell`);
      assert.ok(box.x + box.w <= 70.001 && box.y + box.h <= 80.001,
        `n=${n}: block ends inside the cell`);
      assert.ok(box.w > 0 && box.h > 0);
    }
  }
});

test("blocks never overlap", () => {
  const boxes = footprintCells(7, 100);
  for (let i = 0; i < boxes.length; i++)
    for (let j = i + 1; j < boxes.length; j++) {
      const a = boxes[i], b = boxes[j];
      const apart = a.x + a.w <= b.x + 1e-9 || b.x + b.w <= a.x + 1e-9
        || a.y + a.h <= b.y + 1e-9 || b.y + b.h <= a.y + 1e-9;
      assert.ok(apart, `blocks ${i} and ${j} overlap`);
    }
});

test("a lone building gets a big block, a crowded plot small ones", () => {
  const one = footprintCells(1, 100)[0];
  const many = footprintCells(9, 100)[0];
  assert.ok(one.w > many.w * 2, "one building reads as much heavier than nine");
});

test("the layout is deterministic — a plot does not shimmer", () => {
  assert.deepEqual(footprintCells(5, 40, 3, 4), footprintCells(5, 40, 3, 4));
});

test("finished blocks come first, scaffolds last", () => {
  const blocks = plotBlocks(
    [{ id: "BUILDING_CASTLE", owner: "RULER" }],
    [{ id: "BUILDING_HOUSING_BARK_HUTS", cost: 20, progress: 5, owner: "HOUSEHOLD" }]);
  assert.deepEqual(blocks.map(b => b.id),
    ["BUILDING_CASTLE", "BUILDING_HOUSING_BARK_HUTS"]);
  assert.equal(blocks[0].progress, null, "a finished building has no progress");
  assert.equal(blocks[1].progress, 0.25);
});

test("a costless construction reads as unstarted, never NaN", () => {
  const [b] = plotBlocks([], [{ id: "X", cost: 0, progress: 3, owner: "RULER" }]);
  assert.equal(b.progress, 0);
});

test("progress is clamped to the bar's range", () => {
  const [b] = plotBlocks([], [{ id: "X", cost: 10, progress: 99, owner: "RULER" }]);
  assert.equal(b.progress, 1);
});
