// The city screen's per-hamlet FOOD status (city-of-hamlets V2/V3): a village's shared larder — the
// pool its households eat from — read against the level its lord holds it at. A village below that
// floor is going hungry, which is otherwise invisible until its people start dying, so the chip
// calls it out rather than only reporting a number.
//
// Kept out of city-screen.mjs (which reaches for the live session and the DOM) so the rule is a pure
// function over one snapshot district and can be unit tested on its own: node --test web/js.

// a food quantity as the screen shows it: whole units once it is a real store, one decimal while a
// village is running on fumes (where the difference between 0.4 and 4 is the difference between
// starving tonight and not)
const fmt = n => (n >= 100 ? String(Math.round(n)) : String(Math.round(n * 10) / 10));

/**
 * Whether a village is FED — its larder is at or above the floor its lord provisions it to. A
 * village with no floor (no residents to feed) is trivially fed.
 *
 * @param {{larder?: number, larderFloor?: number}} d a snapshot district
 * @returns {boolean}
 */
export function isFed(d) {
  const floor = Math.max(0, (d && d.larderFloor) || 0);
  return !(floor > 0) || Math.max(0, (d && d.larder) || 0) >= floor;
}

/**
 * The larder chip for one plot's head row, or "" when the plot is no hamlet or carries no larder
 * (a colony not running village larders, or the city center).
 *
 * @param {{larder?: number, larderFloor?: number}} d a snapshot district
 * @param {boolean} isHamlet whether this plot is a peopled, non-center plot
 * @returns {string} HTML for the chip, or "" for nothing to show
 */
export function larderChip(d, isHamlet) {
  const stock = Math.max(0, (d && d.larder) || 0);
  const floor = Math.max(0, (d && d.larderFloor) || 0);
  if (!isHamlet || (stock <= 0 && floor <= 0)) return "";
  const fed = isFed(d);
  const title = `village larder: ${fmt(stock)} of ${fmt(floor)} — ${fed ? "fed" : "going hungry"}`;
  return `<span class="city-badge city-larder${fed ? "" : " hungry"}" title="${title}">🍞${
    fmt(stock)}</span>`;
}

/**
 * The chip for the necessity farms working this village's fields (city-of-hamlets V3) — its own
 * food engine. A hamlet with farms exports its surplus to the shared market; one without lives off
 * its households' home plots and whatever its lord imports.
 *
 * @param {{farms?: number}} d a snapshot district
 * @returns {string} HTML for the chip, or "" when no farm works for this village
 */
export function farmChip(d) {
  const farms = (d && d.farms) || 0;
  if (farms <= 0) return "";
  const s = farms === 1 ? "" : "s";
  return `<span class="city-badge" title="${farms} necessity farm${s} work${
    farms === 1 ? "s" : ""} this village's fields">🌾${farms}</span>`;
}
