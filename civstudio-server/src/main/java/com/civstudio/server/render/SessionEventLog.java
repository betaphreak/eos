package com.civstudio.server.render;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.civstudio.agent.Rank;

/**
 * A bounded, retained tail of a hosted session's event log — the analysis counterpart to
 * {@link SessionLogBuffer}. Where {@code SessionLogBuffer} is a <b>drain-once</b> queue emptied into
 * each snapshot frame (so it only ever holds the delta since the last emit), this keeps a rolling
 * window of the last {@link #CAP} lines so the {@code get_events} tool / {@code events} MCP resource
 * can serve a real event tail (filter by severity, in-game date range, substring). Fed by the same
 * {@code SimLog} tap on the colony threads; queried from an MCP request thread — so both paths
 * synchronize on this buffer. See {@code docs/mcp-server.md} (retained per-session log buffer).
 *
 * <p>It is a <i>reporting mirror</i>: populated after each day's lines are emitted, never touching an
 * RNG stream, and bounded so a months-long session can't grow it without limit. The full history is
 * still the {@code CommandLog} replay; this is a convenience tail, not the archive.
 */
public final class SessionEventLog {

	// rolling window size; a session logs a handful of lines per colony-day, so this holds roughly
	// the last few in-game years of notable churn before the oldest lines age out. Bounded memory.
	private static final int CAP = 4096;

	// default number of most-recent matching lines a query returns when it doesn't cap itself
	static final int DEFAULT_LIMIT = 200;

	// guarded by `this`; add() runs on colony threads, query() on an MCP request thread
	private final ArrayDeque<LogLine> ring = new ArrayDeque<>();

	/**
	 * Append a line (from the SimLog tap); evicts the oldest past {@link #CAP}. Severity and the
	 * curated flag are derived by {@link LogLine#of} — the same derivation {@link SessionLogBuffer}
	 * uses, so a line served from this tail is identical to the one that went out over the snapshot
	 * delta. This used to flag only warnings as curated, which meant a founding read as a notable
	 * event live and as routine churn when replayed from here.
	 */
	public synchronized void add(String date, String message, int level, Rank rank) {
		ring.addLast(LogLine.of(date, message, level, rank));
		if (ring.size() > CAP)
			ring.removeFirst();
	}

	/**
	 * The most-recent matching lines in chronological order (oldest first).
	 *
	 * @param level minimum severity {@code info|warn|error}; null/blank includes all
	 * @param from  inclusive start in-game date (ISO-8601); null/blank = unbounded. ISO dates sort
	 *              lexically, so a string compare is a correct date compare.
	 * @param to    inclusive end in-game date (ISO-8601); null/blank = unbounded
	 * @param grep  case-insensitive substring the line text must contain; null/blank = no filter
	 * @param limit max lines returned (the most recent that match); {@code <= 0} → {@link #DEFAULT_LIMIT}
	 */
	public synchronized List<LogLine> query(String level, String from, String to, String grep, int limit) {
		int minRank = rank(level);
		String needle = (grep == null || grep.isBlank()) ? null : grep.toLowerCase(Locale.ROOT);
		String lo = (from == null || from.isBlank()) ? null : from;
		String hi = (to == null || to.isBlank()) ? null : to;
		int cap = limit <= 0 ? DEFAULT_LIMIT : limit;

		List<LogLine> out = new ArrayList<>();
		for (LogLine l : ring) {
			if (rank(l.sev()) < minRank)
				continue;
			if (lo != null && l.date().compareTo(lo) < 0)
				continue;
			if (hi != null && l.date().compareTo(hi) > 0)
				continue;
			if (needle != null && !l.text().toLowerCase(Locale.ROOT).contains(needle))
				continue;
			out.add(l);
		}
		if (out.size() > cap)
			return new ArrayList<>(out.subList(out.size() - cap, out.size()));
		return out;
	}

	// severity ordering for the minimum-level filter; unknown/null → 0 (info), so it never over-filters
	private static int rank(String sev) {
		if (sev == null)
			return 0;
		return switch (sev.toLowerCase(Locale.ROOT)) {
			case "error" -> 2;
			case "warn" -> 1;
			default -> 0;
		};
	}
}
