package com.civstudio.server.render;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A small, thread-safe holding buffer for a hosted session's event-log lines between snapshot
 * emissions. A {@link com.civstudio.io.SimLog} tap {@link #add adds} lines on colony worker
 * threads; the session thread {@link #drain drains} them into each {@link SessionSnapshot} so the
 * browser's live log bar receives the delta since the last frame. Bounded, so a burst between
 * emits (or a client that never connects) can't grow it without limit.
 *
 * <p>Each line is tagged {@code curated} = a warning, or its text matches a small allow-list of
 * notable events (foundings, deaths, policy/tax changes, settling, …). The bar shows curated lines
 * by default and all lines under its "show all" toggle. The allow-list is a display heuristic and
 * intentionally easy to tune here.
 */
public final class SessionLogBuffer {

	// cap on lines held between drains; a normal emit interval produces a handful, so this only
	// bites if a client is slow/absent — then the oldest lines drop (a live feed, not an archive)
	private static final int CAP = 512;

	// substrings (lowercased) that mark a line as a notable, curated event — kept specific so a
	// routine line doesn't match incidentally (e.g. the annual digest mentions "nobles"/"deaths").
	// Tunable.
	private static final String[] CURATED = {
			"founded", "died", "death", "dissolv", "collaps", "ennobl",
			"tax rate", "settl", "re-found", "immigr", "born", "wed", "marri", "starv"
	};

	private final ConcurrentLinkedQueue<LogLine> pending = new ConcurrentLinkedQueue<>();
	private final AtomicInteger size = new AtomicInteger();

	/**
	 * Append a message (from the SimLog tap) with its in-game date and JUL level value; derives the
	 * severity ({@code info}/{@code warn}/{@code error}) and the curated flag (warnings/errors, or a
	 * notable-event keyword match).
	 */
	public void add(String date, String message, int level) {
		// JUL: WARNING = 900, SEVERE = 1000
		String sev = level >= 1000 ? "error" : level >= 900 ? "warn" : "info";
		boolean warning = level >= 900;
		pending.add(new LogLine(date, message, warning || curated(message), sev));
		if (size.incrementAndGet() > CAP && pending.poll() != null)
			size.decrementAndGet();
	}

	/** Remove and return all buffered lines (oldest first); empty if none. */
	public List<LogLine> drain() {
		if (pending.isEmpty())
			return List.of();
		List<LogLine> out = new java.util.ArrayList<>();
		for (LogLine l; (l = pending.poll()) != null;) {
			out.add(l);
			size.decrementAndGet();
		}
		return out;
	}

	private static boolean curated(String text) {
		String low = text.toLowerCase(Locale.ROOT);
		if (low.contains("digest"))
			return false; // the periodic stats summary is "show all", not a curated event
		for (String k : CURATED)
			if (low.contains(k))
				return true;
		return false;
	}
}
