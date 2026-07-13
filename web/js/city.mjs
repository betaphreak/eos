"use strict";
// A subtle city marker over each urban core plot. This is the INTERIM treatment: the old urban
// visuals — the synthetic grey-concrete ground tile, the Civ4 med_europe city sprite, and the
// procedural roof-lot footprints that used to live here — were all pulled pending proper Civ6
// district tiles (docs/urban-plots.md, docs/civ6-art-replacement.md). Urban plots now render as
// their surrounding countryside (re-terrained in plots.mjs markUrbanPlots); this layer just draws
// a small, muted pip so a city site is still locatable on the map.
//
// Real data only: markUrbanPlots flags each city-core plot `urban`; we draw one marker per flagged
// plot. No new server data — these are the plots drawPlots already streams.
import { P, ctx, pxr, pyr, provOnScreen, isPolitical } from "./core.mjs";
import { bandAlpha } from "./bands.mjs";

// one small settlement pip centred on an urban plot: a soft stone disc with a thin keyline and a
// faint highlight — reads as a marker, not a texture. Sized to a fraction of a plot but clamped so
// it stays a discreet dot across zoom.
function drawMarker(cx, cy, plotPx) {
  const r = Math.max(2.5, Math.min(plotPx * 0.22, 9));
  ctx.beginPath(); ctx.arc(cx, cy, r, 0, Math.PI * 2);
  ctx.fillStyle = "rgba(74,60,46,0.72)";                 // muted stone/umber
  ctx.fill();
  ctx.lineWidth = Math.max(0.75, r * 0.18);
  ctx.strokeStyle = "rgba(238,232,222,0.75)";            // pale keyline so it reads on any ground
  ctx.stroke();
  ctx.beginPath(); ctx.arc(cx - r * 0.3, cy - r * 0.3, r * 0.32, 0, Math.PI * 2);
  ctx.fillStyle = "rgba(250,246,238,0.55)";              // faint top-left highlight
  ctx.fill();
}

/** Subtle city markers over urban core plots — fades in once you're reading a region's terrain. */
export function drawCity() {
  const a = bandAlpha([3.5, 4.5]);   // fade in through Province→Terrain, then hold (locatable in-region)
  if (a <= 0.01 || isPolitical()) return;
  const plotPx = pxr(1) - pxr(0);
  ctx.save();
  ctx.globalAlpha = a;
  for (const p of P) {
    if (!p._plots || !p._plots.length || !provOnScreen(p)) continue;
    for (const q of p._plots) {
      if (!q.urban) continue;
      drawMarker(pxr(q.x) + plotPx / 2, pyr(q.y) + plotPx / 2, plotPx);
    }
  }
  ctx.restore();
}
