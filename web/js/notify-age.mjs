"use strict";
// The notification board's MATH — pure, and therefore testable (notify-age.test.mjs).
//
// A notification lives for LIFETIME_DAYS *in-game* days and reddens as it approaches that expiry.
// Both of those are functions of the sim clock alone, so they take the dates as ARGUMENTS and this
// module imports nothing — the same split band-math.mjs / river-geom.mjs / route-tiling.mjs use, so
// it loads under node with no DOM. notify.mjs keeps everything that touches an element.
// See docs/notifications.md.

// A card's lifetime, in IN-GAME days. This is a simulation-time surface, not a UI-time one: a paused
// session freezes every card mid-ramp, and at speed 5 a card is born, reddens and dies inside a
// couple of real seconds. That is the point — the board reads the sim's clock, not the wall's.
export const LIFETIME_DAYS = 30;

// Hard cap on retained cards. Expiry alone normally keeps the board at ~1-3 (a real run emits ~1 log
// line per in-game month per colony), and the host CLIPS anything taller than the viewport — but
// clipping is visual only: the nodes would still be live. A burst (mass starvation, or a run at
// -Deos.log.level=FINE) must not grow the DOM without bound, so the oldest are dropped outright.
export const MAX_CARDS = 60;

const MS_PER_DAY = 86400000;

// The ramp endpoints, sRGB. Fresh is the panel/toast background; expiring is a desaturated blood red
// — dark enough at full ramp that white text still reads on it (both endpoints are dark, so the
// straight sRGB lerp between them stays perceptually sane without an oklab detour).
const FRESH = [20, 26, 38];    // #141a26
const OLD = [110, 21, 34];     // #6e1522

const clamp01 = x => x < 0 ? 0 : x > 1 ? 1 : x;

/**
 * Whether `s` is an ISO yyyy-mm-dd date the board can age from. The wire carries the in-game date as
 * a plain ISO string, but SimLog emits "----------" for a line logged with no colony bound, and the
 * session date is "" before the first colony reports — neither can be aged, and both must be caught
 * here rather than becoming a NaN that silently disables expiry.
 */
export const isDate = s => typeof s === "string" && /^\d{4}-\d{2}-\d{2}$/.test(s) && Number.isFinite(Date.parse(s));

/**
 * In-game days elapsed from `born` to `now`, both ISO yyyy-mm-dd. 0 if either is unusable, and never
 * negative. ISO date-only strings parse as UTC by spec, so this is exact for the 1444+ dates in play
 * and immune to the host's timezone.
 */
export function ageDays(born, now) {
  if (!isDate(born) || !isDate(now)) return 0;
  const d = (Date.parse(now) - Date.parse(born)) / MS_PER_DAY;
  return d > 0 ? Math.round(d) : 0;
}

/** Whether a card of this age has outlived LIFETIME_DAYS and should pop. */
export const expired = age => age >= LIFETIME_DAYS;

/**
 * `iso` shifted back `n` days, as an ISO yyyy-mm-dd string; "" if `iso` is unusable. Used to ask the
 * server's event tail for exactly the window the board can show — there is no point rehydrating
 * lines that would expire the moment they landed.
 */
export function minusDays(iso, n) {
  if (!isDate(iso)) return "";
  return new Date(Date.parse(iso) - n * MS_PER_DAY).toISOString().slice(0, 10);
}

/** A card's position along its life, 0 (just posted) → 1 (expiring), clamped. */
export const ramp = age => clamp01(age / LIFETIME_DAYS);

/** The background colour for a card of `age` days: FRESH → OLD, linear along the ramp. */
export function rampColor(age) {
  const t = ramp(age);
  const ch = i => Math.round(FRESH[i] + (OLD[i] - FRESH[i]) * t);
  return `rgb(${ch(0)}, ${ch(1)}, ${ch(2)})`;
}
