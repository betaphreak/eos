// Entry point. The WorldMap is split into ES modules under js/:
//   core   — data, projection/camera, shared state (S), constants, small utils
//   plots  — the per-plot terrain-zoom layer + movement-cost overlay
//   labels — map text: province names and the zoom-banded geographic tiers
//   main   — the draw() orchestrator, camera raster, zoom, deep-link
//   panel  — all DOM interaction: sidebar, search, tooltips, timeline, events
// data.js (a classic script) sets window.BUNDLE before this deferred module runs.
import "./js/core.mjs";
import "./js/plots.mjs";
import "./js/labels.mjs";
import "./js/main.mjs";
import { boot } from "./js/panel.mjs";
import { initTechTree } from "./js/techtree.mjs";
import { initShortcuts } from "./js/shortcuts.mjs";

boot();
initTechTree();
initShortcuts();
