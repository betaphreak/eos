"use strict";
// The repaint scheduler — the app's single "please redraw" entry point.
//
// WHY IT IS ITS OWN MODULE. This is 20 lines of rAF bookkeeping that half the frontend needs to
// call: plots, plotcanvas, plotfetch, routes, techtree and political all want `draw` and nothing
// else from it. While it lived in main.mjs, each of them dragged in the 491-line orchestrator —
// which imports them back — so a 44-line leaf like plotcanvas.mjs transitively depended on the
// whole scene renderer. That was the source of most of the module graph's import cycles.
//
// It deliberately imports NOTHING. The frame body (paint + the post-paint refreshes) is injected by
// main.mjs via setFrame, because those refreshes live in modules that themselves call draw() — so
// importing them here would just recreate the cycle this module exists to remove.

// what a frame actually does, installed by main.mjs at boot. Until then draw() is a safe no-op: an
// asset can land (and call draw) before the app has finished wiring itself up.
let frame = () => {};

/** Install the frame body. Called once, by main.mjs. */
export function setFrame(fn) { frame = fn; }

// The scene renders ON DEMAND — there is no free-running loop — so these two rules are the whole
// paint policy:
//   COALESCE  one paint per animation frame, so a burst of pan/zoom/pinch events (mobile fires many
//             touchmoves per frame) collapses into a single scene render.
//   CAP       at most FPS_CAP paints a second: a frame that comes due early re-queues itself until
//             the budget has elapsed. The scene is heavy, and a 120Hz pan asked for frames nobody
//             needed. Coalescing still holds while it waits (rafPending stays set), so the deferred
//             paint draws the NEWEST camera, never a stale one.
const FPS_CAP = 30, MIN_FRAME_MS = 1000 / FPS_CAP;
let rafPending = false, lastPaintAt = 0;

/** Request a redraw. Cheap and idempotent — call it whenever anything visible may have changed. */
export function draw() {
  if (rafPending) return;
  rafPending = true;
  const tick = () => {
    const now = performance.now();
    if (now - lastPaintAt < MIN_FRAME_MS) { requestAnimationFrame(tick); return; }   // under budget — hold
    lastPaintAt = now;
    rafPending = false;
    frame();
  };
  requestAnimationFrame(tick);
}
