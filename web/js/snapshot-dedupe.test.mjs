"use strict";
// Unit tests for the log-delta gate (snapshot-dedupe.mjs). Run: node --test web/js/
// (the web/ direction is node:test — see docs/game-over.md, web-unit-tests-wanted.)
import { test } from "node:test";
import assert from "node:assert/strict";
import { makeLogGate } from "./snapshot-dedupe.mjs";

test("each tick's lines are ingested exactly once", () => {
  const g = makeLogGate();
  assert.equal(g.accept("s1", 0), true);
  assert.equal(g.accept("s1", 1), true);
  assert.equal(g.accept("s1", 2), true);
});

test("the reported bug: a reconnect replaying the cached final frame posts nothing twice", () => {
  // production, 2026-07-17: a STOPPED session hands every re-subscriber the same cached frame,
  // whose one-line delta is "Dhenijansar departed as a Caravan …"
  const g = makeLogGate();
  assert.equal(g.accept("caravan-demo-7654321", 2639), true, "first delivery posts the departure");
  assert.equal(g.accept("caravan-demo-7654321", 2639), false, "a reconnect must not post it again");
  assert.equal(g.accept("caravan-demo-7654321", 2639), false, "…nor the one after that");
});

test("an out-of-order or stale frame is not ingested", () => {
  const g = makeLogGate();
  g.accept("s1", 10);
  assert.equal(g.accept("s1", 9), false, "an older frame's lines are already posted");
  assert.equal(g.accept("s1", 10), false);
  assert.equal(g.accept("s1", 11), true, "…but the next real tick still lands");
});

test("a different session starts fresh — its ticks restart at 0", () => {
  const g = makeLogGate();
  g.accept("caravan-demo-7654321", 2639);
  // a re-founded session (new id) begins at tick 0; those lines are new, not "already seen"
  assert.equal(g.accept("caravan-demo-999", 0), true);
  assert.equal(g.accept("caravan-demo-999", 1), true);
});

test("reset forgets the session, so its lines are new again", () => {
  const g = makeLogGate();
  g.accept("s1", 5);
  assert.equal(g.accept("s1", 5), false);
  g.reset();                                    // left live mode; the board was cleared
  assert.equal(g.accept("s1", 5), true, "a cleared board must be able to rehydrate");
});

test("a frame with no usable tick is never swallowed", () => {
  const g = makeLogGate();
  // an older server, or a malformed frame: judging is impossible, so prefer a duplicate line over
  // a silently lost one
  assert.equal(g.accept("s1", undefined), true);
  assert.equal(g.accept("s1", null), true);
});
