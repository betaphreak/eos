// Deep-link query-string arithmetic — dependency-free on purpose, so it is unit-testable in node
// (importing rail.mjs pulls in modules that read `window.BUNDLE` at module scope).
//
// The viewer's deep-link contract is `?p=<provinceId>` (+ optional `?z=`, `?realm=`); see
// web/js/main.mjs readDeepLink. docs/studio-control-plane-plan.md §D3.

/**
 * `href` with `?p=` set to the selected province — or removed when nothing is selected — leaving
 * every other parameter and the hash untouched.
 *
 * `z` is deliberately not written: zoom changes continuously, so capturing whatever it happened to
 * be at click time would be arbitrary. Without it a reload frames the whole province
 * (focusProvinceFit), which is the useful landing for "look at this one".
 *
 * @param {string} href the current URL
 * @param {{id?: number|string}|null|undefined} p the selected province, or null/undefined for none
 * @returns {string} the URL to replace the current one with
 */
export function selectionUrl(href, p) {
  const u = new URL(href);
  if (p && p.id != null) u.searchParams.set("p", p.id);
  else u.searchParams.delete("p");
  return u.href;
}
