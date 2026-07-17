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
import { initLobby, openLobby, closeLobby, lobbyOpen } from "./js/lobby.mjs";

boot();
initTechTree();
initAdvisor();   // build the advisor selector + sub-bar, deriving the mode from boot()'s render state
initShortcuts();
initSiteAuth();   // resolves identity at boot: the control now lives in the LOBBY header, but the
                  // body.auth-anon class it sets gates the map transport whether the lobby is open or not
initDiag();      // the top bar's fps · latency readout (starts the ping poll)
initLobby();     // the Spectator Lobby, opened by the brand ("home") — docs/spectator-lobby.md
// exposed on window for the same reason the picker is: index.html's pre-module boot flow and
// panel.mjs's home gesture both need to reach it without importing this module graph
window.__lobby = { open: openLobby, close: closeLobby, isOpen: lobbyOpen };
// A session picked in the lobby DURING the load (index.html opens it while the bundle downloads):
// the choice is waiting on window, and live.mjs will connect to it — but only Live mode shows it, so
// switch there. Clicking the toggle rather than reaching into its state keeps one source of truth.
if (window.__spectate) {
  const liveBtn = document.querySelector('#overlayToggle button[data-ov="live"]');
  if (liveBtn && !liveBtn.classList.contains('on')) liveBtn.click();
}
