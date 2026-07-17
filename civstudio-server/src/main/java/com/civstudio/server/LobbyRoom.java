package com.civstudio.server;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.springframework.stereotype.Component;

import com.civstudio.server.chat.ChatStore;
import com.civstudio.server.render.ChatMessage;

/**
 * The lobby's chat room — the one channel that belongs to no session.
 * <p>
 * Session chat needs you to already be watching something; this is where you talk <em>before</em>
 * you have picked anything, which is what makes the lobby a place rather than a menu (see {@code
 * docs/spectator-lobby.md} Phase 1).
 * <p>
 * It is a {@link ChatStore} room under a <b>reserved key</b> rather than a store of its own: the
 * store is keyed by session id and knows nothing about sessions beyond that, so a key no session can
 * have gives the lobby the same persistence, the same backlog replay and the same server-resolved
 * display names for free. Durable exactly when chat is (a datasource configured), in-memory
 * otherwise.
 */
@Component
public class LobbyRoom {

	/**
	 * The room's {@link ChatStore} key. Starts with {@code @}, which no {@link SessionSpec#id()} can
	 * produce ({@code <scenario>-<seed>}), so the lobby can never collide with a real session's chat.
	 */
	public static final String ROOM = "@lobby";

	/** Messages replayed to someone who has just walked in. */
	private static final int REPLAY = 40;

	private final ChatStore chat;

	// serializes a subscribe (replay + add) against a post (append + broadcast), so a message can be
	// neither missed nor delivered twice by someone arriving mid-post — the same lock discipline
	// HostedSession uses for a session's chat
	private final Object lock = new Object();
	private final List<Consumer<ChatMessage>> subscribers = new ArrayList<>();

	public LobbyRoom(ChatStore chat) {
		this.chat = chat;
	}

	/**
	 * Say something in the lobby. The poster's display name is resolved server-side by the caller, so
	 * it cannot be spoofed.
	 *
	 * @param user the poster's display name
	 * @param text the message
	 */
	public void post(String user, String text) {
		ChatMessage msg = new ChatMessage(user, text);
		synchronized (lock) {
			chat.append(ROOM, user, text);
			for (Consumer<ChatMessage> c : subscribers)
				try {
					c.accept(msg);
				} catch (RuntimeException e) {
					// one broken listener must never break the broadcast
				}
		}
	}

	/**
	 * Listen to the lobby. The recent backlog is replayed first — so someone who has just arrived
	 * sees a conversation rather than an empty box — then every subsequent message.
	 *
	 * @param sub the message consumer
	 * @return an unsubscribe handle
	 */
	public AutoCloseable subscribe(Consumer<ChatMessage> sub) {
		synchronized (lock) {
			for (ChatMessage m : chat.recent(ROOM, REPLAY))
				try {
					sub.accept(m);
				} catch (RuntimeException ignored) {
					// best-effort backlog replay
				}
			subscribers.add(sub);
		}
		return () -> {
			synchronized (lock) {
				subscribers.remove(sub);
			}
		};
	}

	/** How many connections are listening to the lobby right now. */
	public int listeners() {
		synchronized (lock) {
			return subscribers.size();
		}
	}
}
