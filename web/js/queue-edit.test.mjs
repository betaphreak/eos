"use strict";
// Unit tests for the build queue's edit reducer (queue-edit.mjs) — the whole write story of the
// city screen's queue controls. Run: node --test web/js/
import { test } from "node:test";
import assert from "node:assert/strict";
import { reorder, append } from "./queue-edit.mjs";

const Q = ["A", "B", "C"];

test("cancel removes exactly that item", () => {
  assert.deepEqual(reorder(Q, "drop", 1), ["A", "C"]);
  assert.deepEqual(Q, ["A", "B", "C"], "the input list is never mutated");
});

test("sooner swaps with the item ahead, later with the one behind", () => {
  assert.deepEqual(reorder(Q, "up", 2), ["A", "C", "B"]);
  assert.deepEqual(reorder(Q, "down", 0), ["B", "A", "C"]);
});

test("the ends do not wrap", () => {
  assert.deepEqual(reorder(Q, "up", 0), Q, "the head cannot move sooner");
  assert.deepEqual(reorder(Q, "down", 2), Q, "the tail cannot move later");
});

test("an out-of-range index is a no-op, not a crash", () => {
  assert.deepEqual(reorder(Q, "drop", 9), Q);
  assert.deepEqual(reorder(Q, "drop", -1), Q);
  assert.deepEqual(reorder([], "up", 0), []);
  assert.deepEqual(reorder(undefined, "drop", 0), []);
});

test("an unknown verb changes nothing", () => {
  assert.deepEqual(reorder(Q, "sideways", 1), Q);
});

test("a decree appends in pick order", () => {
  assert.deepEqual(append(Q, ["D", "E"]), ["A", "B", "C", "D", "E"]);
});

test("a building already ordered is not queued twice", () => {
  assert.deepEqual(append(Q, ["B", "D"]), ["A", "B", "C", "D"]);
  assert.deepEqual(append([], ["X", "X"]), ["X"]);
});

test("appending nothing leaves the queue alone", () => {
  assert.deepEqual(append(Q, []), Q);
  assert.deepEqual(append(Q, undefined), Q);
});
