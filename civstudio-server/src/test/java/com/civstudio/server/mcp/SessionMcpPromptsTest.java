package com.civstudio.server.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.modelcontextprotocol.spec.McpSchema.GetPromptResult;
import io.modelcontextprotocol.spec.McpSchema.Role;
import io.modelcontextprotocol.spec.McpSchema.TextContent;

/** Unit coverage for the server-shipped diagnosis prompt — Spring-free (pure text, no session). */
class SessionMcpPromptsTest {

	private final SessionMcpPrompts prompts = new SessionMcpPrompts();

	@Test
	void playbookNamesTheSessionWhenGiven() {
		GetPromptResult r = prompts.diagnoseLiveColony("caravan-demo-7654321");
		assertFalse(r.messages().isEmpty());
		assertEquals(Role.USER, r.messages().get(0).role());
		String text = ((TextContent) r.messages().get(0).content()).text();
		assertTrue(text.contains("caravan-demo-7654321"), "the playbook should target the given session");
		assertTrue(text.contains("get_snapshot") && text.contains("get_command_log"),
				"the playbook should sequence the read tools");
	}

	@Test
	void playbookFallsBackToListWhenNoSession() {
		String text = ((TextContent) prompts.diagnoseLiveColony(null).messages().get(0).content()).text();
		assertTrue(text.contains("list_sessions"),
				"with no session id the playbook should start from list_sessions");
	}
}
