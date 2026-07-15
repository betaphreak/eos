"use strict";
// Unit tests for the pure route auto-tiler (route-tiling.mjs). Run: node --test web/js/
// (the web/ direction is node:test — see docs/route-rendering.md, web-unit-tests-wanted).
import { test } from "node:test";
import assert from "node:assert/strict";
import { DIR, rotateMask, routePiece, neighbourMask } from "./route-tiling.mjs";

const { N, E, S, W } = DIR;

test("rotateMask sends N→E→S→W→N clockwise", () => {
  assert.equal(rotateMask(N, 1), E);
  assert.equal(rotateMask(E, 1), S);
  assert.equal(rotateMask(S, 1), W);
  assert.equal(rotateMask(W, 1), N);
  assert.equal(rotateMask(N, 4), N);                 // full turn is identity
  assert.equal(rotateMask(N | E, 2), S | W);         // multi-bit rotates coherently
});

test("routePiece classifies every canonical piece at rot 0", () => {
  assert.deepEqual(routePiece(0), { piece: "iso", rot: 0 });
  assert.deepEqual(routePiece(N), { piece: "end", rot: 0 });
  assert.deepEqual(routePiece(N | S), { piece: "straight", rot: 0 });
  assert.deepEqual(routePiece(N | E), { piece: "corner", rot: 0 });
  assert.deepEqual(routePiece(N | E | S), { piece: "tee", rot: 0 });
  assert.deepEqual(routePiece(N | E | S | W), { piece: "cross", rot: 0 });
});

test("routePiece rotates ends and straights", () => {
  assert.deepEqual(routePiece(E), { piece: "end", rot: 1 });
  assert.deepEqual(routePiece(S), { piece: "end", rot: 2 });
  assert.deepEqual(routePiece(W), { piece: "end", rot: 3 });
  assert.deepEqual(routePiece(E | W), { piece: "straight", rot: 1 });   // horizontal through
});

test("routePiece rotates corners through all four quadrants", () => {
  assert.equal(routePiece(N | E).rot, 0);
  assert.equal(routePiece(E | S).rot, 1);
  assert.equal(routePiece(S | W).rot, 2);
  assert.equal(routePiece(W | N).rot, 3);
  assert.equal(routePiece(E | S).piece, "corner");
});

test("routePiece rotates tees by the missing direction", () => {
  assert.deepEqual(routePiece(N | E | S), { piece: "tee", rot: 0 });   // missing W
  assert.deepEqual(routePiece(E | S | W), { piece: "tee", rot: 1 });   // missing N
  assert.deepEqual(routePiece(S | W | N), { piece: "tee", rot: 2 });   // missing E
  assert.deepEqual(routePiece(W | N | E), { piece: "tee", rot: 3 });   // missing S
});

test("routePiece is total — all 16 masks resolve to a real piece", () => {
  const pieces = new Set(["iso", "end", "straight", "corner", "tee", "cross"]);
  const counts = {};
  for (let m = 0; m < 16; m++) {
    const { piece, rot } = routePiece(m);
    assert.ok(pieces.has(piece), `mask ${m} → unknown piece ${piece}`);
    assert.ok(rot >= 0 && rot < 4, `mask ${m} → bad rot ${rot}`);
    counts[piece] = (counts[piece] || 0) + 1;
  }
  // 1 iso + 4 ends + 2 straights + 4 corners + 4 tees + 1 cross = 16
  assert.deepEqual(counts, { iso: 1, end: 4, straight: 2, corner: 4, tee: 4, cross: 1 });
});

test("neighbourMask reads N=−y and the orthogonal ring", () => {
  assert.equal(neighbourMask(() => false), 0);
  assert.equal(neighbourMask((dx, dy) => dx === 0 && dy === -1), N);
  assert.equal(neighbourMask((dx, dy) => dx === 1 && dy === 0), E);
  assert.equal(neighbourMask(() => true), N | E | S | W);
});
