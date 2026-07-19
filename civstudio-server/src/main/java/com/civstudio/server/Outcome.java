package com.civstudio.server;

/**
 * The <b>contest result</b> of a hosted run — the second of the two axes that replaced the old
 * five-value {@code HostedSession.State} (the other is {@link ClockState}). This axis is about how the
 * run <em>ended</em>, and is meaningful once it has; while a run is going (or merely stopped from
 * outside) it is {@link #LIVE}. See {@code docs/session-management.md}.
 *
 * <ul>
 * <li>{@link #LIVE} — not decided: the run is going, or was stopped from outside (an admin, a
 *     redeploy) and may resume. A {@code LIVE} run is <b>restorable</b>; the registry will bring it
 *     back.</li>
 * <li>{@link #WON} — a Timeline decided in the last colony's favour.</li>
 * <li>{@link #LOST} — the colony died outright (no survivors took to the road).</li>
 * <li>{@link #ABANDONED} — the survivors abandoned the settlement, departing as a band.</li>
 * </ul>
 *
 * <p><b>{@code isDecided()} is the "finished" question.</b> A decided outcome never ticks again, holds
 * no save slot, and is never restored — its outcome <em>is</em> its record. That is deliberately not
 * the same as {@link ClockState#STOPPED}: a run stopped from outside is {@code STOPPED} but still
 * {@code LIVE}, so it must come back (that is what a graceful shutdown does to every session).
 */
public enum Outcome {

	LIVE,
	WON,
	LOST,
	ABANDONED;

	/** Whether the run has reached its own end — anything but {@link #LIVE}. The "finished" question. */
	public boolean isDecided() {
		return this != LIVE;
	}

	/**
	 * The outcome for a persisted {@code outcome} string, tolerant of legacy rows: an unknown or absent
	 * value is treated as {@link #LIVE} (undecided), which is the safe reading — the worst it does is let
	 * a restore be attempted.
	 *
	 * @param outcome the stored outcome, or {@code null}
	 * @return the resolved outcome
	 */
	public static Outcome fromRecord(String outcome) {
		if (outcome == null || outcome.isBlank())
			return LIVE;
		try {
			return valueOf(outcome);
		} catch (IllegalArgumentException ignored) {
			return LIVE;
		}
	}
}
