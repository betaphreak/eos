package com.civstudio.server.render;

import java.util.List;

/**
 * A full render snapshot of a hosted session at one tick — the projection streamed to a
 * (thin) browser client over SSE (see {@code docs/client-server.md}, Phase A). It is a
 * <em>projection</em>, deliberately not a resume-capable save: the savegame is the {@link
 * com.civstudio.server.SessionSpec spec} + command log, replayed deterministically. This
 * carries only what a spectator renders — the session's identity and control state, its
 * colonies, and its marching caravans.
 *
 * @param sessionId  the session id (see {@link com.civstudio.server.SessionSpec#id()})
 * @param seed       the session seed
 * @param scenario   the founding scenario id
 * @param clockState what the clock is doing (CREATED / RUNNING / PAUSED / STOPPED) — the transport axis
 * @param outcome    the contest result (LIVE / WON / LOST / ABANDONED). Split out of the old single
 *                   {@code state} (see {@code docs/session-management.md}). The client shows its terminal
 *                   screen and disables play/pause on {@code clockState == STOPPED}; it stops
 *                   reconnecting only once {@code outcome != LIVE} (a decided run never ticks again),
 *                   and keeps trying for a {@code LIVE} stop so a restored run re-attaches
 * @param endReason  why the run ended, as display text for a client's terminal screen, or {@code null}
 *                   unless it ended itself; see {@code docs/game-over.md}
 * @param tick       the authoritative tick (in-game days elapsed)
 * @param date       the session's current in-game date (ISO-8601), or empty if unknown
 * @param colonies   the colonies' projections
 * @param caravans   the wandering bands' projections
 * @param log        the event-log lines emitted since the previous frame (the live log bar's feed)
 * @param routeDirty the ids of provinces whose route layer changed since the previous frame — the
 *                   signal a client uses to refetch only its in-view provinces from {@code
 *                   GET /api/sessions/{id}/routes/{provinceId}}. Bounded (bands touch a handful of
 *                   provinces per day); a dropped frame just delays a refetch (self-heals). See
 *                   {@code docs/route-rendering.md} §Viewport-windowed route persistence
 */
public record SessionSnapshot(String sessionId, long seed, String scenario, String clockState,
		String outcome, String endReason, long tick, String date, List<ColonyView> colonies,
		List<CaravanView> caravans, List<LogLine> log, List<Integer> routeDirty,
		boolean awaitingBuildChoice, List<String> buildCandidates) {

	/**
	 * This frame with its two <b>per-frame deltas</b> — {@link #log()} and {@link #routeDirty()} —
	 * emptied, leaving the full state fields untouched.
	 * <p>
	 * Deltas are only meaningful to a client that saw the previous frame. Handing them to someone who
	 * did not is not merely useless, it is <em>wrong</em>: a late joiner replays log lines it never
	 * missed, and a stopped session — whose cached frame never changes again — replays the same ones
	 * on every reconnect, forever. That is exactly the bug {@code web/js/snapshot-dedupe.mjs} was
	 * added to paper over client-side; this is the fix at the source, so every client (the browser,
	 * the MCP tools, the admin panel) inherits it instead of re-implementing a tick gate.
	 * <p>
	 * Both fields are correct as empty for a joiner: history comes from {@code GET /events}, and a
	 * client with no cached route layers has nothing to invalidate.
	 *
	 * @return a delta-free copy safe to hand anyone, at any time
	 */
	public SessionSnapshot withoutDeltas() {
		if (log.isEmpty() && routeDirty.isEmpty())
			return this;
		// awaitingBuildChoice/buildCandidates are FULL STATE (not deltas): a late joiner
		// must still see the pause-and-choose modal, so they survive the stripping
		return new SessionSnapshot(sessionId, seed, scenario, clockState, outcome, endReason, tick,
				date, colonies, caravans, List.of(), List.of(), awaitingBuildChoice, buildCandidates);
	}
}
