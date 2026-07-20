// Links from the CMS into the world map viewer (docs/studio-control-plane-plan.md §D1).
//
// The viewer is a separate static origin (Azure SWA) that reads its own data from the game server,
// so this is a plain deep link — no embedding, and therefore no CSP `frame-src` change. The viewer
// already understands ?realm= and ?p= (web/js/main.mjs readDeepLink), including the cross-realm case:
// landing on a province in another realm switches realm first.

import { serverBase } from './serverApi';

// Configurable at build time (env VITE_CIVSTUDIO_WORLDMAP); defaults to the live viewer.
const RAW = (import.meta as any)?.env?.VITE_CIVSTUDIO_WORLDMAP as string | undefined;
export const worldMapBase = (RAW || 'https://anbennar.civstudio.com').replace(/\/+$/, '');

/**
 * A viewer URL, always naming the game server via `?live=`.
 *
 * Without `live` the viewer opens its "Choose a server" splash and waits — fine when a person
 * navigates to it cold, useless in an admin panel, where the answer is never in doubt: the map
 * should show the server this admin manages. Pointing it at the same {@link serverBase} the ops
 * widgets call keeps the whole admin talking about one server.
 */
function viewerUrl(params: Record<string, string | number | null | undefined>,
    opts: { embedded?: boolean } = {}): string {
  const q = new URLSearchParams();
  for (const [k, v] of Object.entries(params)) if (v != null && v !== '') q.set(k, String(v));
  q.set('live', serverBase);
  // Embedded, the viewer's Spectator Lobby is not a choosing — it is a modal in someone else's
  // panel, over the map you asked for. ?lobby=0 opts out (web/index.html openLobbyDuringLoad).
  // A link that opens in its own tab keeps the lobby: there it IS the front door.
  if (opts.embedded) q.set('lobby', '0');
  return `${worldMapBase}/?${q.toString()}`;
}

/** The map with nothing selected — the whole world (or a realm). */
export function worldMapUrl(realm?: string | null, opts?: { embedded?: boolean }): string {
  return viewerUrl({ realm }, opts);
}

/**
 * Deep link to a province on the map.
 *
 * Omitting `z` is deliberate: without a zoom the viewer frames the whole province by its bounding
 * box (focusProvinceFit), which is what "show me this province" should mean. A fixed zoom would be
 * wrong for both a one-plot island and a sprawling steppe.
 *
 * @param provinceId the numeric exporter id — the same value the viewer keys provinces by
 * @param realm      the province's realm; the viewer falls back to Halcann when absent
 */
export function provinceMapUrl(provinceId: number | string, realm?: string | null,
    opts?: { embedded?: boolean }): string {
  return viewerUrl({ realm, p: provinceId }, opts);
}
