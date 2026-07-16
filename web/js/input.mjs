"use strict";
// Map INPUT: the gestures that move the camera — wheel zoom, mouse drag-pan, one-finger pan and
// two-finger pinch, the zoom buttons, reset-to-world, and fullscreen.
//
// Split out of panel.mjs (which contains no panel — see clock.mjs's header). This seam is "things
// that drive cam", and nothing else: picking, hover and the rail stay in panel, because they answer
// "what is under the cursor", which is a different question from "where is the camera".
//
// Every gesture here goes through S.techOpen: the tech tree owns the viewport while it is up, so
// the map must not zoom or pan behind it.
import { cam, VIEW, stage, clampPan, S } from "./core.mjs";
import { draw } from "./repaint.mjs";
import { zoomAt } from "./main.mjs";

stage.addEventListener("wheel", e => {
  if (S.techOpen) return;   // the tech tree owns the viewport while it's up — don't zoom the map behind it
  e.preventDefault();
  const r = stage.getBoundingClientRect();
  zoomAt(e.clientX - r.left, e.clientY - r.top, Math.exp(-e.deltaY * 0.0016));
}, { passive: false });

let lastX = 0, lastY = 0, panMoved = false;

/**
 * Did the gesture that just ended actually PAN (rather than being a click in place)? Reading it
 * clears it — the click handler uses this to tell "released a drag" from "clicked a province", and
 * that answer is only good once.
 *
 * This is the one piece of state the input seam shares with the picking seam, so it crosses as an
 * explicit call instead of a module-level `let` two features reached into.
 */
export function consumePanMoved() {
  const was = panMoved;
  panMoved = false;
  return was;
}

stage.addEventListener("mousedown", e => {
  if (e.button !== 0 || S.techOpen) return;   // map panning disabled while the tech tree is up
  S.dragging = true; panMoved = false; lastX = e.clientX; lastY = e.clientY;
  stage.classList.add("grabbing");
});
window.addEventListener("mousemove", e => {
  if (!S.dragging) return;
  const dx = e.clientX - lastX, dy = e.clientY - lastY;
  if (Math.abs(dx) + Math.abs(dy) > 2) panMoved = true;
  cam.x += dx; cam.y += dy; lastX = e.clientX; lastY = e.clientY;
  clampPan(); S.baseVersion++; draw();
});
// no draw() here: the drag's last mousemove already painted (or has a paint pending), and letting go
// changes nothing on the canvas — the grab cursor is CSS. Repainting on mouseup just bought a wasted
// full scene render on every click.
window.addEventListener("mouseup", () => { if (S.dragging) { S.dragging = false; stage.classList.remove("grabbing"); } });

// --- touch: one finger drags to pan, two fingers pinch to zoom (mobile has no wheel/mouse) ---
stage.style.touchAction = "none";                       // stop the browser scrolling/zooming the page
let touchMode = 0, pinchDist = 0, pinchCX = 0, pinchCY = 0;   // 0 none · 1 pan · 2 pinch
stage.addEventListener("touchstart", e => {
  if (S.techOpen) return;   // map gestures disabled while the tech tree is up
  if (e.touches.length === 1) {
    touchMode = 1; S.dragging = true; panMoved = false;
    lastX = e.touches[0].clientX; lastY = e.touches[0].clientY;
  } else if (e.touches.length >= 2) {
    const a = e.touches[0], b = e.touches[1], r = stage.getBoundingClientRect();
    touchMode = 2; S.dragging = true; panMoved = true;
    pinchDist = Math.hypot(a.clientX - b.clientX, a.clientY - b.clientY);
    pinchCX = (a.clientX + b.clientX) / 2 - r.left; pinchCY = (a.clientY + b.clientY) / 2 - r.top;
  }
}, { passive: true });
stage.addEventListener("touchmove", e => {
  e.preventDefault();                                   // we own the gesture (touch-action:none too)
  if (touchMode === 1 && e.touches.length >= 1) {
    const t = e.touches[0], dx = t.clientX - lastX, dy = t.clientY - lastY;
    if (Math.abs(dx) + Math.abs(dy) > 2) panMoved = true;
    cam.x += dx; cam.y += dy; lastX = t.clientX; lastY = t.clientY;
    clampPan(); S.baseVersion++; draw();
  } else if (touchMode === 2 && e.touches.length >= 2) {
    const a = e.touches[0], b = e.touches[1], r = stage.getBoundingClientRect();
    const d = Math.hypot(a.clientX - b.clientX, a.clientY - b.clientY);
    const cx = (a.clientX + b.clientX) / 2 - r.left, cy = (a.clientY + b.clientY) / 2 - r.top;
    cam.x += cx - pinchCX; cam.y += cy - pinchCY;       // pan by the pinch centre's movement…
    if (pinchDist > 0) zoomAt(cx, cy, d / pinchDist);   // …then zoom by the finger-distance ratio (zoomAt draws)
    else { clampPan(); S.baseVersion++; draw(); }
    pinchDist = d; pinchCX = cx; pinchCY = cy;
  }
}, { passive: false });
function endTouch(e) {
  if (e.touches && e.touches.length === 1) {            // lifted one of two fingers → resume single-finger pan
    touchMode = 1; lastX = e.touches[0].clientX; lastY = e.touches[0].clientY; return;
  }
  if (e.touches && e.touches.length > 0) return;
  touchMode = 0; S.dragging = false; draw();
}
stage.addEventListener("touchend", endTouch);
stage.addEventListener("touchcancel", endTouch);

document.getElementById("zoomIn").onclick = () => zoomAt(VIEW.w / 2, VIEW.h / 2, 1.5);
document.getElementById("zoomOut").onclick = () => zoomAt(VIEW.w / 2, VIEW.h / 2, 1 / 1.5);

/** Reset to the whole world (keyboard 0 / Home — the corner-icon button is fullscreen now). */
export function resetView() { cam.k = 1; cam.x = 0; cam.y = 0; clampPan(); S.baseVersion++; draw(); }

/** Fullscreen the map stage (canvas + its overlay controls); the resize handler re-fits on change. */
export function toggleFullscreen() {
  if (document.fullscreenElement) document.exitFullscreen();
  else stage.requestFullscreen?.();
}
document.getElementById("zoomReset").onclick = toggleFullscreen;
// (#zoomLevel is the Main Map advisor segment now — advisors.mjs wires its click to setAdvisor;
//  reset-to-world lives on the 0/Home key and the zoomctl reset button.)
