package com.civstudio.server.chat;

import java.util.List;

import com.civstudio.server.render.ChatMessage;

/**
 * Persistence for lobby chat, following the same opt-in pattern as {@code CommandStore} /
 * {@code UserStore}: an {@link InMemoryChatStore} by default, a {@link JdbcChatStore} when a
 * datasource is configured (see {@code PersistenceConfig}). A {@link com.civstudio.server.HostedSession}
 * appends each posted message and replays the most recent ones to a newly-connected spectator.
 */
public interface ChatStore {

	/**
	 * Record a chat message for a session, in post order.
	 *
	 * @param sessionId the session the message belongs to
	 * @param user      the poster's display name (server-resolved)
	 * @param text      the message body
	 */
	void append(String sessionId, String user, String text);

	/**
	 * The most recent messages for a session, <b>oldest-first</b> (ready to replay to a new client),
	 * capped at {@code limit}.
	 *
	 * @param sessionId the session
	 * @param limit     the maximum number of messages
	 * @return the recent messages, oldest-first (empty if none)
	 */
	List<ChatMessage> recent(String sessionId, int limit);
}
