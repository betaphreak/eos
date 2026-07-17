"use strict";
// Unit tests for the lobby's row model (lobby-rows.mjs). Run: node --test web/js/
import { test } from "node:test";
import assert from "node:assert/strict";
import { title, status, isOver, canDelete, singlePlayer, ranked, order, KIND } from "./lobby-rows.mjs";

const timeline = (o = {}) => ({ id: "timeline-7654321", kind: KIND.TIMELINE, seed: 7654321,
  state: "CREATED", seats: 2, standing: 2, date: "1444-12-11", mine: false, ...o });
const slot = (o = {}) => ({ id: "run-1@alice", kind: KIND.SINGLE, colony: "Dhenijansar",
  state: "PAUSED", date: "1444-12-11", mine: true, ...o });

test("a run is named by its colony (naming is deferred to countries)", () => {
  assert.equal(title(slot()), "Dhenijansar");
  assert.equal(title(slot({ colony: null, id: "run-1@alice" })), "run-1@alice", "fall back to the id");
  assert.match(title(timeline()), /^Timeline/, "a Timeline is the world's, not a colony's");
});

test("a Timeline's status is its contest", () => {
  assert.equal(status(timeline({ state: "CREATED", seats: 3 })), "open for joins · 3 seated");
  assert.equal(status(timeline({ state: "RUNNING", seats: 12, standing: 7, date: "1452-03-03" })),
    "7 of 12 standing · 1452-03-03");
});

test("a save slot's status says whether it waits for you", () => {
  assert.equal(status(slot({ state: "PAUSED", date: "1444-12-11" })), "paused · 1444-12-11");
  assert.equal(status(slot({ state: "CREATED" })), "not started");
  assert.equal(status(slot({ state: "RUNNING", date: "1447-02-11" })), "1447-02-11");
});

test("a finished run reads as its verdict", () => {
  const dead = slot({ state: "GAME_OVER", endReason: "Dhenijansar departed as a Caravan on 1452-03-02" });
  assert.equal(status(dead), "Dhenijansar departed as a Caravan on 1452-03-02");
  assert.ok(isOver(dead));
  assert.equal(status(slot({ state: "GAME_OVER" })), "over", "…even with no reason given");
});

test("only your own single-player runs offer deletion", () => {
  assert.ok(canDelete(slot({ mine: true })));
  assert.ok(!canDelete(slot({ mine: false })), "not someone else's");
  assert.ok(!canDelete(timeline({ mine: true })), "a seat is not a save");
  assert.ok(!canDelete({ kind: KIND.DEMO, mine: false }), "the demo is nobody's");
});

test("Single Player is gated on signing in and on having a slot free", () => {
  assert.equal(singlePlayer([], false).enabled, false);
  assert.match(singlePlayer([], false).hint, /Sign in/);

  const free = singlePlayer([slot(), slot()], true, 5);
  assert.equal(free.enabled, true);
  assert.match(free.hint, /slot 3 of 5/, "it says which slot you are about to take");

  const full = singlePlayer([slot(), slot(), slot(), slot(), slot()], true, 5);
  assert.equal(full.enabled, false);
  assert.match(full.hint, /finish or delete one/);

  // a finished run does not hold a slot — the server's rule, mirrored here
  const withDead = [slot(), slot(), slot(), slot(), slot({ state: "GAME_OVER" })];
  assert.equal(singlePlayer(withDead, true, 5).enabled, true, "the dead run freed its slot");
  // ...and other people's runs are not your slots
  assert.equal(singlePlayer([slot({ mine: false })], true, 5).hint, "New run · slot 1 of 5");
});

test("Ranked's face follows the Timeline, and admits when there is none", () => {
  assert.equal(ranked([], true).enabled, false, "no Timeline, no offer");
  assert.match(ranked([], true).hint, /No Timeline/);

  const open = ranked([timeline({ state: "CREATED" })], true);
  assert.equal(open.enabled, true);
  assert.ok(open.join, "an open Timeline is joinable");
  assert.match(open.label, /^Join Timeline/);

  const running = ranked([timeline({ state: "RUNNING", seats: 4, standing: 3 })], true);
  assert.equal(running.enabled, true);
  assert.ok(!running.join, "the roster is closed — the honest offer is to watch");
  assert.match(running.label, /^Spectate Timeline/);

  assert.equal(ranked([timeline()], false).enabled, false, "signed out you may watch, not seat");
  assert.equal(ranked([timeline({ state: "GAME_OVER" })], true).enabled, false, "a finished Timeline is not open");
});

test("the list leads with the Timeline, then live runs, then the dead", () => {
  const rows = [
    slot({ colony: "Zeta", state: "GAME_OVER" }),
    slot({ colony: "Alpha" }),
    timeline(),
    slot({ colony: "Beta", mine: false }),
  ];
  const kinds = order(rows).map(r => r.kind + ":" + title(r));
  assert.match(kinds[0], /^timeline/, "the Timeline is the headline: " + kinds);
  assert.equal(kinds[1], "single-player:Alpha", "then yours: " + kinds);
  assert.equal(kinds[2], "single-player:Beta", "then other people's live runs: " + kinds);
  assert.match(kinds[3], /Zeta$/, "the dead sink: " + kinds);
});
