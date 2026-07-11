package com.civstudio.io;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
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
 * session-<b>demultiplexing</b> file handler and a <b>console</b> handler. The file
 * handler routes each record to the log of the {@link GameSession} it belongs to —
 * resolved from the colony {@linkplain #bind(Settlement) bound} on the emitting
 * thread — so <b>several sessions can run in one JVM</b> and each writes its own
 * clean, non-interleaved log under its seed-scoped folder, {@code
 * output/<seed>/<seed>.log}, alongside that session's CSVs. Every colony of one
 * session shares that one file; a session-less colony (a direct {@code Settlement}
 * in a unit test) falls back to {@code output/sim.log}. The console handler mirrors
 * only <b>warnings and anomalies</b> (WARNING and up) to stderr — shared across all
 * sessions — so a long run does not flood the console while problems still surface
 * live. A per-session file sink is opened on first use and cached; a host tearing a
 * session down releases it with {@link #closeSession(Settlement)}.
 * <p>
 * <b>Whose date/name prefixes a record</b> is held <b>per thread</b>: a record is
 * prefixed (and now <em>routed</em>) from the colony {@linkplain #bind(Settlement)
 * bound} on the emitting thread, so when several colonies run concurrently — one
 * thread each — each thread's records carry its own colony's name and date, and land
 * in its own session's file, with no cross-talk. Work that runs <em>between</em>
 * colonies — the session's {@link com.civstudio.agent.Caravan wandering bands},
 * ticked at the day-barrier — belongs to no single colony; {@link #asRealm(Settlement,
 * Runnable)} labels those records {@code (realm)} (with a representative colony's
 * date, so they route to that colony's session file) instead of an arbitrary colony's
 * identity.
 */
public final class SimLog {

	// whether the process-wide handlers (the dispatch + console handlers on the root
	// logger) have been installed. Installed exactly once per process, regardless of
	// how many sessions run; the per-session files are keyed separately (see SINKS).
	private static volatile boolean handlersInstalled = false;

	// the verbosity floor (from -Deos.log.level), resolved once when the handlers are
	// installed and reused for the dispatch handler
	private static volatile Level level = Level.INFO;

	// the logging context (date source + label) for records on the emitting thread.
	// per-thread so concurrent colonies (a thread each) don't overwrite a single
	// shared reference and prefix/route each other's records with the wrong colony.
	private static final ThreadLocal<Ctx> CURRENT = new ThreadLocal<>();

	// per-session file sinks, keyed by the session's log-file path — so every colony
	// of one GameSession shares a sink while distinct sessions in the same JVM write
	// to separate, non-interleaved files. Opened on first use and cached; a failed
	// open caches Sink.NO_OP so the process degrades to console-only for that session
	// rather than retrying the open on every record.
	private static final Map<String, Sink> SINKS = new ConcurrentHashMap<>();

	// per-session in-memory taps, keyed by the same session log-file path as SINKS. A tap
	// receives each formatted record for its session live (in addition to the file sink) — the
	// seam the hosted server uses to stream the event log to spectators (see the live log bar /
	// docs/client-server.md). At most one tap per session (the HostedSession); a new tap replaces
	// any prior one for that path. Empty for a plain headless run, so it costs nothing there.
	private static final Map<String, Consumer<Entry>> LISTENERS = new ConcurrentHashMap<>();

	/**
	 * One log record delivered to a {@linkplain #tap tap}, as structured fields so a consumer can
	 * present its own header rather than the file format's colony prefix: the in-game {@code date}
	 * of the emitting colony (ISO-8601, or empty if unbound), the raw {@code message}, and the JUL
	 * {@code level} value ({@link Level#intValue()} — e.g. 800 INFO, 900 WARNING, 1000 SEVERE) so a
	 * consumer can colour by severity.
	 */
	public record Entry(String date, String message, int level) {
	}

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
	 * Install the process-wide handlers (once) and bind <tt>colony</tt> to the calling
	 * thread, pre-opening its session's log file. Call once per session at the start of
	 * its setup; a worker thread that did not call this should {@link #bind(Settlement)}
	 * its colony instead (the handlers are already installed, and its session's sink
	 * opens on the first record). Calling it again for another session in the same JVM
	 * is safe — the handlers install once, and each session opens its own file.
	 *
	 * @param colony
	 *            the colony whose name and in-game date prefix records on this thread,
	 *            and whose {@link GameSession} seed scopes the log file
	 */
	public static void init(Settlement colony) {
		bind(colony);
		installProcessHandlers();
		// pre-open this session's sink so its log file exists under output/<seed>/ even
		// before the first record (matching the eager open of the former file handler)
		sinkFor(logFilePath(colony));
	}

	// install the root logger's handlers exactly once per process: a session-
	// demultiplexing file handler (routes each record to the bound session's own file)
	// and a console handler mirroring warnings to stderr. Synchronized + idempotent, so
	// concurrent sessions racing to init install them once between them.
	private static synchronized void installProcessHandlers() {
		if (handlersInstalled)
			return;
		Logger root = Logger.getLogger("");
		for (Handler h : root.getHandlers())
			root.removeHandler(h);

		// the verbosity floor for the files, from the -Deos.log.level knob (default
		// INFO). INFO keeps only the colony lifecycle and the annual digest plus
		// anomalies; FINE adds the dynasty/demographic narrative (ennoblement, notable
		// arrivals, POI deaths); FINER (or ALL) adds the economic churn (firm
		// charter/dissolve, peasant starvation). Both the root logger and the dispatch
		// handler must allow the level — the root gates records before any handler.
		level = parseLevel(System.getProperty("eos.log.level", "INFO"));

		// the full record sink: routes every record at or above the configured level to
		// the emitting thread's session file, so many sessions in one JVM stay separate
		Handler dispatch = new DispatchHandler();
		dispatch.setLevel(level);
		dispatch.setFormatter(new DateFormatter());
		root.addHandler(dispatch);

		// the live sink: only warnings/anomalies to stderr, so a multi-colony run
		// doesn't flood the console while real problems still appear immediately
		ConsoleHandler console = new ConsoleHandler();
		console.setLevel(Level.WARNING);
		console.setFormatter(new DateFormatter());
		root.addHandler(console);

		// optional full-log mirror to stdout, for a container that captures stdout as its
		// log stream (no per-session files to read). Off unless -Deos.log.stdout=<level> is
		// set (the server image sets it), so local runs and tests are unchanged. Flushes per
		// record like the file sink so the container log is live.
		String stdoutLevel = System.getProperty("eos.log.stdout");
		if (stdoutLevel != null) {
			StreamHandler out = new StreamHandler(System.out, new DateFormatter()) {
				@Override
				public synchronized void publish(LogRecord record) {
					super.publish(record);
					flush();
				}
			};
			out.setLevel(parseLevel(stdoutLevel));
			root.addHandler(out);
		}

		root.setLevel(level);
		handlersInstalled = true;
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
	 * records emitted on the calling thread (and the session their file is routed
	 * to). Used by each colony's worker thread when the session runs colonies
	 * concurrently (the handlers are installed separately by {@link #init(Settlement)}).
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
	 * lockstep they share the day), and the records route to that colony's session
	 * file, but the label is the realm rather than that colony's name, so a band's log
	 * is not misattributed to whichever colony happened to trip the barrier. The
	 * thread's previous binding is restored afterward.
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

	/**
	 * Register a live tap on the session <tt>colony</tt> belongs to: <tt>listener</tt> is handed
	 * every formatted record for that session, on the emitting (colony worker) thread, in addition
	 * to the file sink — so it must be cheap and thread-safe. Replaces any prior tap for the same
	 * session. Returns a handle that removes it (idempotent); the caller unregisters on session
	 * teardown. A listener that throws is dropped rather than breaking logging.
	 *
	 * @param colony   a colony of the session to tap
	 * @param listener receives each session record
	 * @return a handle that unregisters the tap
	 */
	public static AutoCloseable tap(Settlement colony, Consumer<Entry> listener) {
		String path = logFilePath(colony);
		LISTENERS.put(path, listener);
		return () -> LISTENERS.remove(path, listener);
	}

	/**
	 * Flush and close the file sink of <tt>colony</tt>'s session, if open — the
	 * session-teardown seam a many-sessions-per-JVM host calls when it evicts a
	 * session, so the file handle is released rather than leaked. A plain
	 * single-session run may skip it: the sink flushes per record, and the JVM
	 * reclaims the handle on exit. Safe to call more than once (a no-op if the sink is
	 * already closed or was never opened).
	 *
	 * @param colony a colony of the session whose log file should be released
	 */
	public static void closeSession(Settlement colony) {
		Sink sink = SINKS.remove(logFilePath(colony));
		if (sink != null)
			sink.close();
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

	// the sink for path, opened on first use and cached (a whole session's colonies
	// share one path, hence one sink); a failed open caches Sink.NO_OP so the process
	// degrades to console-only for that session rather than retrying every record
	private static Sink sinkFor(String path) {
		return SINKS.computeIfAbsent(path, SimLog::openSink);
	}

	// open a per-record-flushing file sink at path (creating parent dirs), truncating
	// any prior file; Sink.NO_OP if it cannot be opened, so logging degrades to the
	// console rather than failing the run
	private static Sink openSink(String path) {
		try {
			File parent = new File(path).getParentFile();
			if (parent != null && !parent.exists())
				parent.mkdirs();
			return new Sink(new BufferedWriter(new FileWriter(path)));
		} catch (IOException e) {
			System.err.println("SimLog: could not open log file " + path + ": "
					+ e.getMessage());
			return Sink.NO_OP;
		}
	}

	// a per-session file writer. Writes are synchronized so concurrent colony threads
	// of one session don't interleave a record, and flushed per record so the log is
	// readable mid-run (matching the console handler's immediacy).
	private static final class Sink {
		// the sentinel for a session whose file could not be opened: swallows writes so
		// its records survive only on the console (see openSink)
		static final Sink NO_OP = new Sink(null);

		private final Writer out;

		Sink(Writer out) {
			this.out = out;
		}

		synchronized void write(String formatted) {
			if (out == null)
				return;
			try {
				out.write(formatted);
				out.flush();
			} catch (IOException e) {
				// degrade silently — warnings still reach the console; failing the run
				// over a log write would be worse than a gap in the file
			}
		}

		synchronized void close() {
			if (out == null)
				return;
			try {
				out.close();
			} catch (IOException ignored) {
				// closing a log file is best-effort; nothing to recover
			}
		}
	}

	// routes each record to the bound session's file sink, resolved from the emitting
	// thread's Ctx (colony -> session -> path). A record from a thread bound to no
	// session — or whose session sink failed to open — is dropped here and survives
	// only on the console.
	private static final class DispatchHandler extends Handler {
		@Override
		public void publish(LogRecord record) {
			if (!isLoggable(record))
				return;
			Ctx ctx = CURRENT.get();
			Settlement colony = ctx == null ? null : ctx.colony();
			// format on the emitting thread (the DateFormatter reads the same Ctx), then
			// hand the finished line to the session's sink
			String path = logFilePath(colony);
			String formatted = getFormatter().format(record);
			sinkFor(path).write(formatted);
			// mirror to the session's live tap, if any (the spectator log feed) — as structured
			// fields (date + message), so the client can render its own "server@date" header
			Consumer<Entry> listener = LISTENERS.get(path);
			if (listener != null)
				try {
					String date = ctx == null ? "" : ctx.date();
					listener.accept(new Entry(date, record.getMessage(),
							record.getLevel().intValue()));
				} catch (RuntimeException e) {
					LISTENERS.remove(path, listener); // a tap must never break logging
				}
		}

		@Override
		public void flush() {
			// each Sink flushes per record; nothing is buffered at the handler level
		}

		@Override
		public void close() {
			// the sinks outlive the handler (keyed per session, closed via
			// closeSession); the handler owns no resource of its own to release
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
