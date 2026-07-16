"use strict";
// Unit tests for the notification board's pure clock (notify-age.mjs). Run: npm test --prefix web
import { test } from "node:test";
import assert from "node:assert/strict";
import { ageDays, expired, ramp, rampColor, isDate, minusDays, LIFETIME_DAYS, MAX_CARDS } from "./notify-age.mjs";

test("isDate accepts an ISO in-game date and rejects what the wire really sends", () => {
  assert.ok(isDate("1444-12-11"));
  assert.ok(isDate("1456-02-18"));
  // THE cases this exists for: SimLog stamps an unbound line "----------", and the session date is
  // "" until a colony reports. Both must be caught — a NaN age would silently disable expiry and
  // leave the card on screen forever.
  assert.equal(isDate("----------"), false, "SimLog's unbound-line placeholder");
  assert.equal(isDate(""), false, "the pre-first-colony session date");
  assert.equal(isDate(undefined), false);
  assert.equal(isDate(null), false);
  assert.equal(isDate("1444-13-45"), false, "well-formed but not a real date");
  assert.equal(isDate("1444-12-11T00:00:00Z"), false, "date-only is the wire format");
});

test("ageDays counts in-game days between two ISO dates", () => {
  assert.equal(ageDays("1444-12-11", "1444-12-11"), 0, "born today");
  assert.equal(ageDays("1444-12-11", "1444-12-12"), 1);
  assert.equal(ageDays("1444-12-11", "1445-01-10"), 30, "exactly a lifetime");
  assert.equal(ageDays("1445-03-16", "1445-03-20"), 4, "a real pair from output/24680");
});

test("ageDays spans months, years and a leap day", () => {
  assert.equal(ageDays("1444-12-11", "1445-12-11"), 365);
  assert.equal(ageDays("1444-02-28", "1444-03-01"), 2, "1444 is a leap year — 29 Feb exists");
  assert.equal(ageDays("1445-02-28", "1445-03-01"), 1, "1445 is not");
});

test("ageDays is defensive: never negative, 0 on an unusable date", () => {
  // the session date is the MAX over colonies, so born <= now always holds — but a clamp here is
  // what keeps a future-dated card from ramping backwards out of range if that ever changes
  assert.equal(ageDays("1445-01-10", "1444-12-11"), 0, "a future birth reads as fresh, not negative");
  assert.equal(ageDays("----------", "1445-01-10"), 0);
  assert.equal(ageDays("1444-12-11", ""), 0);
  assert.equal(ageDays(undefined, undefined), 0);
});

test("ageDays is immune to the host timezone", () => {
  // ISO date-only strings parse as UTC by spec. If this ever regressed to local-time parsing, a
  // machine east/west of UTC would round a whole day off and cards would expire a day early/late.
  const days = ageDays("1444-12-11", "1444-12-12");
  assert.equal(days, 1, "one calendar day apart is exactly one day, wherever this runs");
});

test("expired pops a card at exactly LIFETIME_DAYS, not before", () => {
  assert.equal(expired(0), false);
  assert.equal(expired(LIFETIME_DAYS - 1), false, "still alive on day 29");
  assert.equal(expired(LIFETIME_DAYS), true, "gone on day 30");
  assert.equal(expired(LIFETIME_DAYS + 100), true);
});

test("ramp runs 0 → 1 across the lifetime and clamps outside it", () => {
  assert.equal(ramp(0), 0, "just posted");
  assert.equal(ramp(15), 0.5, "half way");
  assert.equal(ramp(30), 1, "expiring");
  assert.equal(ramp(45), 1, "clamped past expiry");
  assert.equal(ramp(-5), 0, "clamped below");
});

test("rampColor reddens monotonically and stays a legal colour", () => {
  const rgb = s => s.match(/\d+/g).map(Number);
  let prevRed = -1;
  for (let age = 0; age <= LIFETIME_DAYS; age++) {
    const [r, g, b] = rgb(rampColor(age));
    for (const c of [r, g, b]) assert.ok(Number.isInteger(c) && c >= 0 && c <= 255, `channel ${c} out of range at ${age}d`);
    assert.ok(r >= prevRed, `red never decreases (age ${age})`);
    prevRed = r;
  }
  // the ends are the endpoints themselves, and they are distinguishable
  assert.equal(rampColor(0), "rgb(20, 26, 38)", "fresh = the panel background");
  assert.equal(rampColor(LIFETIME_DAYS), "rgb(110, 21, 34)", "expiring = red");
  assert.equal(rampColor(999), rampColor(LIFETIME_DAYS), "clamped past expiry");
});

test("rampColor's red channel dominates by the end — the cue actually reads", () => {
  const rgb = s => s.match(/\d+/g).map(Number);
  const [r0, , b0] = rgb(rampColor(0));
  assert.ok(b0 > r0, "fresh reads blue-slate, not red");
  const [r1, g1, b1] = rgb(rampColor(LIFETIME_DAYS));
  assert.ok(r1 > g1 * 2 && r1 > b1 * 2, "expiring is unambiguously red");
});

test("minusDays walks back the in-game calendar for the rehydrate window", () => {
  assert.equal(minusDays("1445-01-10", 30), "1444-12-11", "the board's whole 30-day window");
  assert.equal(minusDays("1445-01-10", 0), "1445-01-10");
  assert.equal(minusDays("1445-03-01", 1), "1445-02-28", "1445 is not a leap year");
  assert.equal(minusDays("1444-03-01", 1), "1444-02-29", "1444 is");
  assert.equal(minusDays("1445-01-01", 1), "1444-12-31", "across a year boundary");
});

test("minusDays is the exact inverse of ageDays — the window asks for what the board can show", () => {
  // if these disagreed the board would rehydrate lines that expire on arrival, or miss live ones
  for (const now of ["1445-01-10", "1444-03-05", "1500-12-31"]) {
    const from = minusDays(now, LIFETIME_DAYS);
    assert.equal(ageDays(from, now), LIFETIME_DAYS, `${from} is exactly a lifetime before ${now}`);
    assert.ok(expired(ageDays(from, now)), "a line dated exactly at the window edge is already expired");
    assert.ok(!expired(ageDays(minusDays(now, LIFETIME_DAYS - 1), now)), "a day inside the edge still shows");
  }
});

test("minusDays refuses an unusable date rather than inventing one", () => {
  assert.equal(minusDays("----------", 30), "");
  assert.equal(minusDays("", 30), "");
  assert.equal(minusDays(undefined, 30), "");
});

test("the card cap is a real backstop above the expected board size", () => {
  // a real run emits ~1 line per in-game month per colony, so a 30-day window holds ~1-3 cards; the
  // cap only exists for a burst, and must sit far above the normal case to never bite in one
  assert.ok(MAX_CARDS > LIFETIME_DAYS, "even one card per day for a whole lifetime fits");
});
