"use strict";
// The continuous-zoom spine. cam.k (1…256) is divided into nine logical BANDS on the powers
// of two: band b = log2(cam.k) ∈ [0, 8]. Everything that draws or takes input declares a band
// ENVELOPE and reads its cross-fade alpha from here, instead of hand-rolling (cam.k - X)/Y
// ramps. This is the single source for zoom-band structure — it replaces the duplicated
// tierAlpha() trapezoid that lived in labels.mjs and overlays/tiers.mjs. See docs/zoom-bands.md.
//
// Draw ORDER and each layer's band mapping are meant to be edited in ONE place: a forthcoming
// ordered layer registry (docs/zoom-bands.md §Layer registry) sits on top of the helpers here —
// each layer descriptor pairs a draw fn with its {order, env}. Keep band tuning declarative
// (envelopes as data), never as scattered inline thresholds.
import { cam } from "./core.mjs";

// canonical continuous band position. cam.k ≥ 1 always (the fitView world-fit floor), so b ≥ 0.
export const band = () => Math.log2(cam.k);

// trapezoidal visibility envelope in BAND units [in0, in1, out0, out1]: 0 outside [in0,out1];
// ramp up over [in0,in1]; hold at 1 through out0; ramp down to out1. Omit out0/out1 (they
// default to Infinity) to "fade in and stay" — e.g. terrain textures that never fade back out.
export function bandAlpha([in0, in1, out0 = Infinity, out1 = Infinity]) {
  const b = band();
  if (b <= in0 || b >= out1) return 0;
  if (b < in1) return (b - in0) / (in1 - in0);
  if (b <= out0) return 1;
  return (out1 - b) / (out1 - out0);
}

// Legacy helper: convert a trapezoid written in cam.k units to band units, so the readable
// cam.k thresholds carried over from the pre-band code stay legible at the call site — e.g.
// kBand([3.6,4.7,7,9.5]). The fade ENDPOINTS are preserved exactly (log2 is monotonic); only
// the mid-fade opacity ramp becomes linear-in-band rather than linear-in-k, a sub-perceptual
// difference for a cross-fading label. New envelopes should be written directly in band units;
// these legacy tables get re-tuned to clean band values in the later feel pass.
export const kBand = ks => ks.map(k => Math.log2(k));

// hard gate for when a fade is the wrong affordance (a line/icon that should just appear)
export const atLeast = n => band() >= n;

// ---- named bands + the three interaction regimes (the input spine, docs/zoom-bands.md) ----
export const BAND = {
  WORLD: 0, REALM: 1, REGION: 2,          // 🌍 Atlas    — EU4 grand strategy
  PROVINCE: 3, TERRAIN: 4, LOCALE: 5,     // 🐫 Overland — caravan / operational
  PLOT: 6, PARCEL: 7, STRUCTURE: 8,       // 🏘️ Ground   — city-builder micro
};
// band index → display name (index = the BAND value). bandName() is the nearest band to the current
// continuous position — what the top-bar readout shows in place of the raw zoom number.
export const BAND_NAMES = ["World", "Realm", "Region", "Province", "Terrain", "Locale", "Plot", "Parcel", "Structure"];
export const bandName = () => BAND_NAMES[Math.max(0, Math.min(8, Math.round(band())))];

export const REGIME = { ATLAS: "atlas", OVERLAND: "overland", GROUND: "ground" };
// display metadata for the mode chip / signal (the accent colour + cursor live in CSS, keyed by
// the same regime slug via [data-regime]); name + icon are the semantic bits, so they live here.
export const REGIME_INFO = {
  atlas:    { name: "Atlas",    icon: "🌍" },   // EU4 grand strategy
  overland: { name: "Overland", icon: "🐫" },   // caravan / operational
  ground:   { name: "Ground",   icon: "🏘️" },   // city-builder micro
};

// current interaction regime, HYSTERETIC: each seam (b=3, b=6) carries a ±0.15-band deadband so
// a scroll-tick landing on a boundary can't strobe the mode chip / cursor / transition pulse.
// Stateful by design — it reads the previously-latched regime to choose the asymmetric threshold.
let _regime = REGIME.ATLAS;
const DEADBAND = 0.15;
export function regime() {
  const b = band();
  const up = _regime === REGIME.ATLAS ? 3 : 3 - DEADBAND;   // fall back to Atlas only past the deadband
  const dn = _regime === REGIME.GROUND ? 6 : 6 + DEADBAND;  // rise to Ground only past the deadband
  _regime = b < up ? REGIME.ATLAS : b < dn ? REGIME.OVERLAND : REGIME.GROUND;
  return _regime;
}
