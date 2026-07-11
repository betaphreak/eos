package com.civstudio.server.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.civstudio.server.render.ChatMessage;

/**
 * With a datasource configured (H2), lobby chat is persisted by the {@link JdbcChatStore} that
 * {@link com.civstudio.server.PersistenceConfig} wires — messages survive a restart and replay
 * oldest-first, capped to the requested limit, scoped per session.
 */
@SpringBootTest(properties = {
		"civstudio.demo.enabled=false",
		"spring.datasource.url=jdbc:h2:mem:chatlog;DB_CLOSE_DELAY=-1",
		"spring.datasource.username=sa",
		"spring.datasource.password=" })
class ChatPersistenceTest {

	@Autowired
	ChatStore store;

	@Test
	void backedByJdbc() {
		assertInstanceOf(JdbcChatStore.class, store, "a datasource is configured, so JDBC");
	}

	@Test
	void appendsAndReplaysOldestFirstScopedPerSession() {
		store.append("sess-A", "alice", "hello");
		store.append("sess-A", "76561198000000000", "caravan north");
		List<ChatMessage> recent = store.recent("sess-A", 10);
		assertEquals(2, recent.size());
		assertEquals("alice", recent.get(0).user(), "oldest first");
		assertEquals("hello", recent.get(0).text());
		assertEquals("76561198000000000", recent.get(1).user());
		assertTrue(store.recent("other-session", 10).isEmpty(), "scoped per session");
	}

	@Test
	void recentKeepsTheLatestUpToTheLimit() {
		for (int i = 0; i < 5; i++)
			store.append("sess-B", "u", "m" + i);
		List<ChatMessage> last3 = store.recent("sess-B", 3);
		assertEquals(3, last3.size());
		assertEquals("m2", last3.get(0).text(), "oldest of the last three");
		assertEquals("m4", last3.get(2).text(), "newest");
	}
}
