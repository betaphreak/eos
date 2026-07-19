package com.civstudio.server;

/**
 * What a hosted run's <b>clock</b> is doing — one of the two axes that replaced the old five-value
 * {@code HostedSession.State} (the other is {@link Outcome}, the contest result). This axis is pure
 * transport: whether and how the session is ticking. See {@code docs/session-management.md}.
 *
 * <ul>
 * <li>{@link #CREATED} — built, thread not started (a Timeline still open for joins).</li>
 * <li>{@link #RUNNING} — ticking freely, paced by the tick rate.</li>
 * <li>{@link #PAUSED} — halted; advances only by {@code step}.</li>
 * <li>{@link #STOPPED} — the thread has exited and the clock will not advance again <em>in this
 *     process</em>. <b>Why</b> it stopped is the {@link Outcome}'s business, not the clock's: an
 *     external stop keeps {@code Outcome.LIVE} (and is restorable — a redeploy brings it back), while a
 *     game-over carries a decided outcome. The client shows the terminal screen and disables play/pause
 *     on {@code STOPPED} either way — a stopped run is not one you can drive.</li>
 * </ul>
 */
public enum ClockState {

	CREATED,
	RUNNING,
	PAUSED,
	STOPPED;

	/** Whether the clock will not advance again in this process (its thread has exited). */
	public boolean isStopped() {
		return this == STOPPED;
	}

	/** Whether play/pause/step apply — only a live clock ({@link #RUNNING} or {@link #PAUSED}). */
	public boolean isControllable() {
		return this == RUNNING || this == PAUSED;
	}
}
