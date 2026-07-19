"use strict";
// Lazy per-province route-layer fetching for Live mode (docs/route-rendering.md §Viewport-windowed
// route persistence). Mirrors plotfetch.mjs: fetch GET /api/sessions/{sid}/routes/{id} for the
// provinces in view, fold each province's WHOLE standing layer into the global route-index (so the
// draw layer fuses roads across province seams), and refetch only the provinces the snapshot flags in
// routeDirty. Read-only; with no session it is a no-op — WorldMap mode draws only the urban stand-in.
//
// Because the server serves the whole standing layer of a province (not a per-band window), a
// late-joining or reloading client gets the full network on viewport entry, not just recently-crossed
// plots — the failure the old per-band snapshot broadcast had.
import { apiUrl } from "./core.mjs";
import { draw } from "./repaint.mjs";
import { applyProvince, clearRoutes } from "./route-index.mjs";

const ROUTE_FETCH_TIMEOUT = 6000;    // ms — drop a route fetch that takes longer than this
const ROUTE_RETRY_BACKOFF = 12000;   // ms — after a timeout, wait this long before retrying a province
const MAX_INFLIGHT_ROUTES = 6;       // most concurrent route fetches (matches plotfetch's cap)
let inFlight = 0;

let sid = null;
// per-province fetch bookkeeping: id -> { rev, loaded, pending, stale, retryAt }
const provState = new Map();

/** Point the route feed at a session, or `null` to leave Live. On a change the index is dropped so a
 *  new session never shows the old one's roads. Called by the live overlay each snapshot. */
export function setRouteSession(next) {
  if (next === sid) return;
  sid = next;
  provState.clear();
  clearRoutes();
  draw();
}

/** Mark the provinces whose route layer changed (the snapshot's `routeDirty`) so the next draw
 *  refetches them. A loaded province is flagged stale; an unloaded one refetches on sight anyway. */
export function invalidateRoutes(ids) {
  if (!ids || !ids.length) return;
  for (const id of ids) {
    const st = provState.get(id);
    if (st) st.stale = true;
  }
}

/** Ensure a province's route layer is loaded (fetch if missing or stale). Called by the draw layer
 *  for each on-screen province, so fetching is bounded to the viewport AND the draw zoom band (the
 *  draw layer returns early below it). No session ⇒ no-op. */
export function ensureProvinceRoutes(p) {
  if (!sid) return;
  const st = provState.get(p.id);
  if (st) {
    if (st.pending) return;
    if (!st.stale && st.loaded) return;
    if (st.retryAt && performance.now() < st.retryAt) return;
  }
  if (inFlight >= MAX_INFLIGHT_ROUTES) return;   // at capacity — the next frame retries
  fetchRoutes(p);
}

async function fetchRoutes(p) {
  let st = provState.get(p.id);
  if (!st) { st = {}; provState.set(p.id, st); }
  st.pending = true;
  st.stale = false;
  inFlight++;
  const fetchSid = sid;   // capture — a session switch mid-flight discards the result
  const ctl = new AbortController();
  const timer = setTimeout(() => ctl.abort(), ROUTE_FETCH_TIMEOUT);
  try {
    const res = await fetch(apiUrl("/api/sessions/" + fetchSid + "/routes/" + p.id), { signal: ctl.signal });
    if (!res.ok) throw new Error("routes " + res.status);
    const body = await res.json();
    if (fetchSid !== sid) return;   // session switched while in flight — drop the result
    // dedup: the same rev we already hold means the layer did not move — no rebuild, no repaint
    if (st.loaded && st.rev === body.rev) { st.retryAt = 0; return; }
    const changed = applyProvince(p.id, body.plots);
    st.rev = body.rev;
    st.loaded = true;
    st.retryAt = 0;
    if (changed) draw();
  } catch (e) {
    if (e.name === "AbortError")
      // too slow — drop it (keeping pan/zoom smooth), retryable after a backoff
      st.retryAt = performance.now() + ROUTE_RETRY_BACKOFF;
    else {
      // a real error (5xx, parse) — treat as an empty layer so the draw loop stops hammering; a later
      // routeDirty for this province re-marks it stale and refetches. One province failing is not a
      // dead session (mirrors plotfetch's contract).
      applyProvince(p.id, []);
      st.loaded = true;
      st.rev = 0;
    }
  } finally {
    clearTimeout(timer);
    inFlight--;
    st.pending = false;
  }
}
