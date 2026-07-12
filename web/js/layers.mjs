"use strict";
// The LAYER REGISTRY — the single source of truth for the scene's draw ORDER (array order =
// back-to-front paint order), per-layer GATING, and a band annotation. main.renderScene() runs
// renderLayers() once per on-screen world copy. To reorder a layer, move its line; to change when
// it appears, edit its `gate`; the actual band fade is single-sourced inside each draw fn (Phase 2)
// and summarised here in `band`. This is the "draw order + band mapping in one place" seam —
// see docs/zoom-bands.md §Layer registry.
//
// The draw fns live in the modules that own their state (main.mjs closes over the raster/camera and
// the province-polygon helpers; the overlays own their own); this module only orders and gates them.
import { isPolitical, S } from "./core.mjs";
import { atLeast, BAND } from "./bands.mjs";
import { drawRaster, drawLakes, drawSeaCells, drawGapHatch, drawImpassable, drawSurfacePlots,
         drawProvinceBorders, drawUnderworld, drawCaveEntrances, drawAdjacencies,
         drawHoverHighlight, drawSelectedHighlight } from "./main.mjs";
import { drawCostOverlay, drawTradeGoodIcons } from "./plots.mjs";
import { drawTiers } from "./overlays/tiers.mjs";
import { drawPolitical } from "./overlays/political.mjs";
import { drawLive } from "./overlays/live.mjs";
import { drawLabels } from "./labels.mjs";

const notPolitical = () => !isPolitical();
const overworld    = () => S.plane !== "underworld";

// Back-to-front. `band` documents where the layer lives on the zoom spine (self-fading layers carry
// their own bandAlpha inside `draw`); `gate` is a cheap predicate that skips the layer entirely.
export const LAYERS = [
  { id: "raster",        band: "all",                     draw: drawRaster },
  { id: "lakes",         band: "all",                     draw: drawLakes },
  { id: "seaCells",      band: "all",  gate: notPolitical, draw: drawSeaCells },
  { id: "gapHatch",      band: "≥PLOT (64×)", gate: () => atLeast(BAND.PLOT) && notPolitical(), draw: drawGapHatch },
  { id: "plots",         band: "≥REGION→, self-fade", gate: notPolitical, draw: drawSurfacePlots },
  { id: "cost",          band: "≥REGION→, toggle",        draw: drawCostOverlay },
  { id: "impassable",    band: "all",  gate: notPolitical, draw: drawImpassable },
  { id: "political",     band: "self-fade", gate: isPolitical, draw: drawPolitical },
  { id: "tiers",         band: "WORLD–PROVINCE, self-fade", draw: drawTiers },
  { id: "provBorders",   band: "PROVINCE (7.5→10×)",      draw: drawProvinceBorders },
  { id: "underworld",    band: "all",  gate: () => S.plane === "underworld", draw: drawUnderworld },
  { id: "caveEntrances", band: "all",  gate: overworld,   draw: drawCaveEntrances },
  { id: "adjacencies",   band: "≥3.3 (10×)",              draw: drawAdjacencies },
  { id: "hover",         band: "all",                     draw: drawHoverHighlight },
  { id: "selected",      band: "all",                     draw: drawSelectedHighlight },
  { id: "live",          band: "all",  gate: () => S.overlay === "live", draw: drawLive },
  { id: "tradeGoods",    band: "TERRAIN→PLOT, self-fade", gate: () => overworld() && notPolitical(), draw: drawTradeGoodIcons },
  { id: "labels",        band: "≥PROVINCE, self-fade",    draw: drawLabels },
];

/** Paint the registry in order for the current world copy (called per-copy by renderScene). */
export function renderLayers() {
  for (const L of LAYERS) {
    if (L.gate && !L.gate()) continue;
    L.draw();
  }
}
