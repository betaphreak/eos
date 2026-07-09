package com.civstudio.server.command;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A session's ordered, tick-stamped input log — the Factorio input sequence and the
 * session's <b>savegame</b> (see {@code docs/client-server.md}). Clients {@link #append}
 * commands (from an HTTP thread); the host {@linkplain #drainDueBy(long) drains} the ones
 * due at the top of each tick (on the session thread). The applied {@link #history()} is
 * the replay format: re-running the session's spec while applying this history reproduces
 * its state exactly.
 * <p>
 * Empty during Phase-A spectator play; the seam is real so nothing is thrown away when
 * interactive commands arrive.
 */
public final class CommandLog {

	// commands submitted but not yet due/applied; guarded by `this`. Kept small (a handful
	// of in-flight inputs), re-sorted on each drain so late arrivals settle into tick order.
	private final List<GameCommand> pending = new ArrayList<>();

	// the applied commands, in the order they were applied — the ordered replay log
	private final List<GameCommand> history = new CopyOnWriteArrayList<>();

	/**
	 * Submit a command. Thread-safe (called from a client/HTTP thread); it takes effect at
	 * the top of its {@link GameCommand#tick() tick}, or the next drained tick if that tick
	 * has already passed.
	 *
	 * @param command the command to enqueue
	 */
	public synchronized void append(GameCommand command) {
		pending.add(command);
	}

	/**
	 * Remove and return every pending command due at or before {@code tick}, in tick order
	 * (ties in submission order). Called once per tick on the session thread; the returned
	 * commands are recorded in {@link #history()} in the order returned.
	 *
	 * @param tick the tick being entered
	 * @return the commands to apply this tick (empty if none are due)
	 */
	public synchronized List<GameCommand> drainDueBy(long tick) {
		if (pending.isEmpty())
			return List.of();
		// stable sort by tick preserves submission order among same-tick commands
		pending.sort(Comparator.comparingLong(GameCommand::tick));
		List<GameCommand> due = new ArrayList<>();
		Iterator<GameCommand> it = pending.iterator();
		while (it.hasNext()) {
			GameCommand c = it.next();
			if (c.tick() <= tick) {
				due.add(c);
				it.remove();
			} else {
				break; // sorted: nothing after this is due
			}
		}
		history.addAll(due);
		return due;
	}

	/** The ordered log of applied commands — the session's replay/savegame format. */
	public List<GameCommand> history() {
		return List.copyOf(history);
	}

	/** Number of submitted-but-not-yet-applied commands. */
	public synchronized int pendingCount() {
		return pending.size();
	}
}
