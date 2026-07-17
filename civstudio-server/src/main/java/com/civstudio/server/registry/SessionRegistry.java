package com.civstudio.server.registry;

import java.util.List;
import java.util.Optional;

/**
 * Where runs and their seats are remembered — the durable half of the {@link
 * com.civstudio.server.SessionHost}, which itself only holds what is live in this process.
 * <p>
 * Follows the same opt-in shape as {@code CommandStore} / {@code ChatStore} / {@code UserStore}: an
 * {@link InMemorySessionRegistry} by default, a {@link JdbcSessionRegistry} once a datasource is
 * configured (see {@code PersistenceConfig}). The in-memory one is not a stub — it is the correct
 * implementation for a run that is not meant to outlive the process (a test, a local dev server).
 * <p>
 * <b>What this buys</b> ({@code docs/spectator-lobby.md} Phase 6): a redeploy stops erasing runs.
 * Ranked cannot open to the public without it — an in-memory registry hands out a fresh attempt
 * every deploy, and "one shot" is the only thing that makes a ranked run mean anything.
 */
public interface SessionRegistry {

	/**
	 * Remember a run, or update what is remembered of it (upsert by {@link SessionRecord#id()}).
	 *
	 * @param record the run
	 */
	void save(SessionRecord record);

	/**
	 * Record how far a run got and how it ended — its progress, not its founding.
	 *
	 * @param id        the session id (a no-op if it is not recorded)
	 * @param state     its {@link com.civstudio.server.HostedSession.State}
	 * @param endReason why it ended, or {@code null} if it has not ended itself
	 * @param tick      the tick reached
	 */
	void updateProgress(String id, String state, String endReason, long tick);

	/**
	 * The run recorded under this id.
	 *
	 * @param id the session id
	 * @return the record, or empty if none
	 */
	Optional<SessionRecord> find(String id);

	/**
	 * Every remembered run, oldest first.
	 *
	 * @return the records
	 */
	List<SessionRecord> all();

	/**
	 * Take a seat, durably. <b>One per player per run</b> — this is where that rule is actually
	 * enforced, so it survives a restart and a race rather than resting on a live map's memory.
	 *
	 * @param seat the seat
	 * @throws SeatTakenException if this player already holds a seat in this run
	 */
	void seat(SeatRecord seat) throws SeatTakenException;

	/**
	 * The seats of a run, in the order they were taken — the join order a rebuild must replay to
	 * re-found the same colonies in the same places.
	 *
	 * @param sessionId the run
	 * @return its seats, oldest first (empty if none)
	 */
	List<SeatRecord> seats(String sessionId);

	/**
	 * The seat this player holds in this run.
	 *
	 * @param sessionId the run
	 * @param userId    the player
	 * @return their seat, or empty if they hold none
	 */
	Optional<SeatRecord> seatOf(String sessionId, String userId);

	/**
	 * Forget a run and its seats entirely, as though it had never been played.
	 * <p>
	 * <b>Deliberately hard to reach for.</b> This is how a finished run's verdict is destroyed, so it
	 * is for disposable fixtures only — the demo, which is a shop window and not a record of anyone's
	 * play. Forgetting a ranked Timeline would silently grant every one of its players another
	 * attempt, which is the thing this whole registry exists to prevent.
	 *
	 * @param id the run to forget (a no-op if nothing is recorded under it)
	 */
	void forget(String id);

	/** Thrown when a player who already holds a seat in a run tries to take another. */
	class SeatTakenException extends RuntimeException {
		private static final long serialVersionUID = 1L;

		public SeatTakenException(String message) {
			super(message);
		}
	}
}
