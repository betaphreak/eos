package com.civstudio.server.mcp;

import java.util.List;

import org.springframework.ai.mcp.annotation.McpResource;
import org.springframework.stereotype.Component;

import com.civstudio.server.HostedSession;
import com.civstudio.server.SessionHost;
import com.civstudio.server.render.LogLine;
import com.civstudio.server.render.SessionSnapshot;

import tools.jackson.databind.ObjectMapper;

/**
 * Read-only MCP resources over the live {@link SessionHost} (docs/mcp-server.md Phase 2): the render
 * snapshot and the recent event-log lines for a session, addressed by {@code civstudio://} URIs. A
 * resource is the "pull this as context" counterpart to the {@link SessionMcpTools} query tools — an
 * analyst LLM can attach a session's current state rather than round-tripping a tool call.
 *
 * <p>URI template variables (the {@code {id}}) bind to the method parameter of the same name. Bodies
 * are returned as JSON strings (serialized with the shared Jackson {@link ObjectMapper}); a missing
 * session or a not-yet-emitted snapshot serializes to {@code null}/{@code []} rather than erroring, so
 * the resource is always readable.
 */
@Component
public class SessionMcpResources {

	private final SessionHost host;
	private final ObjectMapper json;

	public SessionMcpResources(SessionHost host, ObjectMapper json) {
		this.host = host;
		this.json = json;
	}

	@McpResource(uri = "civstudio://session/{id}/snapshot", name = "session-snapshot",
			description = "The latest render snapshot for a session, as JSON (null before the first "
					+ "frame). Mirrors the get_snapshot tool.")
	public String snapshot(String id) {
		return json.writeValueAsString(currentSnapshot(id));
	}

	@McpResource(uri = "civstudio://session/{id}/events", name = "session-events",
			description = "The retained tail of the session's event log (foundings, deaths, policy "
					+ "changes, …), most recent lines, as a JSON array. For filtered access use the "
					+ "get_events tool.")
	public String events(String id) {
		HostedSession hs = host.get(id);
		List<LogLine> tail = hs == null ? List.of() : hs.eventTail(null, null, null, null, 0);
		return json.writeValueAsString(tail);
	}

	private SessionSnapshot currentSnapshot(String id) {
		HostedSession hs = host.get(id);
		return hs == null ? null : hs.currentSnapshot();
	}
}
