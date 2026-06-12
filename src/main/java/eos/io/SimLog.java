package eos.io;

import java.util.logging.ConsoleHandler;
import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import eos.settlement.Settlement;

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
 * Logging is process-global, so the date prefix is taken from the colony
 * passed to the most recent {@link #init(Settlement)} call.
 */
public final class SimLog {

	private static boolean initialized = false;

	// colony whose in-game date prefixes each log record
	private static Settlement colony;

	private SimLog() {
	}

	/**
	 * Route all logging through a handler that prefixes each record with the
	 * in-game date of <tt>colony</tt>. The handler is installed once; repeat
	 * calls only update which colony supplies the date.
	 *
	 * @param colony
	 *            the colony whose in-game date prefixes each log record
	 */
	public static synchronized void init(Settlement colony) {
		SimLog.colony = colony;
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

	/** Prefixes each message with the in-game date and a level label. */
	private static final class DateFormatter extends Formatter {
		@Override
		public String format(LogRecord record) {
			String level = record.getLevel() == Level.WARNING ? "WARN"
					: record.getLevel().getName();
			return colony.getDate() + ": " + level + " "
					+ formatMessage(record) + System.lineSeparator();
		}
	}
}
