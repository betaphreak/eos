package eos.io;

import java.util.logging.ConsoleHandler;
import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import eos.economy.Economy;

/**
 * Configures java.util.logging so every record is prefixed with the current
 * in-game date ({@link Economy#getDate()}). Call {@link #init(Economy)} once at
 * the start of a simulation's <tt>main</tt> (after the economy is created),
 * before {@link Economy#run(int)}.
 * <p>
 * Agents and markets log via Lombok <tt>@Log</tt> (a java.util.logging
 * {@link Logger}): use <tt>log.info(...)</tt> for events (e.g. an agent dying)
 * and <tt>log.warning(...)</tt> for anomalies (e.g. a price skyrocketing).
 * Output goes to stderr and is flushed per record, so it appears immediately.
 * <p>
 * Logging is process-global, so the date prefix is taken from the economy
 * passed to the most recent {@link #init(Economy)} call.
 */
public final class SimLog {

	private static boolean initialized = false;

	// economy whose in-game date prefixes each log record
	private static Economy economy;

	private SimLog() {
	}

	/**
	 * Route all logging through a handler that prefixes each record with the
	 * in-game date of <tt>economy</tt>. The handler is installed once; repeat
	 * calls only update which economy supplies the date.
	 *
	 * @param economy
	 *            the economy whose in-game date prefixes each log record
	 */
	public static synchronized void init(Economy economy) {
		SimLog.economy = economy;
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
			return economy.getDate() + ": " + level + " "
					+ formatMessage(record) + System.lineSeparator();
		}
	}
}
