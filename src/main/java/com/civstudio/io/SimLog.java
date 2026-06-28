package com.civstudio.io;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Locale;
import java.util.logging.ConsoleHandler;
import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.StreamHandler;

import com.civstudio.settlement.GameSession;
import com.civstudio.settlement.Settlement;

/**
 * Configures java.util.logging so every record is prefixed with the <b>colony</b>
 * that emitted it and the current in-game date ({@link Settlement#getDate()}) —
 * e.g. {@code Withacen 1452-03-14: WARN …}. Call {@link #init(Settlement)} once at
 * the start of a simulation's <tt>main</tt> (after the colony is created), before
 * {@link Settlement#run(int)}.
 * <p>
 * Agents and markets log via Lombok <tt>@Log</tt> (a java.util.logging
 * {@link Logger}), at a <b>level by frequency</b> so a many-colony run can be turned
 * down to a readable volume:
 * <ul>
 * <li><tt>log.warning(...)</tt> — anomalies (a price skyrocketing, a non-converging
 * clear); always shown (file + stderr).</li>
 * <li><tt>log.info(...)</tt> — colony lifecycle (founded/died/dissolved) and the
 * once-a-year {@link Settlement} digest; the default file floor.</li>
 * <li><tt>log.fine(...)</tt> — the dynasty/demographic narrative (ennoblement,
 * notable arrivals, persons-of-interest deaths).</li>
 * <li><tt>log.finer(...)</tt> — the economic churn (firm charter/dissolve, peasant
 * starvation).</li>
 * </ul>
 * The floor is set by the <tt>-Deos.log.level</tt> system property (default
 * {@code INFO}); raise it to {@code FINE} or {@code FINER}/{@code ALL} to add the
 * narrative or the churn when debugging one run.
 * <p>
 * <b>Where records go.</b> Two handlers are installed once per process: a
 * <b>file</b> handler that captures <em>every</em> record at or above the configured
 * level to a single per-run log, and a <b>console</b> handler that mirrors only
 * <b>warnings and anomalies</b> (WARNING and up) to stderr, so a long multi-colony
 * run does not flood the console while problems still surface live. The file lives under the
 * run's seed-scoped folder, {@code output/<seed>/<seed>.log}, alongside that run's
 * CSVs — so all 10 (or 2, or 1) colonies of one {@link GameSession} write one
 * combined, seed-stamped log. A session-less colony (a direct {@code Settlement} in
 * a unit test) falls back to {@code output/sim.log}.
 * <p>
 * <b>Whose date/name prefixes a record</b> is held <b>per thread</b>: a record is
 * prefixed from the colony {@linkplain #bind(Settlement) bound} on the emitting
 * thread, so when several colonies run concurrently — one thread each — each
 * thread's records carry its own colony's name and date with no cross-talk. Work
 * that runs <em>between</em> colonies — the session's {@link
 * com.civstudio.agent.Caravan wandering bands}, ticked at the day-barrier — belongs
 * to no single colony; {@link #asRealm(Settlement, Runnable)} labels those records
 * {@code (realm)} (with a representative colony's date) instead of an arbitrary
 * colony's identity.
 */
public final class SimLog {

	private static boolean initialized = false;

	// the logging context (date source + label) for records on the emitting thread.
	// per-thread so concurrent colonies (a thread each) don't overwrite a single
	// shared reference and prefix each other's records with the wrong colony.
	private static final ThreadLocal<Ctx> CURRENT = new ThreadLocal<>();

	// the prefix source: a colony supplies the in-game date, and the label is its
	// name unless overridden (the realm label for between-colony band work).
	private record Ctx(Settlement colony, String labelOverride) {
		String label() {
			if (labelOverride != null)
				return labelOverride;
			return colony == null ? "----------" : colony.getName();
		}

		String date() {
			return colony == null ? "----------" : colony.getDate().toString();
		}
	}

	private SimLog() {
	}

