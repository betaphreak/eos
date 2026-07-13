"use strict";
// Advisor portrait resolution from the baked per-culture sheets (web/build-advisors.mjs →
// assets/advisors/portraits.json + <culture>.webp). Lazily loads the manifest once, then resolves a
// person's (race, culture, advisor role, gender) to one cell of a culture sheet — a
// culture → race-representative → fallback-culture chain (docs/privy-council.md §3). Returns a CSS
// style that paints exactly that cell at a requested size; null when no art applies (caller falls
// back to the initials tile).
let manifest = null, loading = null;

function load() {
  if (manifest) return Promise.resolve(manifest);
  if (!loading) loading = fetch("assets/advisors/portraits.json")
    .then(r => (r.ok ? r.json() : null)).then(m => (manifest = m || {})).catch(() => (manifest = {}));
  return loading;
}

/** Warm the manifest (call at init) so the first portrait paints without a fetch stall. */
export function initPortraits() { load(); }

// resolve an engine race + (optional) actual culture to a baked culture that has `role`, or null:
// the person's own culture if it has art, else the race's representative, else the global fallback
function resolveCulture(m, race, culture, role) {
  const has = c => c && m.cultures[c] && m.cultures[c].roles.includes(role);
  if (has(culture)) return culture;
  const mapped = (m.raceCulture || {})[race];
  if (has(mapped)) return mapped;
  if (has(m.fallbackCulture)) return m.fallbackCulture;
  return null;
}

/**
 * Resolve a portrait cell for advisor `advisorId` (or a raw `role`) held by a person of `race` /
 * `culture` / `gender`, at `size` on-screen px. Returns `{ url, culture, style }` or null.
 */
export async function advisorPortrait({ race, culture, advisorId, role, gender }, size) {
  const m = await load();
  if (!m || !m.cultures) return null;
  const artRole = role || (m.advisorRole || {})[advisorId] || advisorId;
  const cul = resolveCulture(m, race, culture, artRole);
  if (!cul) return null;
  const col = m.roles.indexOf(artRole);
  const row = (m.genders || ["male", "female"]).indexOf(gender === "female" ? "female" : "male");
  if (col < 0 || row < 0) return null;
  const url = `assets/advisors/${cul}.webp`;
  const bw = size * m.roles.length, bh = size * m.genders.length;
  const style = `background-image:url(${url});background-size:${bw}px ${bh}px;`
    + `background-position:-${size * col}px -${size * row}px;background-repeat:no-repeat`;
  return { url, culture: cul, style };
}
