package com.civstudio.server.registry;

import com.civstudio.server.Outcome;
import com.civstudio.server.SessionKind;

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
 * seed-reproducible. {@code kind}/{@code mode}/{@code difficulty} are the run's <b>taxonomy</b> (see
 * {@code docs/session-management.md}); {@code clockState}/{@code outcome} are its <b>progress</b> — where
 * it got to and how it ended. The old single {@code state} column is gone from the model; it is derived
 * on the way to the database as a legacy mirror (see {@link #legacyState()}).
 *
 * @param id         the session id (the {@link com.civstudio.server.SessionHost} key)
 * @param scenario   the founding scenario id
 * @param seed       the reproducibility root
 * @param provinceId the founding province (a Timeline's <b>anchor</b> — its first seat's site)
 * @param owner      the {@code app_user} who owns the run, or {@code null} for a house-owned/public one
 * @param kind       the run's {@link SessionKind} (its name), the visibility/auth category
 * @param mode       the mode variant within the kind (open set), or {@code null} for the default
 * @param difficulty the Civ4 handicap key, or {@code null} for the default
 * @param clockState the run's last known {@link com.civstudio.server.ClockState} (its name)
 * @param outcome    the run's {@link Outcome} (its name) — {@code LIVE} until it ends itself
 * @param endReason  why it ended (a verdict for a Timeline), or {@code null} unless it ended itself
 * @param tick       the last recorded tick — how far the run got
 * @param contentVersion the content-store version the run was founded against, or {@code null} when
 *                   the source carried none (the classpath default). Reproducibility is
 *                   {@code seed + contentVersion + command log}: the seed fixes the draws, but the
 *                   world — and, once balance data rides the bundle, the numbers it was tuned with —
 *                   comes from studio and changes independently of the code. Without this column a
 *                   re-run cannot even be shown to be comparable. Legacy rows read {@code null},
 *                   which means "unknown", not "the current one"
 */
public record SessionRecord(String id, String scenario, long seed, int provinceId, String owner,
		String kind, String mode, String difficulty, String clockState, String outcome,
		String endReason, long tick, String contentVersion) {

	/**
	 * Whether this run <b>ended itself</b> — its {@link Outcome} is decided (won / lost / abandoned).
	 * Such a run is finished for good: never re-founded, never restored, because its outcome is these
	 * columns and re-running it would hand its players a second attempt.
	 * <p>
	 * Deliberately <em>not</em> the same question as "was it stopped": a run {@code STOPPED} from
	 * outside stays {@code LIVE} — which is what a graceful shutdown does to <b>every</b> session — so
	 * it must come back. Confusing the two would make a redeploy permanently kill everything it touched,
	 * the opposite of the point. See {@code docs/game-over.md}.
	 *
	 * @return {@code true} if the run reached its own end
	 */
	public boolean isFinished() {
		return Outcome.fromRecord(outcome).isDecided();
	}

	/** Whether this record is a ranked Timeline. */
	public boolean isTimeline() {
		return kindEnum() == SessionKind.TIMELINE;
	}

	/** The run's kind, resolved tolerantly (legacy rows fall back to a scenario/owner derivation). */
	public SessionKind kindEnum() {
		return SessionKind.fromRecord(kind, scenario, owner);
	}

	/**
	 * The legacy five-value {@code state} string this run would have had — {@code GAME_OVER} if
	 * finished, else the clock state. Written to the (retained) {@code state} column as a mirror so a
	 * rollback to pre-split code still reads the table, and used for display where a single word is
	 * wanted (e.g. the {@code RunFinishedException} message).
	 */
	public String legacyState() {
		if (isFinished())
			return "GAME_OVER";
		return clockState == null || clockState.isBlank() ? "CREATED" : clockState;
	}
}
