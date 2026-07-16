"use strict";
// Unit tests for the pure band spine (band-math.mjs). Run: npm test --prefix web
// This is the most-reused logic in the frontend — every layer's fade goes through bandAlphaAt, and
// regimeAt decides the cursor/chip/pulse — and it had no tests until the module was split out of
// bands.mjs (which reads cam, so it cannot load under node).
import { test } from "node:test";
import assert from "node:assert/strict";
import { bandAlphaAt, bandNameAt, regimeAt, kBand, BAND, BAND_NAMES, REGIME, REGIME_INFO, GEO_TIER_ENV, DEADBAND }
  from "./band-math.mjs";

test("bandAlphaAt ramps up, holds, ramps down", () => {
  const env = [2, 4, 6, 8];
  assert.equal(bandAlphaAt(env, 1), 0, "below in0");
  assert.equal(bandAlphaAt(env, 2), 0, "at in0 the envelope is still closed");
  assert.equal(bandAlphaAt(env, 3), 0.5, "mid ramp-up");
  assert.equal(bandAlphaAt(env, 4), 1, "at in1 fully open");
  assert.equal(bandAlphaAt(env, 5), 1, "held through the plateau");
  assert.equal(bandAlphaAt(env, 6), 1, "at out0 still open");
  assert.equal(bandAlphaAt(env, 7), 0.5, "mid ramp-down");
  assert.equal(bandAlphaAt(env, 8), 0, "at out1 closed");
  assert.equal(bandAlphaAt(env, 9), 0, "beyond out1");
});

test("bandAlphaAt with no out0/out1 fades in and stays", () => {
  const env = [3, 4];                          // e.g. terrain textures — never fade back out
  assert.equal(bandAlphaAt(env, 2), 0);
  assert.equal(bandAlphaAt(env, 3.5), 0.5);
  assert.equal(bandAlphaAt(env, 4), 1);
  assert.equal(bandAlphaAt(env, 9), 1, "still open at the deepest zoom");
  assert.equal(bandAlphaAt(env, 1e6), 1, "Infinity defaults mean it never closes");
});

test("bandAlphaAt never leaves 0..1", () => {
  const envs = [[2, 4, 6, 8], [3, 4], [0, 1, 1, 2], GEO_TIER_ENV.regions, GEO_TIER_ENV.continents];
  for (const env of envs)
    for (let b = -1; b <= 10; b += 0.25) {
      const a = bandAlphaAt(env, b);
      assert.ok(a >= 0 && a <= 1, `alpha ${a} out of range for env ${env} at b=${b}`);
    }
});

test("kBand converts cam.k thresholds to band units, preserving endpoints", () => {
  assert.deepEqual(kBand([1, 2, 4, 8]), [0, 1, 2, 3]);
  // the legacy tables' endpoints must survive the conversion exactly (log2 is monotonic)
  const [in0, , , out1] = kBand([3.6, 4.7, 7.0, 9.5]);
  assert.equal(in0, Math.log2(3.6));
  assert.equal(out1, Math.log2(9.5));
});

test("bandNameAt names the NEAREST band and clamps at both ends", () => {
  assert.equal(bandNameAt(0), "World");
  assert.equal(bandNameAt(3.4), "Province", "rounds down");
  assert.equal(bandNameAt(3.6), "Terrain", "rounds up");
  assert.equal(bandNameAt(8), "Building");
  assert.equal(bandNameAt(9), "Building", "the 512x cap is still Building, not undefined");
  assert.equal(bandNameAt(-1), "World", "clamped low");
  assert.equal(BAND_NAMES.length, 9, "one name per band");
  assert.equal(BAND_NAMES[BAND.SETTLEMENT], "Settlement", "names are indexed BY the BAND value");
});

test("regimeAt maps the three regimes", () => {
  assert.equal(regimeAt(0, REGIME.ATLAS), REGIME.ATLAS);
  assert.equal(regimeAt(4, REGIME.OVERLAND), REGIME.OVERLAND);
  assert.equal(regimeAt(8, REGIME.GROUND), REGIME.GROUND);
  for (const r of Object.values(REGIME)) assert.ok(REGIME_INFO[r], `${r} has display metadata`);
});

