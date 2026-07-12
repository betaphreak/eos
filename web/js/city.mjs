"use strict";
// The Ground-regime micro layer (docs/zoom-bands.md §bands 6–8): as you dive into the Ground regime
// the urban core resolves into discrete BUILDING FOOTPRINTS — the first genuinely new pixels of the
// city-builder view. Real data only: each province's grid carries TERRAIN_URBAN plots (their x,y) and
// the province's `dev` (how built-up it is); we subdivide each urban plot into deterministic lots,
// denser where dev is higher. No new server data — the plots are the ones drawPlots already streams.
//
// Deferred (need feed/engine data that doesn't exist yet, docs/zoom-bands.md §Introducing z-levels):
// real per-agent/household dots and per-building labels + the Ground input pick — the live feed today
// streams only the colony's aggregate counts, not agent/building positions.
import { P, cam, ctx, pxr, pyr, provOnScreen, isPolitical } from "./core.mjs";
import { bandAlpha } from "./bands.mjs";

// deterministic 0..1 hash per (x, y, salt) — stable footprints across redraws (no Math.random)
function h2(x, y, salt) {
  let n = (Math.imul((x | 0) ^ 0x9e3779b1, 2654435761) ^ Math.imul((y | 0) + salt, 40503)) >>> 0;
  n = (n ^ (n >>> 15)) >>> 0;
  return (n % 10000) / 10000;
}
const ROOFS = ["#8a6a4a", "#7c5a3c", "#94765a", "#6f5138", "#a07d54"];   // varied roof tones

// subdivide one urban plot (grid x,y) into a lot grid, denser with development
function drawLots(gx, gy, plotPx, dev) {
  const ox = pxr(gx), oy = pyr(gy);
  const n = Math.max(2, Math.min(5, 2 + Math.round(dev * 3)));   // 2×2 hamlet → 5×5 dense core
  const cell = plotPx / n, gap = Math.max(0.5, cell * 0.14);
  const key = Math.max(0.5, cell * 0.045);
  for (let i = 0; i < n; i++) for (let j = 0; j < n; j++) {
    if (h2(gx * n + i, gy * n + j, 7) < 0.14) continue;          // some empty lots — yards / streets
    const bx = ox + i * cell + gap * 0.5, by = oy + j * cell + gap * 0.5;
    ctx.fillStyle = ROOFS[(h2(gx + i, gy + j, 13) * ROOFS.length) | 0];
    ctx.fillRect(bx, by, cell - gap, cell - gap);
    ctx.strokeStyle = "rgba(10,8,6,0.5)"; ctx.lineWidth = key;
    ctx.strokeRect(bx, by, cell - gap, cell - gap);
  }
}

/** Building footprints over the urban plots in view — fades in entering the Ground regime (band 6). */
export function drawCity() {
  const a = bandAlpha([5.5, 6.2]);   // fade in over Locale→Plot (band units — a fresh Phase-6 envelope)
  if (a <= 0.01 || isPolitical()) return;
  const plotPx = pxr(1) - pxr(0);
  if (plotPx < 20) return;           // urban plots too small on screen to read lots
  ctx.save();
  ctx.globalAlpha = a;
  for (const p of P) {
    if (!p._plots || !p._plots.length || !provOnScreen(p)) continue;
    const dev = p.dev || 0;
    for (const q of p._plots) if (q.terrain === "TERRAIN_URBAN") drawLots(q.x, q.y, plotPx, dev);
  }
  ctx.restore();
}
