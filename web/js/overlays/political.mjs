"use strict";
// Political overlay — the map render for the Nation/Culture/Faith overlays: province polygons
// coloured by the active dimension (core.polOf / S.overlay), zoom-banded so the map yields to the
// physical terrain as you dive in, plus the legend/search spotlight. The chrome (legend, entity
// search, sidebar Politics block) lives in panel.mjs; this module owns only the canvas render.
import { ctx, cam, P, provPath, polOf, K_PLOT, K_TEX, lerp, S } from "../core.mjs";

// "#rrggbb" + alpha -> an rgba() string, memoised (the nation/culture/faith fills)
const _rgbaCache = {};
export function hexA(hex, a) {
  const key = hex + "|" + a.toFixed(3);
  return _rgbaCache[key] || (_rgbaCache[key] =
    `rgba(${parseInt(hex.slice(1,3),16)},${parseInt(hex.slice(3,5),16)},${parseInt(hex.slice(5,7),16)},${a.toFixed(3)})`);
}

// Draw the active political overlay. Called by main.renderScene only while isPolitical() is true.
// Below K_PLOT it is a full-opacity overview; through K_PLOT→K_TEX the fill fades as the terrain
// plots appear; past K_TEX only coloured borders remain (plus the hovered province), letting the
// per-plot terrain read underneath. A province with no value for the dimension never fills.
export function drawPolitical() {
  if (cam.k < K_TEX) {
    const a = cam.k < K_PLOT ? 0.58
      : lerp(0.5, 0.15, (cam.k - K_PLOT) / (K_TEX - K_PLOT));
    for (const p of P) if (p.rings) {
      const e = polOf(p).e;
      if (e) { ctx.fillStyle = hexA(e.color, a); ctx.fill(provPath(p)); }
    }
  } else {
    ctx.lineWidth = 1.4;
    for (const p of P) if (p.rings) {
      const e = polOf(p).e;
      if (e) { ctx.strokeStyle = hexA(e.color, 0.9); ctx.stroke(provPath(p)); }
    }
    const hi = S.hoverProv, he = hi && hi.rings && polOf(hi).e;
    if (he) { ctx.fillStyle = hexA(he.color, 0.35); ctx.fill(provPath(hi)); }
  }
  // spotlight one polity (hovered in the legend / picked from search): brighten its provinces
  if (S.polHi) {
    ctx.save(); ctx.lineWidth = 2;
    for (const p of P) if (p.rings && polOf(p).key === S.polHi) {
      ctx.fillStyle = "rgba(255,255,255,.16)"; ctx.fill(provPath(p));
      ctx.strokeStyle = "#fff"; ctx.stroke(provPath(p));
    }
    ctx.restore();
  }
}
