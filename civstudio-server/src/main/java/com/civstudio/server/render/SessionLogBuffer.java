package com.civstudio.server.render;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A small, thread-safe holding buffer for a hosted session's event-log lines between snapshot
 * emissions. A {@link com.civstudio.io.SimLog} tap {@link #add adds} lines on colony worker
 * threads; the session thread {@link #drain drains} them into each {@link SessionSnapshot} so the
 * browser's live log bar receives the delta since the last frame. Bounded, so a burst between
 * emits (or a client that never connects) can't grow it without limit.
 *
 * <p>Nothing is filtered out here: every line the tap sees is forwarded, tagged {@code curated} (see
 * {@link LogLine#of}, which derives that flag and the severity for this feed and the retained
 * {@link SessionEventLog} tail alike). The bar shows curated lines by default and all lines under its
 * "show all" toggle; the notification board shows every line, curated ones as full cards.
 */
public final class SessionLogBuffer {

	// cap on lines held between drains; a normal emit interval produces a handful, so this only
	// bites if a client is slow/absent — then the oldest lines drop (a live feed, not an archive)
	private static final int CAP = 512;

	private final ConcurrentLinkedQueue<LogLine> pending = new ConcurrentLinkedQueue<>();
	private final AtomicInteger size = new AtomicInteger();

	/** Append a message (from the SimLog tap) with its in-game date and JUL level value. */
	public void add(String date, String message, int level) {
		pending.add(LogLine.of(date, message, level));
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
}
