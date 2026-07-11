package com.civstudio.server.render;

/**
 * A lobby chat message posted by an authenticated spectator, broadcast live to everyone watching a
 * session over its own SSE {@code chat} event (separate from the tick-paced snapshot feed, so chat
 * is immediate). Rendered in the browser log/chat window as {@code <user>: <text>} in a distinct
 * colour. The {@code user} is the poster's display name, resolved server-side from the authenticated
 * principal (never client-supplied), so it cannot be spoofed.
 *
 * @param user the poster's display name (e.g. a Steam id or a Google email)
 * @param text the message body (trimmed, length-capped by the controller)
 */
public record ChatMessage(String user, String text) {
}
