package com.civstudio.server.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.civstudio.server.render.ChatMessage;

/** The in-memory {@link ChatStore} (the no-datasource default): ordering, per-session scope, and
 * the recent() limit. */
class InMemoryChatStoreTest {

	@Test
	void appendsReplayOldestFirstAndScopePerSession() {
		ChatStore store = new InMemoryChatStore();
		store.append("a", "alice", "hi");
		store.append("a", "bob", "yo");
		store.append("b", "carol", "elsewhere");
		List<ChatMessage> a = store.recent("a", 10);
		assertEquals(List.of("alice", "bob"), a.stream().map(ChatMessage::user).toList());
		assertEquals(1, store.recent("b", 10).size());
		assertTrue(store.recent("missing", 10).isEmpty());
	}

	@Test
	void recentKeepsTheLatestUpToTheLimit() {
		ChatStore store = new InMemoryChatStore();
		for (int i = 0; i < 6; i++)
			store.append("s", "u", "m" + i);
		assertEquals(List.of("m3", "m4", "m5"), store.recent("s", 3).stream().map(ChatMessage::text).toList());
	}
}
