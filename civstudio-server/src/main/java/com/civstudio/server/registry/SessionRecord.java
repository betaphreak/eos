package com.civstudio.server.registry;

/**
 * The durable record of one hosted run — a row of {@code game_session}.
 * <p>
 * A live {@link com.civstudio.server.HostedSession} is the run <em>happening</em>; this is the run
 * <em>having happened</em>, and it outlives the process. Without it a redeploy silently erases every
 * run: a ranked player would quietly get a second attempt, and a "save slot" that vanishes is not a
 * save (see {@code docs/spectator-lobby.md} Phase 6).
 * <p>
 * The founding half ({@code scenario}/{@code seed}/{@code provinceId}) is the {@link
 * com.civstudio.server.SessionSpec spec} — enough to rebuild the world, since the engine is
 * seed-reproducible. The rest is progress: where the run got to, and how it ended.
 *
 * @param id         the session id (the {@link com.civstudio.server.SessionHost} key)
 * @param scenario   the founding scenario — also the run's <b>kind</b> (a Timeline is a session
 *                   whose scenario is {@code timeline}, which is why the design sketch's separate
 *                   {@code timeline} table collapsed into this one)
 * @param seed       the reproducibility root
 * @param provinceId the founding province (a Timeline's <b>anchor</b> — its first seat's site)
 * @param owner      the {@code app_user} who owns the run, or {@code null} for a house-owned/public
 *                   one (the demo, and every Timeline — whose seats are owned per colony instead)
 * @param state      the run's last known {@link com.civstudio.server.HostedSession.State}
 * @param endReason  why it ended (a verdict for a Timeline), or {@code null} unless it ended itself
 * @param tick       the last recorded tick — how far the run got
 */
public record SessionRecord(String id, String scenario, long seed, int provinceId, String owner,
		String state, String endReason, long tick) {

	/**
	 * Whether this run <b>ended itself</b> — game over. Such a run is finished for good: it is never
	 * re-founded and never restored, because its outcome is these columns and re-running it would
	 * mean handing its players a second attempt.
	 * <p>
	 * Deliberately <em>not</em> the same question as {@link #isTerminal()}. A {@code STOPPED} run was
	 * stopped from <em>outside</em> — which is what a graceful shutdown does to <b>every</b> session
	 * — so it must come back. Confusing the two would make a redeploy permanently kill everything it
	 * touched, which is the opposite of the point. See {@code docs/game-over.md}.
	 *
	 * @return {@code true} if the run reached its own end
	 */
	public boolean isFinished() {
		return "GAME_OVER".equals(state);
	}

	/**
	 * Whether the run was not ticking when last recorded — finished, or stopped from outside. Useful
	 * for reporting; for "may this run come back?", ask {@link #isFinished()}.
	 *
	 * @return {@code true} if it was not running
	 */
	public boolean isTerminal() {
		return "STOPPED".equals(state) || isFinished();
	}

	/** Whether this record is a ranked Timeline. */
	public boolean isTimeline() {
		return "timeline".equals(scenario);
	}
}
