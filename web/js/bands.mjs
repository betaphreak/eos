"use strict";
// The continuous-zoom spine — the LIVE half: the thin wrappers that read cam.k, plus the one piece
// of latched state (the regime). Everything that draws or takes input declares a band ENVELOPE and
// reads its cross-fade alpha from here, instead of hand-rolling (cam.k - X)/Y ramps.
//
// The arithmetic itself lives in band-math.mjs — pure, zero-import, unit-tested. This module is the
// seam that binds it to the camera; the constants (BAND, BAND_NAMES, REGIME, REGIME_INFO,
// GEO_TIER_ENV, kBand) are re-exported below so every caller still imports them from "./bands.mjs"
// and nothing outside needs to know about the split. See docs/zoom-bands.md.
import { cam } from "./core.mjs";
import { bandAlphaAt, bandNameAt, regimeAt, REGIME } from "./band-math.mjs";

export { BAND, BAND_NAMES, REGIME, REGIME_INFO, GEO_TIER_ENV, kBand } from "./band-math.mjs";

// canonical continuous band position. cam.k ≥ 1 always (the fitView world-fit floor), so b ≥ 0.
export const band = () => Math.log2(cam.k);

/** This envelope's cross-fade alpha at the current zoom. See band-math.bandAlphaAt. */
export const bandAlpha = env => bandAlphaAt(env, band());

// hard gate for when a fade is the wrong affordance (a line/icon that should just appear)
export const atLeast = n => band() >= n;

/** The nearest band's display name at the current zoom. */
export const bandName = () => bandNameAt(band());

// The current interaction regime. STATEFUL by design: the hysteresis needs the previously-latched
// regime to pick its asymmetric threshold, so the latch lives here and the decision lives in the
// pure regimeAt (which is why the deadband is testable from both directions).
let _regime = REGIME.ATLAS;
export function regime() {
  _regime = regimeAt(band(), _regime);
  return _regime;
}
