"use strict";
// The notification board's RANK rule — pure, and therefore testable (notify-rank.test.mjs).
//
// Every event carries the Rank of what it is ABOUT (com.civstudio.agent.Rank, sent as `rankLevel` on
// each LogLine): a household forming is HOUSEHOLD however dramatic, a colony dying is VILLAGE however
// quiet. Rank is scope, not severity.
//
// A viewer plays AT a rank and wants everything above their level plus at most one rung below — far
// enough to see how their vassals perform, and no further. That one rule does the work that a keyword
// allow-list was guessing at: prominence stops being a statement about wording and becomes a
// statement about scope, relative to whoever is watching. It is also what keeps many settlements
// readable — a household's news in one of fifty colonies is noise to a duke and the whole world to a
// captain, and it is the same event, ranked once.
//
// See docs/notifications.md.

// The ladder (contiguous, alternating singular/plural — mirrors com.civstudio.agent.Rank.level()).
// Kept as levels rather than names because the wire sends the level: comparing two ranks must not
// need a copy of the ladder on both sides.
export const RANK = {
  HOUSEHOLD: 0, CARAVAN: 1, HOLDING: 2, VILLAGE: 3, CITY: 4, LEAGUE: 5,
  BARONY: 6, VISCOUNTY: 7, COUNTY: 8, MARCH: 9, DUCHY: 10, PRINCIPATE: 11,
  KINGDOM: 12, FEDERATION: 13, EMPIRE: 14, HEGEMONY: 15,
};

// An unranked line — a plain log.info/@Log call site rather than SimLog.event — arrives as -1.
export const UNRANKED = -1;

// The viewer's rank until there is a player seat to ask (docs: project-direction — the seat is
// deferred). CARAVAN is what the live demo actually is: a captain leading a band, who by this rule
// sees everything down to HOUSEHOLD. So the spectator sees the whole story, and the filter only
// starts biting once a colony climbs — which is the ladder working, not a setting.
export const VIEWER_RANK_DEFAULT = RANK.CARAVAN;

/**
 * Whether an event of `rankLevel` reaches a viewer at `viewerLevel`: their own level and everything
 * above it, plus exactly one rung below (their vassals).
 *
 * An UNRANKED line is always shown: it predates the rank and we would rather show a line we cannot
 * place than silently drop it.
 */
export const visibleTo = (rankLevel, viewerLevel) =>
  rankLevel === UNRANKED || rankLevel >= viewerLevel - 1;

/**
 * Whether an event of `rankLevel` is the viewer's OWN business (their level or above) rather than a
 * vassal's — a full card versus a dim one-liner. Null for an unranked line, which has no rank to
 * judge by and falls back to the server's `curated` flag.
 */
export const prominentTo = (rankLevel, viewerLevel) =>
  rankLevel === UNRANKED ? null : rankLevel >= viewerLevel;
