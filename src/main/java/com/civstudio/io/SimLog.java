package com.civstudio.io;

import java.util.logging.ConsoleHandler;
import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import com.civstudio.settlement.Settlement;

/**
 * Configures java.util.logging so every record is prefixed with the current
 * in-game date ({@link Settlement#getDate()}). Call {@link #init(Settlement)} once at
 * the start of a simulation's <tt>main</tt> (after the colony is created),
 * before {@link Settlement#run(int)}.
 * <p>
 * Agents and markets log via Lombok <tt>@Log</tt> (a java.util.logging
 * {@link Logger}): use <tt>log.info(...)</tt> for events (e.g. an agent dying)
 * and <tt>log.warning(...)</tt> for anomalies (e.g. a price skyrocketing).
 * Output goes to stderr and is flushed per record, so it appears immediately.
 * <p>
 * The handler is process-global (installed once), but the colony whose in-game
 * date prefixes a record is held <b>per thread</b>: a record is prefixed with
 * the date of the colony {@linkplain #bind(Settlement) bound} on the thread that
 * emitted it. So when several colonies run concurrently — one thread each — each
 * thread's records carry its own colony's date, with no cross-talk.
 */
public final class SimLog {

	private static boolean initialized = false;

	// the colony whose in-game date prefixes each log record, per emitting thread.
	// per-thread so concurrent colonies (a thread each) don't overwrite a single
	// shared reference and prefix each other's records with the wrong date.
	private static final ThreadLocal<Settlement> CURRENT = new ThreadLocal<>();

	private SimLog() {
	}

	/**
	 * Install the date-prefixing log handler (once per process) and bind
	 * <tt>colony</tt> to the calling thread. Call once at the start of a
	 * simulation's <tt>main</tt>; a worker thread that did not call this should
	 * {@link #bind(Settlement)} its colony instead (the handler is already
	 * installed).
	 *
	 * @param colony
	 *            the colony whose in-game date prefixes records on this thread
	 */
	public static synchronized void init(Settlement colony) {
		bind(colony);
		if (initialized)
			return;
		Logger root = Logger.getLogger("");
		for (Handler h : root.getHandlers())
			root.removeHandler(h);
		ConsoleHandler handler = new ConsoleHandler();
		handler.setLevel(Level.ALL);
		handler.setFormatter(new DateFormatter());
		root.addHandler(handler);
		root.setLevel(Level.INFO);
		initialized = true;
	}

	/**
	 * Bind <tt>colony</tt> as the source of the in-game date for log records
	 * emitted on the calling thread. Used by each colony's worker thread when the
	 * session runs colonies concurrently (the handler is installed separately by
	 * {@link #init(Settlement)}).
	 *
	 * @param colony
	 *            the colony whose in-game date prefixes records on this thread
	 */
	public static void bind(Settlement colony) {
		CURRENT.set(colony);
	}

	/** Prefixes each message with the in-game date and a level label. */
	private static final class DateFormatter extends Formatter {
		@Override
		public String format(LogRecord record) {
			String level = record.getLevel() == Level.WARNING ? "WARN"
					: record.getLevel().getName();
			Settlement colony = CURRENT.get();
			// a record from a thread that never bound a colony (defensive): emit it
			// without a date rather than NPE
			String date = (colony == null) ? "----------" : colony.getDate().toString();
			return date + ": " + level + " "
					+ formatMessage(record) + System.lineSeparator();
		}
	}
}
