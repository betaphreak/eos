package com.civstudio.server.render;

import java.util.Locale;

import com.civstudio.agent.Rank;

/**
 * One event-log line from a hosted session: streamed to the browser in a {@link SessionSnapshot} (the
 * live log bar renders it as {@code <server>@<date>  <text>}; the notification board renders it as a
 * card), served as a retained tail by {@link SessionEventLog}, and returned by the {@code get_events}
 * MCP tool.
 *
 * <p>Build one with {@link #of}, never the canonical constructor: {@code curated} and {@code sev} are
 * <b>derived</b> from the JUL level and the message, and the two feeds must derive them identically.
 * They once did not — {@link SessionEventLog} flagged only warnings as curated while
 * {@link SessionLogBuffer} also matched the notable-event allow-list, so the very same line arrived
 * curated over the snapshot delta and routine over the tail. That is invisible until something reads
 * both: the notification board rehydrates from the tail and is then fed live, and a founding would
 * have changed shape under the reader on reload. Deriving it in one place makes the two agree by
 * construction rather than by two lists staying in step.
 *
 * @param date    the emitting colony's in-game date (ISO-8601), the message's timestamp
 * @param text    the log message
 * @param curated whether this is a notable event (foundings, deaths, policy changes, anomalies) —
 *                shown by default in the log bar — versus routine churn. A keyword guess, and the
 *                log bar's filter; the notification board ranks by {@code rankLevel} instead.
 * @param sev     severity for colouring: {@code "info"}, {@code "warn"} or {@code "error"}
 * @param rank    the {@link Rank} name of what the event is <b>about</b> — {@code "HOUSEHOLD"} for a
 *                birth, {@code "VILLAGE"} for a founding — or null for an unranked line
 * @param rankLevel
 *                {@link Rank#level()}, or {@code -1} when unranked. The number the board actually
 *                filters on: a viewer plays at a rank and sees everything at or above it plus one
 *                rung below (their vassals), so the same event is a card for a captain and silence
 *                for a mayor. Sent as the level, not just the name, so the client needn't carry a
 *                copy of the ladder to compare two ranks. See {@code docs/notifications.md}.
 */
public record LogLine(String date, String text, boolean curated, String sev, String rank, int rankLevel) {

	// substrings (lowercased) that mark a line as a notable, curated event — kept specific so a
	// routine line doesn't match incidentally (e.g. the annual digest mentions "nobles"/"deaths").
	// Tunable; this is a display heuristic, not a contract.
	//
	// "notable", "promot" and "succeeded" carry the dynasty/demographic narrative the live tap now
	// delivers (SimLog's tap floor is FINE): a person raised from the peasantry, a notable arrival or
	// death, a house succeeding. Without them a promotion arrived on the board as routine churn.
	private static final String[] CURATED = {
			"founded", "died", "death", "dissolv", "collaps", "ennobl", "notable", "promot",
			"succeeded", "tax rate", "settl", "re-found", "immigr", "born", "wed", "marri", "starv"
	};

	/**
	 * Build a line from a {@link com.civstudio.io.SimLog} tap's raw fields, deriving the severity and
	 * the curated flag. The single place either is decided.
	 *
	 * @param level the JUL level value (WARNING = 900, SEVERE = 1000)
	 * @param rank  the event's {@link Rank} — the scope of what it is about — or null if the line was
	 *              logged through a plain {@code log.info} rather than {@code SimLog.event}
	 */
	public static LogLine of(String date, String message, int level, Rank rank) {
		boolean warning = level >= 900;
		String sev = level >= 1000 ? "error" : warning ? "warn" : "info";
		return new LogLine(date, message, warning || curated(message), sev,
				rank == null ? null : rank.name(), rank == null ? -1 : rank.level());
	}

	/** Whether a message names a notable event. Warnings are curated regardless — see {@link #of}. */
	private static boolean curated(String text) {
		if (text == null)
			return false;
		String low = text.toLowerCase(Locale.ROOT);
		if (low.contains("digest"))
			return false; // the periodic stats summary is routine churn, not an event
		for (String k : CURATED)
			if (low.contains(k))
				return true;
		return false;
	}
}
