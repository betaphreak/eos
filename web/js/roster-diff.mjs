"use strict";
// Pure roster-diff for advisor succession detection (docs/privy-council.md §2b). Given the previous
// and next advisor rosters (arrays of { role, personId, name, race, ... } from the live snapshot),
// return the seats whose holder changed — a succession. First-time fills (a role the previous
// roster did not hold) are NOT successions. No DOM, no state — unit-testable in node.
export function diffRoster(prev, next) {
  const prevByRole = new Map((prev || []).map(a => [a.role, a]));
  const changes = [];
  for (const a of next || []) {
    const p = prevByRole.get(a.role);
    if (p && p.personId !== a.personId)   // a seat that WAS filled now holds a different person
      changes.push({ role: a.role, from: p, to: a });
  }
  return changes;
}
