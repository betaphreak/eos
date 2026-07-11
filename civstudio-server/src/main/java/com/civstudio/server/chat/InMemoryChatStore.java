package com.civstudio.server.chat;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.civstudio.server.render.ChatMessage;

/**
 * In-memory {@link ChatStore}: a bounded per-session ring, lost on restart. The default when no
 * datasource is configured — chat still works within a run (and replays to late joiners), it just
 * isn't durable. Mirrors the in-memory fallbacks of the command / user stores.
 */
public final class InMemoryChatStore implements ChatStore {

	private static final int CAP = 200; // messages kept per session

	private final Map<String, Deque<ChatMessage>> bySession = new ConcurrentHashMap<>();

	@Override
	public void append(String sessionId, String user, String text) {
		Deque<ChatMessage> q = bySession.computeIfAbsent(sessionId, k -> new ArrayDeque<>());
		synchronized (q) {
			q.addLast(new ChatMessage(user, text));
			while (q.size() > CAP)
				q.removeFirst();
		}
	}

	@Override
	public List<ChatMessage> recent(String sessionId, int limit) {
		Deque<ChatMessage> q = bySession.get(sessionId);
		if (q == null || limit <= 0)
			return List.of();
		synchronized (q) {
			int skip = Math.max(0, q.size() - limit);
			List<ChatMessage> out = new ArrayList<>(Math.min(limit, q.size()));
			int i = 0;
			for (ChatMessage m : q) // ArrayDeque iterates oldest→newest
				if (i++ >= skip)
					out.add(m);
			return out;
		}
	}
}
