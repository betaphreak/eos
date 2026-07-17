"use strict";
// Which snapshot frames' LOG lines are new (overlays/live.mjs).
//
// A snapshot's `log` is a per-tick DELTA — drained once into the frame it rides on. But the server
// hands a late joiner (and every RECONNECT) the CACHED last snapshot, delta and all. So a client
// that re-subscribes replays that frame's lines as if they had just happened, posting them to the
// notification board a second time. Measured against production (2026-07-17): a STOPPED session's
// cached final frame carries the one-line delta "Dhenijansar departed as a Caravan …", and every
// subscribe re-delivered it — the session emits nothing while stopped, the SSE connection goes
// idle, the proxy drops it, the client reconnects, and the card is posted again. Forever.
//
// The rule: a frame's log is new only if its tick is beyond the last tick we ingested. The frame is
// still rendered (it IS the current state) — only its lines are held back. Ticks are monotonic
// within a session, so this needs no memory of the lines themselves.
//
// Kept pure and separate (no core.mjs import) so it unit-tests under node. See docs/game-over.md.

/** Track which frames' log deltas have been ingested, per session. */
export function makeLogGate() {
  let lastTick = -1;
  let sid = null;
  return {
    /**
     * Should this frame's `log` be ingested? True once per tick, in tick order.
     * A frame from a DIFFERENT session starts a fresh gate — notifications are per session, and a
     * new session's ticks restart at 0 (they must not be swallowed as "already seen").
     */
    accept(sessionId, tick) {
      if (sessionId !== sid) { sid = sessionId; lastTick = -1; }
      if (!Number.isFinite(tick)) return true;   // no tick to judge by — don't swallow the lines
      if (tick <= lastTick) return false;        // a replay of the cached frame; already posted
      lastTick = tick;
      return true;
    },
    /** Forget what we've seen (leaving live mode / switching session). */
    reset() { lastTick = -1; sid = null; },
  };
}
