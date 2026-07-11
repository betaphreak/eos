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
 * @param sessionId the session id (see {@link com.civstudio.server.SessionSpec#id()})
 * @param seed      the session seed
 * @param scenario  the founding scenario id
 * @param state     the host's control state (RUNNING / PAUSED / STOPPED …)
 * @param tick      the authoritative tick (in-game days elapsed)
 * @param date      the session's current in-game date (ISO-8601), or empty if unknown
 * @param colonies  the colonies' projections
 * @param caravans  the wandering bands' projections
 * @param log       the event-log lines emitted since the previous frame (the live log bar's feed)
 */
public record SessionSnapshot(String sessionId, long seed, String scenario, String state,
		long tick, String date, List<ColonyView> colonies, List<CaravanView> caravans,
		List<LogLine> log) {
}