test("regimeAt hysteresis: a seam does not strobe", () => {
  // THE property this exists for. Sitting exactly on the b=3 seam and jittering by less than the
  // deadband must not flip the regime — a flip repaints the cursor, the chip and the pulse.
  let r = REGIME.ATLAS;
  for (const b of [3 - 0.01, 3 + 0.01, 3 - 0.05, 3 + 0.05]) {
    const next = regimeAt(b, r);
    if (b >= 3) assert.equal(next, REGIME.OVERLAND, `crossing up at ${b}`);
    else assert.equal(next, r === REGIME.ATLAS ? REGIME.ATLAS : REGIME.OVERLAND,
      `at ${b} it holds whatever was latched, rather than strobing`);
    r = next;
  }
});

test("regimeAt is asymmetric at each seam — you must overshoot to fall back", () => {
  // rising: Atlas holds until b reaches 3
  assert.equal(regimeAt(2.99, REGIME.ATLAS), REGIME.ATLAS);
  assert.equal(regimeAt(3.0, REGIME.ATLAS), REGIME.OVERLAND);
  // falling: once Overland, you stay Overland until b drops BELOW 3 - DEADBAND
  assert.equal(regimeAt(2.9, REGIME.OVERLAND), REGIME.OVERLAND, "inside the deadband it holds");
  assert.equal(regimeAt(3 - DEADBAND - 0.001, REGIME.OVERLAND), REGIME.ATLAS, "past it, it falls back");
  // The Ground seam (b=6) is deadbanded on the way UP only: rising out of Overland needs an
  // overshoot past 6 + DEADBAND, but once latched, Ground gives way the moment b drops below 6.
  // (So the deadband sits ABOVE the seam here, where at b=3 it sits below it — each deadband is on
  // the far side of the seam from the regime that owns it.)
  assert.equal(regimeAt(6.1, REGIME.OVERLAND), REGIME.OVERLAND, "inside the deadband it holds");
  assert.equal(regimeAt(6 + DEADBAND + 0.001, REGIME.OVERLAND), REGIME.GROUND, "past it, it rises");
  assert.equal(regimeAt(6.0, REGIME.GROUND), REGIME.GROUND, "Ground holds at the seam");
  assert.equal(regimeAt(5.99, REGIME.GROUND), REGIME.OVERLAND, "and gives way just below it");
});

test("regimeAt is stable — feeding its own output back never oscillates", () => {
  // the wrapper in bands.mjs latches the result and feeds it back every frame; a fixed camera must
  // therefore reach a fixed point rather than flapping
  for (const b of [0, 2.9, 3, 3.1, 5.9, 6, 6.1, 8]) {
    let r = REGIME.ATLAS;
    const first = (r = regimeAt(b, r));
    for (let i = 0; i < 5; i++) r = regimeAt(b, r);
    assert.equal(r, first, `b=${b} settles instead of oscillating`);
  }
});

test("a fade-out-only envelope is the exact complement of the fade-in it hands off to", () => {
  // The live overlay's caravan trail polyline is the OVERVIEW stand-in for the baked route art
  // (routes.mjs fades IN on [3.5, 4.5]); it must fade OUT across exactly that window, so the two
  // never both read at full strength and never leave a gap with neither. An open-ended leading edge
  // (-Infinity) is what makes an envelope "already on" at world-fit rather than ramping up.
  const OUT = [-Infinity, -Infinity, 3.5, 4.5];
  const IN = [3.5, 4.5];
  assert.equal(bandAlphaAt(OUT, 0), 1, "fully on at world fit");
  assert.equal(bandAlphaAt(OUT, 3.5), 1, "still fully on where the art starts to appear");
  assert.equal(bandAlphaAt(OUT, 4.5), 0, "fully off once the art is fully in");
  assert.equal(bandAlphaAt(OUT, 5), 0, "and stays off past it");
  assert.equal(bandAlphaAt(OUT, 4), 0.5, "half way across the hand-off");
  // the two envelopes partition the cross-fade: their alphas sum to 1 everywhere in the window
  for (const b of [3.5, 3.75, 4, 4.25, 4.5]) {
    assert.ok(Math.abs(bandAlphaAt(OUT, b) + bandAlphaAt(IN, b) - 1) < 1e-9,
      `b=${b}: the polyline and the route art cross-fade cleanly`);
  }
});
