"use strict";
// The notification board's RANK rule — pure, and therefore testable (notify-rank.test.mjs).
//
// Every event carries the Rank of what it is ABOUT (com.civstudio.agent.Rank, sent as `rankLevel` on
// each LogLine): a household forming is HOUSEHOLD however dramatic, a colony dying is VILLAGE however
// quiet. Rank is scope, not severity.
//
// A viewer plays AT a rank and sees a window THREE RUNGS WIDE, centred on themselves: one below (how
// their vassals perform), their own level, and one above (the world they actually operate in).
// Nothing further, in EITHER direction — an adventurer company does not care about hegemony politics
// any more than a hegemon cares about one family's harvest. Bounding the top is what makes the lowest
// rank playable, which is where single-player starts (an Anbennar-style adventurer company).
//
// That one rule does the work a keyword allow-list was guessing at: prominence stops being a
// statement about wording and becomes a statement about scope, relative to whoever is watching. It is
// also what keeps many settlements readable — a household's news in one of fifty colonies is noise to
// a duke and the whole world to a captain, and it is the same event, ranked once. The window SLIDES
// as you climb, so the game declutters itself.
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

// How far the window reaches from the viewer's own rung, in each direction.
export const WINDOW = 1;

// The viewer's rank until there is a player seat to ask (docs: project-direction — the seat is
// deferred). CARAVAN is both what the live demo literally is — a captain leading a band — and where
// single-player begins: an adventurer company at the bottom of the ladder, seeing households through
// holdings and nothing of the wider realm until it climbs.
export const VIEWER_RANK_DEFAULT = RANK.CARAVAN;

/**
 * Whether an event of `rankLevel` reaches a viewer at `viewerLevel`: within one rung either way —
 * their vassals below, themselves, and the tier they answer to above.
 *
 * Bounded ABOVE as well as below, which is the part that is easy to get wrong: a caravan has no use
 * for a kingdom's politics, and showing it would drown the band's own story in news it can neither
 * act on nor care about.
 *
 * An UNRANKED line is always shown: it predates the rank and we would rather show a line we cannot
 * place than silently drop it.
 */
export const visibleTo = (rankLevel, viewerLevel) =>
  rankLevel === UNRANKED || Math.abs(rankLevel - viewerLevel) <= WINDOW;

/**
 * Whether an event of `rankLevel` is the viewer's own business or their liege's (their level or the
 * rung above) rather than a vassal's — a full card versus a dim one-liner. Null for an unranked line,
 * which has no rank to judge by and falls back to the server's `curated` flag.
 */
export const prominentTo = (rankLevel, viewerLevel) =>
  rankLevel === UNRANKED ? null : rankLevel >= viewerLevel;
