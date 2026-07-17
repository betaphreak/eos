"use strict";
// How a lobby row reads — the pure part of the Spectator Lobby (docs/spectator-lobby.md Phase 5).
//
// The server hands us rows from GET /api/sessions carrying kind / state / date / watching / mine /
// seats / standing / endReason. Turning those into what a human reads is a decision per field, and
// decisions are worth testing without a browser — so they live here, and lobby.mjs only paints.
//
// NAMING IS DEFERRED. A run will eventually be named after its COUNTRY (the political layer already
// has Country / Province.ownerTag); until then a row is labelled by its colony, which is generated,
// meaningful, and needs no new field. When countries land, `title()` is the one place that changes.

/** Kinds the lobby knows. Anything else is shown as-is rather than hidden — an unknown run is still a run. */
export const KIND = { TIMELINE: "timeline", DEMO: "demo", SINGLE: "single-player" };

/**
 * The row's headline. A Timeline is the world's, so it is named as one; every other run is named by
 * the colony you would be watching (see the naming note above).
 */
export function title(row) {
  if (!row) return "";
  if (row.kind === KIND.TIMELINE) return timelineName(row);
  return row.colony || row.id || "";
}

// "Timeline I" reads as a world; "timeline-7654321" reads as a database row. Until the registry
// names them (it will), derive something stable from the seed rather than inventing a fiction.
function timelineName(row) {
  return "Timeline · " + (row.seed ?? "");
}

/**
 * The row's second line: what is happening, in the words that matter for this kind of run.
 * A Timeline's status is its contest; a save slot's is whether it is waiting for you.
 */
export function status(row) {
  if (!row) return "";
  if (row.state === "GAME_OVER") return row.endReason || "over";
  if (row.kind === KIND.TIMELINE) {
    if (row.state === "CREATED") return `open for joins · ${row.seats ?? 0} seated`;
    return `${row.standing ?? 0} of ${row.seats ?? 0} standing · ${row.date || ""}`.trim();
  }
  if (row.state === "CREATED") return "not started";
  if (row.state === "PAUSED") return `paused · ${row.date || ""}`.trim();
  if (row.state === "STOPPED") return `stopped · ${row.date || ""}`.trim();
  return row.date || "";
}

/** Whether this run is finished for good — the row reads as a record, not something to join. */
export const isOver = row => !!row && row.state === "GAME_OVER";

/** Whether the viewer may delete this row: their own single-player runs, and only those. */
export const canDelete = row => !!row && row.kind === KIND.SINGLE && !!row.mine;

/**
 * The Single Player button's state for a viewer.
 * Signed out you cannot found anything; with a full shelf you must finish or delete one first.
 *
 * @param rows      every row the lobby can see
 * @param signedIn  whether the viewer is signed in
 * @param limit     the server's save-slot limit
 */
export function singlePlayer(rows, signedIn, limit = 5) {
  if (!signedIn) return { label: "Single Player", enabled: false, hint: "Sign in to play" };
  const mine = (rows || []).filter(r => r.kind === KIND.SINGLE && r.mine && !isOver(r));
  if (mine.length >= limit)
    return { label: `Single Player (${mine.length}/${limit})`, enabled: false,
      hint: `All ${limit} runs are in play — finish or delete one` };
  return { label: "Single Player", enabled: true,
    hint: `New run · slot ${mine.length + 1} of ${limit}` };
}

/**
 * The Ranked button's face — the four the sketch called for, plus the one reality adds: there is no
 * Timeline to point at until the registry names a current one, and a button that lies about what it
 * will do is worse than a button that says it is not ready.
 *
 * @param rows      every row the lobby can see
 * @param signedIn  whether the viewer is signed in
 */
export function ranked(rows, signedIn) {
  const timeline = (rows || []).find(r => r.kind === KIND.TIMELINE && !isOver(r));
  if (!timeline) return { label: "Ranked", enabled: false, hint: "No Timeline is open" };
  if (!signedIn)
    return { label: "Ranked", enabled: false, hint: "Sign in to take a seat", id: timeline.id };
  if (timeline.state === "CREATED")
    return { label: "Join " + title(timeline), enabled: true, join: true, id: timeline.id,
      hint: status(timeline) };
  // it has started: the roster is closed, so the honest offer is to watch it
  return { label: "Spectate " + title(timeline), enabled: true, id: timeline.id,
    hint: status(timeline) };
}

/**
 * Order the lobby's list: the Timeline first (it is the headline), then live runs, then finished
 * ones — and yours before strangers' within a group, since you came here to find yours.
 */
export function order(rows) {
  const rank = r => (r.kind === KIND.TIMELINE ? 0 : 1) + (isOver(r) ? 10 : 0) - (r.mine ? 0.5 : 0);
  return (rows || []).slice().sort((a, b) => rank(a) - rank(b)
    || String(title(a)).localeCompare(String(title(b))));
}
