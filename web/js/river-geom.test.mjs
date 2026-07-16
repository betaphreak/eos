"use strict";
// Unit tests for the pure river centre-line geometry (river-geom.mjs). Run: node --test web/js/
// (the web/ direction is node:test — see docs/river-rendering.md, web-unit-tests-wanted).
import { test } from "node:test";
import assert from "node:assert/strict";
import { riverClass, riverAdj, riverLinks, cellStrokes, ribbonWidth, CLASS_WIDTH } from "./river-geom.mjs";

// packed river code digits: class·100000 + adj·1000 + node·100 + flow·10 + authored width
const code = ({ cls = 0, adj = 0, node = 0, flow = 0, w = 1 }) =>
  cls * 100000 + adj * 1000 + node * 100 + flow * 10 + w;

const E = 1, W = 2, S = 4, N = 8;   // adjacency mask bits (NB4 order)

test("riverClass reads the class digit", () => {
  assert.equal(riverClass(code({ cls: 7, adj: 15, node: 3, flow: 8, w: 4 })), 7);
  assert.equal(riverClass(code({ cls: 1, w: 1 })), 1);
  assert.equal(riverClass(0), 0, "no river");
});

test("riverClass falls back to authored width on a pre-class pack", () => {
  // MAP_VERSION 8 packs carry no class digit — the ribbon must still taper, just more coarsely
  assert.equal(riverClass(code({ cls: 0, w: 4 })), 4);
  assert.equal(riverClass(code({ cls: 0, adj: 15, w: 2 })), 2);
});

test("riverAdj demasks with %100, so the class digit cannot corrupt it", () => {
  // the regression this guards: adj reaches 15 and so spans TWO decimal digits; a %16 demask
  // folds the class digit back in as garbage (915384/1000 = 915, 915%16 = 3, not 15)
  assert.equal(riverAdj(code({ cls: 9, adj: 15, node: 3, flow: 8, w: 4 })), 15);
  assert.equal(riverAdj(code({ cls: 9, adj: 0 })), 0);
  assert.equal(riverAdj(code({ cls: 0, adj: 15 })), 15, "and still works on a pre-class pack");
});

test("riverLinks prefers the global mask over the local probe", () => {
  // the mask names neighbours in the ADJACENT province, which the local grid cannot see — so a
  // border cell must link across the seam even when the local probe says there is nothing there
  const never = () => false;
  assert.deepEqual(riverLinks(code({ adj: E | W }), never), [0, 1]);
  assert.deepEqual(riverLinks(code({ adj: N }), never), [3]);
  assert.deepEqual(riverLinks(code({ adj: E | W | S | N }), never), [0, 1, 2, 3]);
});

test("riverLinks falls back to the local probe when the mask is absent", () => {
  const onlyEast = (dx, dy) => dx === 1 && dy === 0;
  assert.deepEqual(riverLinks(code({ adj: 0 }), onlyEast), [0]);
});

test("a straight run strokes edge to edge, through the centre", () => {
  const [sp] = cellStrokes([0, 1], 0, 0, 10);   // E + W
  assert.equal(sp.kind, "line");
  assert.deepEqual(sp.from, [10, 5], "the E shared-edge midpoint");
  assert.deepEqual(sp.to, [0, 5], "the W shared-edge midpoint");
});

test("a bend curves through the centre instead of turning a corner", () => {
  const [sp] = cellStrokes([0, 2], 0, 0, 10);   // E + S
  assert.equal(sp.kind, "curve");
  assert.deepEqual(sp.from, [10, 5]);
  assert.deepEqual(sp.ctrl, [5, 5], "the cell centre is the quadratic's control point");
  assert.deepEqual(sp.to, [5, 10]);
});

test("neighbouring cells meet exactly at their shared edge midpoint", () => {
  // THE seam property: a cell and its E neighbour each stroke to the same point, so their ribbons
  // join — including when the neighbour is drawn into a DIFFERENT province's canvas
  const s = 10;
  const [a] = cellStrokes([0, 1], 0, 0, s);          // cell at (0,0), running E–W
  const [b] = cellStrokes([0, 1], s, 0, s);          // its E neighbour, same run
  const aEast = a.from, bWest = b.to;
  assert.deepEqual(aEast, bWest, "both land on x = s, y = s/2");
});

test("a junction strokes one spoke per link, unsmoothed", () => {
  const sps = cellStrokes([0, 1, 2], 0, 0, 10);      // a tee
  assert.equal(sps.length, 3);
  for (const sp of sps) {
    assert.equal(sp.kind, "line");
    assert.deepEqual(sp.from, [5, 5], "every spoke starts at the centre");
  }
});

test("an isolated source is a degenerate line, painted as a dot by the round cap", () => {
  const [sp] = cellStrokes([], 0, 0, 10);
  assert.equal(sp.kind, "line");
  assert.deepEqual(sp.from, [5, 5]);
  assert.ok(Math.abs(sp.to[0] - 5) < 0.1 && sp.to[1] === 5, "a hair's length, so it caps to a round dot");
});

test("a headwater runs from the centre out to its one link", () => {
  const [sp] = cellStrokes([3], 0, 0, 10);           // N only
  assert.deepEqual(sp.from, [5, 5]);
  assert.deepEqual(sp.to, [5, 0]);
});

test("ribbon width grows with class and never fills the plot", () => {
  for (let c = 1; c < 9; c++)
    assert.ok(ribbonWidth(c + 1, 10) > ribbonWidth(c, 10), `class ${c + 1} is wider than ${c}`);
  assert.ok(ribbonWidth(9, 10) < 10, "even a trunk leaves terrain visible either side");
  assert.equal(CLASS_WIDTH.length, 10, "index 0 = no river, then classes 1..9");
});
