// Entry point. The WorldMap is split into ES modules under js/:
//   core   — data, projection/camera, shared state (S), constants, small utils
//   plots  — the per-plot terrain-zoom layer + movement-cost overlay
//   labels — map text: province names and the zoom-banded geographic tiers
//   main   — the draw() orchestrator, camera raster, zoom, deep-link
//   panel  — all DOM interaction: sidebar, search, tooltips, timeline, events
// index.html's bootstrap fetches window.BUNDLE from the server (GET /api/bundle) and only then
// dynamically imports this module, so window.BUNDLE is populated before core.mjs reads it.
import "./js/core.mjs";
import "./js/plots.mjs";
import "./js/labels.mjs";
import "./js/main.mjs";
import { boot } from "./js/panel.mjs";
import { initTechTree } from "./js/techtree.mjs";
import { initShortcuts } from "./js/shortcuts.mjs";
import { initSiteAuth } from "./js/auth.mjs";
import { initAdvisor } from "./js/advisors.mjs";
import { initDiag } from "./js/diag.mjs";

boot();
initTechTree();
initAdvisor();   // build the advisor selector + sub-bar, deriving the mode from boot()'s render state
initShortcuts();
initSiteAuth();
initDiag();      // the top bar's fps · latency readout (starts the ping poll)
