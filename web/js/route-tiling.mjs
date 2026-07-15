"use strict";
// Route auto-tiling — the pure Civ4-style piece picker for the road/trail/rail draw layer
// (docs/route-rendering.md). Given a plot's orthogonal-neighbour connection mask, it returns
// which baked connection sprite to stamp and how many 90° turns to rotate it. This is the
// reusable core of the per-plot renderer (gap C); it has NO imports so it unit-tests in node
// (route-tiling.test.mjs) without the browser globals core.mjs pulls in.
//
// The baked atlas ships six canonical pieces (build.mjs bakeRoutes / ROUTE_PIECES); every one of
// the 16 masks maps onto one of them at some rotation, matching Civ4's `Rotations "0 90 180 270"`.

// Orthogonal direction bits. Rotating a mask 90° CW sends N→E→S→W→N.
export const DIR = { N: 1, E: 2, S: 4, W: 8 };

// canonical mask each baked piece is drawn at (see ROUTE_PIECES `conn` in build.mjs):
//   iso -           end N          straight N|S       corner N|E        tee N|E|S       cross N|E|S|W
const CANON = { iso: 0, end: DIR.N, straight: DIR.N | DIR.S, corner: DIR.N | DIR.E,
                tee: DIR.N | DIR.E | DIR.S, cross: DIR.N | DIR.E | DIR.S | DIR.W };

/** Rotate a 4-bit N/E/S/W mask by `r` quarter-turns clockwise (N→E→S→W). */
export function rotateMask(mask, r) {
  let m = mask & 15;
  for (let i = 0; i < (r & 3); i++) m = ((m << 1) | (m >> 3)) & 15;
  return m;
}

/**
 * Pick the connection sprite + rotation for a plot's orthogonal-neighbour mask (bits DIR.N/E/S/W
 * set where an adjacent plot also carries a route). Returns {piece, rot} where `rot` is the number
 * of 90°-CW turns to apply to the canonical sprite. Total coverage: all 16 masks.
 */
export function routePiece(mask) {
  const m = mask & 15;
  for (const piece of ["iso", "end", "straight", "corner", "tee", "cross"])
    for (let r = 0; r < 4; r++)
      if (rotateMask(CANON[piece], r) === m) return { piece, rot: r };
  return { piece: "iso", rot: 0 };   // unreachable — the six canon masks cover every rotation class
}

/** Build a plot's neighbour mask from a `has(dx,dy)` predicate (does the orthogonal neighbour carry
 *  a route of the same/again-drawn tier). N is −y (north = up), matching the map's world +Y. */
export function neighbourMask(has) {
  return (has(0, -1) ? DIR.N : 0) | (has(1, 0) ? DIR.E : 0)
       | (has(0, 1) ? DIR.S : 0) | (has(-1, 0) ? DIR.W : 0);
}
