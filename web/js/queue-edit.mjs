"use strict";
// The build queue's EDIT reducer — a pending list and one verb in, the new list out. Kept in its
// own module (no core.mjs import) so it unit-tests under node, like district-plots.mjs.
//
// Every edit produces a WHOLE list, because that is what the player submits: `queue_build` carries
// {clear: true, items}, replacing the queue in one tick-stamped command. Sending deltas against a
// queue the engine is simultaneously consuming would race; sending the list the player is looking
// at cannot.

/**
 * Apply one queue verb.
 *
 * @param {string[]} pending the queue as the player sees it
 * @param {"up"|"down"|"drop"} act move it sooner, later, or cancel it
 * @param {number} i the index acted on
 * @returns {string[]} a new list (the input is never mutated); unchanged for an out-of-range
 *          index, an unknown verb, or a move off either end
 */
export function reorder(pending, act, i) {
  const out = (pending || []).slice();
  if (!(i >= 0) || i >= out.length) return out;
  if (act === "drop") out.splice(i, 1);
  else if (act === "up" && i > 0) out.splice(i - 1, 0, out.splice(i, 1)[0]);
  else if (act === "down" && i < out.length - 1) out.splice(i + 1, 0, out.splice(i, 1)[0]);
  return out;
}

/**
 * Append newly-decreed ids to the queue, dropping any already ordered — the queue is a plan, and
 * the same building twice in it is a mistake the engine would silently drop at pick time anyway.
 *
 * @param {string[]} pending the current queue
 * @param {string[]} picked the ids just chosen, in pick order
 * @returns {string[]} the new whole list
 */
export function append(pending, picked) {
  const out = (pending || []).slice();
  for (const id of picked || [])
    if (!out.includes(id)) out.push(id);
  return out;
}
