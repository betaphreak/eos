package com.civstudio.server.command;

import com.civstudio.server.HostedSession;

/**
 * A player input against a hosted session — the Factorio-style spine (see {@code
 * docs/client-server.md}). A command is <b>tick-stamped</b> (it executes on a specific
 * authoritative tick) and applied at the single deterministic point at the <em>top of that
 * tick</em>, before any colony acts — so a session's whole run stays a pure function of its
 * spec and the ordered command log, and two replays of the same log produce identical state
 * regardless of wall-clock pacing.
 * <p>
 * During Phase A (spectator) the log is empty and no commands flow; the interface exists so
 * the transport, host loop and replay format are already command-shaped when interactive
 * play (Phase B) lands.
 */
public interface GameCommand {

	/**
	 * The authoritative tick this command executes on. The host applies it at the top of
	 * that tick (or the next tick it drains, if it was submitted late — see {@link
	 * CommandLog#drainDueBy(long)}).
	 *
	 * @return the execution tick
	 */
	long tick();

	/**
	 * Apply this command to the session, mutating sim state deterministically. Runs on the
	 * session thread at the top of {@link #tick()}, with every colony paused between days,
	 * so it may touch colony/agent state directly.
	 *
	 * @param session the session to act on
	 */
	void apply(HostedSession session);
}
