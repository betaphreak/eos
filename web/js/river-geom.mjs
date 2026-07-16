"use strict";
// River geometry — the pure centre-line path builder for the river ribbon (docs/river-rendering.md).
// Given a plot's packed river code it decodes the render width class and the network links, and emits
// the stroke geometry for that cell. This is the reusable core of the river renderer; it has NO
// imports so it unit-tests in node (river-geom.test.mjs) without the browser globals core.mjs pulls in
// — the same split as route-tiling.mjs / routes.mjs.
//
// WHY A CENTRE LINE. Our rivers come from EU4's rivers.bmp, which is PIXEL-based: a river is a chain
// of cells whose line runs through cell CENTRES. (Civ4's own river art is edge decals for edge-based
// rivers — a different model, which is why it was rejected; see the doc's §3 post-mortem.) So a cell's
// ribbon runs from its centre out to the shared edge with each linked neighbour. Two consequences fall
// out for free:
//   • adjacent cells meet EXACTLY at the shared-edge midpoint, including across a province seam —
//     each province's canvas draws its own half and the round cap completes the join;
//   • a bend curves through the centre instead of turning a right angle, so a diagonal river reads as
//     a river rather than a staircase.

// orthogonal neighbour offsets, in the bit order of the engine's river-adjacency mask
// (1=E, 2=W, 4=S, 8=N) — see ProvinceRaster.riverAdjMask / Plot.riverAdj.
export const NB4 = [[1, 0], [-1, 0], [0, 1], [0, -1]];

/**
 * The render width class of a packed river code: 1 (thread) .. 9 (trunk), 0 for no river.
 * Falls back to the authored width digit for packs baked before MAP_VERSION 9 carried a class, so an
 * older plot cache still renders a (coarser) taper instead of collapsing to a uniform ribbon.
 */
export function riverClass(code) {
  if (!code) return 0;
  const cls = Math.floor(code / 100000) % 10;
  return cls || Math.min(9, Math.max(1, code % 10));
}

/**
 * The river-adjacency mask of a packed river code: which orthogonal neighbours are also river cells
 * (bits 1=E, 2=W, 4=S, 8=N), or 0 when the cell stands alone / the pack predates the mask.
 * Demasked with % 100, not % 16: the mask reaches 15, so it spans two decimal digits and a % 16 would
 * fold the class digit above it back in as garbage.
 */
export function riverAdj(code) {
  return Math.floor(code / 1000) % 100;
}

/**
 * The NB4 indices this cell's river links to. Prefers the packed adjacency mask — which is computed
 * globally, so it names neighbours in the ADJACENT province and the ribbon crosses seams unbroken —
 * and falls back to the caller's local `isRiverAt(dx, dy)` probe when the mask is absent (older packs).
 */
export function riverLinks(code, isRiverAt) {
  const adj = riverAdj(code), out = [];
  for (let i = 0; i < 4; i++) {
    if (adj ? (adj & (1 << i)) : isRiverAt(NB4[i][0], NB4[i][1])) out.push(i);
  }
  return out;
}

/**
 * The stroke geometry of one river cell, in canvas units: the cell's top-left corner is (cx, cy) and
 * it spans `s` on both axes. Returns an array of subpaths, each either
 *   {kind: "line",  from: [x,y], to: [x,y]}                — a straight run, or a source dot
 *   {kind: "curve", from: [x,y], ctrl: [x,y], to: [x,y]}   — a bend, quadratic through the centre
 * so the caller can stroke every cell of a width class into ONE path.
 *
 * A cell with no links renders as a degenerate line — a round line cap paints it as a dot of exactly
 * the ribbon's width, so an isolated source needs no separate fill pass and can never disagree with
 * the ribbon it feeds.
 */
export function cellStrokes(links, cx, cy, s) {
  const mx = cx + s / 2, my = cy + s / 2;
  const centre = [mx, my];
  const edge = d => [mx + NB4[d][0] * s / 2, my + NB4[d][1] * s / 2];   // midpoint of the shared edge

  if (links.length === 0) return [{ kind: "line", from: centre, to: [mx + 0.01, my] }];
  if (links.length === 1) return [{ kind: "line", from: centre, to: edge(links[0]) }];
  if (links.length === 2) {
    const [a, b] = links, da = NB4[a], db = NB4[b];
    // opposite links (a straight run) pass through the centre; perpendicular links bend around it,
    // with the centre as the quadratic's control point
    if (da[0] === -db[0] && da[1] === -db[1]) return [{ kind: "line", from: edge(a), to: edge(b) }];
    return [{ kind: "curve", from: edge(a), ctrl: centre, to: edge(b) }];
  }
  // a tee or a cross: spokes from the centre. No smoothing — a junction is genuinely a corner, and
  // curving one branch into another would imply a flow that isn't there.
  return links.map(d => ({ kind: "line", from: centre, to: edge(d) }));
}

/**
 * The ribbon's full width, as a fraction of a plot, for each class 1..9 (index 0 = no river). Tuned
 * here rather than in the engine on purpose: the class is the DATA (one octave of drainage per step),
 * this curve is the LOOK, so it can be retuned without a map rebake.
 */
export const CLASS_WIDTH = [0, 0.14, 0.17, 0.21, 0.25, 0.29, 0.34, 0.39, 0.44, 0.50];

/** The ribbon's full width in canvas units for a class, at `s` px per plot. */
export function ribbonWidth(cls, s) {
  return s * (CLASS_WIDTH[cls] || CLASS_WIDTH[1]);
}
