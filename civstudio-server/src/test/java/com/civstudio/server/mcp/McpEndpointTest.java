package com.civstudio.server.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import com.civstudio.server.HostedSession;
import com.civstudio.server.SessionHost;
import com.civstudio.server.SessionSpec;
import com.civstudio.server.render.SessionSnapshot;

/**
 * Boots the full server context (so the Spring AI MCP auto-config is exercised) and checks two
 * things: the read-only MCP tool/resource beans are wired against the live {@link SessionHost} and
 * project a real paused session correctly, and the Streamable HTTP MCP endpoint is actually mapped
 * at {@code /mcp}. The MCP JSON-RPC protocol itself is the SDK's concern — we assert the transport
 * is present (not 404), not that we can hand-drive the handshake.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = { "civstudio.demo.enabled=false" })
class McpEndpointTest {

	private static final int DHENIJANSAR = 4411;

	@LocalServerPort
	int port;

	@Autowired
	SessionHost host;

	@Autowired
	SessionMcpTools tools;

	@Autowired
	SessionMcpResources resources;

	private final HttpClient client = HttpClient.newHttpClient();

	@AfterEach
	void cleanup() {
		host.stopAll();
	}

	@Test
	@Timeout(120)
	void toolsProjectALiveSession() throws Exception {
		HostedSession hs = host.create(SessionSpec.caravanDemo(444L, DHENIJANSAR));
		hs.startPaused(); // emits the tick-0 snapshot asynchronously on the session thread

		// list_sessions reports the founded session with its identity fields
		var listed = tools.listSessions();
		assertEquals(1, listed.size());
		SessionMcpTools.SessionInfo info = listed.get(0);
		assertEquals(hs.id(), info.id());
		assertEquals(444L, info.seed());
		assertEquals(SessionSpec.CARAVAN_DEMO, info.scenario());

		// the tick-0 snapshot is emitted on the session thread — wait for it (as the SSE feed does)
		long deadline = System.nanoTime() + 60_000_000_000L;
		while (hs.currentSnapshot() == null && System.nanoTime() < deadline)
			Thread.sleep(5);

		// get_snapshot returns the cached tick-0 render snapshot for that session
		SessionSnapshot snap = tools.getSnapshot(hs.id());
		assertNotNull(snap, "a paused session has emitted its tick-0 snapshot");
		assertEquals(hs.id(), snap.sessionId());
		assertFalse(snap.colonies().isEmpty(), "the demo has a colony");

		// get_command_log is empty for pure spectator play (no commands applied yet)
		assertTrue(tools.getCommandLog(hs.id()).isEmpty());

		// the snapshot resource serializes the same session to a non-empty JSON body
		String resource = resources.snapshot(hs.id());
		assertTrue(resource.contains(hs.id()), "the snapshot resource carries the session id");
	}

	@Test
	@Timeout(120)
	void mcpEndpointIsMapped() throws Exception {
		// a bare POST to /mcp is malformed for the protocol, but a mapped endpoint answers with a
		// client-error/handshake status — NOT 404, which is what proves the transport is wired.
		HttpResponse<String> res = client.send(
				HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/mcp"))
						.header("Content-Type", "application/json")
						.POST(HttpRequest.BodyPublishers.ofString("{}"))
						.build(),
				HttpResponse.BodyHandlers.ofString());
		assertNotEquals(404, res.statusCode(),
				"the MCP Streamable HTTP endpoint should be mapped at /mcp");
	}
}
