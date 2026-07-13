import test from "node:test";
import assert from "node:assert/strict";
import { diffRoster } from "./js/roster-diff.mjs";

const seat = (role, id, name) => ({ role, personId: id, name, race: "human", gender: "male" });

test("no change → no successions", () => {
  const r = [seat("technology", 1, "A"), seat("foreign", 2, "B")];
  assert.deepEqual(diffRoster(r, r), []);
});

test("first-time fill is not a succession", () => {
  const changes = diffRoster([], [seat("technology", 1, "A")]);
  assert.deepEqual(changes, []);
});

test("a seat changing holder is a succession", () => {
  const prev = [seat("technology", 1, "Percy"), seat("foreign", 2, "Freeman")];
  const next = [seat("technology", 3, "Aldo"), seat("foreign", 2, "Freeman")];
  const changes = diffRoster(prev, next);
  assert.equal(changes.length, 1);
  assert.equal(changes[0].role, "technology");
  assert.equal(changes[0].from.name, "Percy");
  assert.equal(changes[0].to.name, "Aldo");
});

test("multiple simultaneous successions", () => {
  const prev = [seat("technology", 1, "A"), seat("foreign", 2, "B")];
  const next = [seat("technology", 9, "X"), seat("foreign", 8, "Y")];
  assert.equal(diffRoster(prev, next).length, 2);
});

test("a vacated seat (gone from next) is not reported", () => {
  const prev = [seat("technology", 1, "A"), seat("foreign", 2, "B")];
  const next = [seat("technology", 1, "A")];
  assert.deepEqual(diffRoster(prev, next), []);
});

test("null/undefined inputs are safe", () => {
  assert.deepEqual(diffRoster(undefined, undefined), []);
  assert.deepEqual(diffRoster(null, [seat("foreign", 1, "A")]), []);
});
