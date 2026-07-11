// The single hub for global keyboard shortcuts: one registry drives both the dispatch
// and the bottom bar (#hotbar), so a binding and its displayed hint can never drift. The
// actual actions live in the feature modules (map camera / playback in panel.mjs, the
// tech-tree modal in techtree.mjs); this module wires keys to them and renders the hints.
import { S, cam, VIEW, clampPan } from "./core.mjs";
import { draw, zoomAt } from "./main.mjs";
import { resetView, toggleFullscreen, togglePlay, closePanel } from "./panel.mjs";
import { toggleTech, closeTech } from "./techtree.mjs";

// pan the camera and repaint — the shared tail of the WASD / arrow handlers
function panBy(dx, dy) {
  cam.x += dx; cam.y += dy;
  clampPan(); S.baseVersion++; draw();
}

// One entry per shortcut. `keys` are the raw KeyboardEvent.key values that fire `run`;
// `cap` + `label` (when present) render a chip in the bottom bar. Entries with no `cap`
// (keyboard zoom, Home, arrow aliases) are dispatched but not shown.
const REGISTRY = [
  { keys: ["F7"], cap: ["F7"], label: "Tech tree", modalSafe: true,
    run: e => { e.preventDefault(); toggleTech(); } },
  { keys: [" ", "Spacebar"], cap: ["Space"], label: "Play / pause",
    run: e => { e.preventDefault(); togglePlay(); } },
  { keys: ["f", "F"], cap: ["F"], label: "Fullscreen",
    run: e => { e.preventDefault(); toggleFullscreen(); } },
  { keys: ["0", "Home"], cap: ["0"], label: "Reset view",
    run: e => { e.preventDefault(); resetView(); } },
  { keys: ["w", "W", "ArrowUp", "s", "S", "ArrowDown",
           "a", "A", "ArrowLeft", "d", "D", "ArrowRight"],
    cap: ["W", "A", "S", "D"], label: "Pan",
    run: e => {
      e.preventDefault();
      const step = Math.max(40, Math.min(VIEW.w, VIEW.h) * 0.12);
      const move = {
        w: [0, step], W: [0, step], ArrowUp: [0, step],
        s: [0, -step], S: [0, -step], ArrowDown: [0, -step],
        a: [step, 0], A: [step, 0], ArrowLeft: [step, 0],
        d: [-step, 0], D: [-step, 0], ArrowRight: [-step, 0],
      }[e.key];
      if (move) panBy(move[0], move[1]);
    } },
  { keys: ["Escape"], cap: ["Esc"], label: "Close", modalSafe: true,
    // the tech modal wins; otherwise collapse the sidebar (only prevent-default if it did something)
    run: e => { if (S.techOpen) { e.preventDefault(); closeTech(); } else if (closePanel()) { e.preventDefault(); } } },
  { keys: ["+", "="],
    run: e => { e.preventDefault(); zoomAt(VIEW.w / 2, VIEW.h / 2, 1.5); } },
  { keys: ["-", "_"],
    run: e => { e.preventDefault(); zoomAt(VIEW.w / 2, VIEW.h / 2, 1 / 1.5); } },
];

/** Render the hint chips into #hotbar and install the one global keydown dispatcher. */
export function initShortcuts() {
  const bar = document.getElementById("hotbar");
  if (bar) bar.innerHTML = REGISTRY.filter(s => s.cap).map(s =>
    `<span class="hk">${s.cap.map(k => `<kbd>${k}</kbd>`).join("")} ${s.label}</span>`
  ).join("");

  window.addEventListener("keydown", e => {
    if (e.metaKey || e.ctrlKey || e.altKey) return;                                  // leave OS/browser combos alone
    if (e.target instanceof HTMLElement && e.target.matches("input, textarea")) return;   // don't hijack typing
    const modal = S.techOpen;   // a modal runs in paused mode — only modal-safe keys pass (no play/pan/zoom)
    for (const s of REGISTRY)
      if (s.keys.includes(e.key)) {
        if (modal && !s.modalSafe) { e.preventDefault(); return; }
        s.run(e);
        return;
      }
  });
}