	/**
	 * Install the log handlers (once per process) and bind <tt>colony</tt> to the
	 * calling thread. Call once at the start of a simulation's <tt>main</tt>; a
	 * worker thread that did not call this should {@link #bind(Settlement)} its
	 * colony instead (the handlers are already installed).
	 *
	 * @param colony
	 *            the colony whose name and in-game date prefix records on this
	 *            thread, and whose {@link GameSession} seed scopes the log file
	 */
	public static synchronized void init(Settlement colony) {
		bind(colony);
		if (initialized)
			return;
		Logger root = Logger.getLogger("");
		for (Handler h : root.getHandlers())
			root.removeHandler(h);

		// the verbosity floor for the file, from the -Deos.log.level knob (default
		// INFO). INFO keeps only the colony lifecycle and the annual digest plus
		// anomalies; FINE adds the dynasty/demographic narrative (ennoblement, notable
		// arrivals, POI deaths); FINER (or ALL) adds the economic churn (firm
		// charter/dissolve, peasant starvation). Both the root logger and the file
		// handler must allow the level — the root gates records before any handler.
		Level level = parseLevel(System.getProperty("eos.log.level", "INFO"));

		// the full record sink: a per-run file under output/<seed>/, capturing every
		// record at or above the configured level so the run's narrative is preserved
		Handler file = openFileHandler(logFilePath(colony));
		if (file != null) {
			file.setLevel(level);
			file.setFormatter(new DateFormatter());
			root.addHandler(file);
		}

		// the live sink: only warnings/anomalies to stderr, so a multi-colony run
		// doesn't flood the console while real problems still appear immediately
		ConsoleHandler console = new ConsoleHandler();
		console.setLevel(Level.WARNING);
		console.setFormatter(new DateFormatter());
		root.addHandler(console);

		root.setLevel(level);
		initialized = true;
	}

	// parse a java.util.logging level name (INFO/FINE/FINER/ALL/…) from the
	// eos.log.level knob, defaulting to INFO on anything unrecognized
	private static Level parseLevel(String name) {
		try {
			return Level.parse(name.trim().toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException | NullPointerException e) {
			return Level.INFO;
		}
	}

	/**
	 * Bind <tt>colony</tt> as the source of the name and in-game date for log
	 * records emitted on the calling thread. Used by each colony's worker thread
	 * when the session runs colonies concurrently (the handlers are installed
	 * separately by {@link #init(Settlement)}).
	 *
	 * @param colony
	 *            the colony whose name and date prefix records on this thread
	 */
	public static void bind(Settlement colony) {
		CURRENT.set(new Ctx(colony, null));
	}

	/**
	 * Run <tt>body</tt> with this thread's records labelled {@code (realm)} — for
	 * session-level work that belongs to no single colony (the {@link
	 * com.civstudio.agent.Caravan wandering bands} ticked at the day-barrier). The
	 * date still comes from <tt>dateSource</tt> (any colony of the session — in
	 * lockstep they share the day), but the label is the realm rather than that
	 * colony's name, so a band's log is not misattributed to whichever colony
	 * happened to trip the barrier. The thread's previous binding is restored
	 * afterward.
	 *
	 * @param dateSource a colony of the session, to date the records
	 * @param body       the work to run under the realm label
	 */
	public static void asRealm(Settlement dateSource, Runnable body) {
		Ctx prev = CURRENT.get();
		CURRENT.set(new Ctx(dateSource, "(realm)"));
		try {
			body.run();
		} finally {
			CURRENT.set(prev);
		}
	}

	// the seed-scoped log file path for colony's session, output/<seed>/<seed>.log;
	// a session-less colony (a direct Settlement in a test) falls back to output/sim.log
	private static String logFilePath(Settlement colony) {
		GameSession session = colony == null ? null : colony.getSession();
		if (session == null)
			return "output/sim.log";
		long seed = session.getSeed();
		return "output/" + seed + "/" + seed + ".log";
	}

	// open a per-record-flushing file handler at path (creating parent dirs); null
	// if the file cannot be opened, so logging degrades to the console rather than
	// failing the run
	private static Handler openFileHandler(String path) {
		try {
			File parent = new File(path).getParentFile();
			if (parent != null && !parent.exists())
				parent.mkdirs();
			// flush per record so the log is readable mid-run (matches the console
			// handler's immediacy); StreamHandler buffers otherwise
			return new StreamHandler(new FileOutputStream(path), new DateFormatter()) {
				@Override
				public synchronized void publish(LogRecord record) {
					super.publish(record);
					flush();
				}
			};
		} catch (IOException e) {
			System.err.println("SimLog: could not open log file " + path + ": "
					+ e.getMessage());
			return null;
		}
	}

	/** Prefixes each message with the colony, the in-game date and a level label. */
	private static final class DateFormatter extends Formatter {
		@Override
		public String format(LogRecord record) {
			String level = record.getLevel() == Level.WARNING ? "WARN"
					: record.getLevel().getName();
			// a record from a thread that never bound a colony (defensive): emit it
			// with placeholders rather than NPE
			Ctx ctx = CURRENT.get();
			String label = (ctx == null) ? "----------" : ctx.label();
			String date = (ctx == null) ? "----------" : ctx.date();
			return label + " " + date + ": " + level + " "
					+ formatMessage(record) + System.lineSeparator();
		}
	}
}
