package com.civstudio.server.render;

/**
 * One event-log line in a {@link SessionSnapshot}, streamed to the browser's live log bar. The bar
 * renders it as {@code <server>@<date>  <text>} (the server label is client-derived from the
 * connected server), so the header is the server + in-game date rather than the file log's colony
 * prefix.
 *
 * @param date    the emitting colony's in-game date (ISO-8601), the message's timestamp
 * @param text    the log message
 * @param curated whether this is a notable event (foundings, deaths, policy changes, anomalies)
 *                shown by default, versus routine churn shown only under the bar's "show all"
 *                toggle. Computed server-side by {@link SessionLogBuffer}.
 * @param sev     severity for colouring: {@code "info"}, {@code "warn"} or {@code "error"}
 */
public record LogLine(String date, String text, boolean curated, String sev) {
}
