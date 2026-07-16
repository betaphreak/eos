package com.civstudio.server.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.civstudio.server.SessionHost;
import com.civstudio.server.command.SetTaxRateCommand;
import com.civstudio.server.command.SetTaxRateCommand.Lever;

/**
 * Unit coverage for the read-only MCP tool projections — Spring-free (no context, no world import),
 * so it stays fast. Verifies the empty/not-found behaviour and the command-row projection; the live
 * wiring against a real session is covered by {@link McpEndpointTest}.
 */
class SessionMcpToolsTest {

	@Test
	void listIsEmptyWhenNoSessions() {
		SessionMcpTools tools = new SessionMcpTools(new SessionHost());
		assertTrue(tools.listSessions().isEmpty(), "a fresh host has no sessions");
	}

	@Test
	void unknownSessionIsAnError() {
		SessionMcpTools tools = new SessionMcpTools(new SessionHost());
		// read tools reject an unknown id rather than returning null, so the LLM gets a clear error
		assertThrows(IllegalArgumentException.class, () -> tools.getSnapshot("no-such-session"));
		assertThrows(IllegalArgumentException.class, () -> tools.getCommandLog("no-such-session"));
		assertThrows(IllegalArgumentException.class, () -> tools.getPerson("no-such-session", 1));
	}

	@Test
	void reportsTheMapVersion() {
		SessionMcpTools tools = new SessionMcpTools(new SessionHost());
		// session-independent: the tool surfaces the plot-generation version (also in /api/bundle)
		assertEquals(com.civstudio.settlement.ProvincePlotStore.MAP_VERSION,
				tools.getMapVersion().mapVersion());
	}

	@Test
	void projectsTheKnownCommandType() {
		SessionMcpTools.CommandInfo row =
				SessionMcpTools.project(new SetTaxRateCommand(42L, Lever.BANK_PROFIT, 0.25));
		assertEquals(42L, row.tick());
		assertEquals("setTaxRate", row.type());
		assertEquals("BANK_PROFIT", row.lever());
		assertEquals(0.25, row.rate());
	}

	@Test
	void projectsAnUnknownCommandWithoutLeverOrRate() {
		// a future GameCommand with no codec still projects to {tick, type}, never crashing the log
		SessionMcpTools.CommandInfo row = SessionMcpTools.project(new com.civstudio.server.command.GameCommand() {
			@Override public long tick() { return 7L; }
			@Override public void apply(com.civstudio.server.HostedSession session) { }
		});
		assertEquals(7L, row.tick());
		assertNull(row.lever());
		assertNull(row.rate());
	}
}
