package com.civstudio.server.mcp;

import java.util.List;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import com.civstudio.agent.Household;
import com.civstudio.server.HostedSession;
import com.civstudio.server.SessionHost;
import com.civstudio.server.command.GameCommand;
import com.civstudio.server.command.SetTaxRateCommand;
import com.civstudio.server.render.PersonDetail;
import com.civstudio.server.render.PersonProjections;
import com.civstudio.server.render.SessionSnapshot;
import com.civstudio.settlement.Settlement;

/**
 * Read-only MCP tools over the live {@link SessionHost} — the query half of the surface sketched in
 * {@code docs/mcp-server.md} Phase 2. Each tool is a thin projection over a seam the REST controllers
 * already expose ({@link SessionController} / {@link PersonController}); none advances a tick or
 * mutates engine state, so they inherit the server's spectate-anonymously stance (like the SSE feed).
 * The write tools (create/control/submit) are a later, admin-gated addition.
 *
 * <p>The Spring AI MCP annotation scanner discovers this {@code @Component}'s {@code @McpTool}
 * methods automatically (see {@code spring.ai.mcp.server} in {@code application.yml}).
 */
@Component
public class SessionMcpTools {

	private final SessionHost host;

	public SessionMcpTools(SessionHost host) {
		this.host = host;
	}

	@McpTool(name = "list_sessions",
			description = "List all hosted simulation sessions with their scenario, seed, control "
					+ "state and current tick. Use a returned id to query the other tools.")
	public List<SessionInfo> listSessions() {
		return host.list().stream()
				.map(hs -> new SessionInfo(hs.id(), hs.spec().scenario(), hs.spec().seed(),
						hs.state().name(), hs.tick()))
				.toList();
	}

	@McpTool(name = "get_snapshot",
			description = "Latest tick-paced render snapshot for a session: colonies (population, "
					+ "prices, taxes, advisors, districts), caravans, and recent log lines. Read-only; "
					+ "null until the session emits its first frame.")
	public SessionSnapshot getSnapshot(
			@McpToolParam(description = "Session id, e.g. 'caravan-demo-7654321'", required = true)
			String sessionId) {
		return require(sessionId).currentSnapshot();
	}

	@McpTool(name = "get_person",
			description = "A court member's character sheet (race, role, age, skills, household) by "
					+ "agent id, resolved against the session's POV (first) colony — agent ids are "
					+ "unique only within a colony.")
	public PersonDetail getPerson(
			@McpToolParam(description = "Session id", required = true) String sessionId,
			@McpToolParam(description = "Agent id within the POV colony", required = true) int personId) {
		HostedSession hs = require(sessionId);
		List<Settlement> colonies = hs.colonies();
		if (colonies.isEmpty())
			throw new IllegalArgumentException("session " + sessionId + " has no colonies");
		Settlement pov = colonies.get(0);
		Household h = pov.getHouseholdById(personId);
		if (h == null)
			throw new IllegalArgumentException("no person " + personId + " in session " + sessionId);
		String culture = pov.getProvince() == null ? null : pov.getProvince().culture();
		return PersonProjections.of(h, pov.getDate(), culture);
	}

	@McpTool(name = "get_command_log",
			description = "The session's ordered, tick-stamped command history — the applied replay "
					+ "log (its savegame). Read-only; empty during pure spectator play.")
	public List<CommandInfo> getCommandLog(
			@McpToolParam(description = "Session id", required = true) String sessionId) {
		return require(sessionId).commandLog().history().stream()
				.map(SessionMcpTools::project)
				.toList();
	}

	private HostedSession require(String sessionId) {
		HostedSession hs = host.get(sessionId);
		if (hs == null)
			throw new IllegalArgumentException("no session " + sessionId);
		return hs;
	}

	/** Project a persisted command to a typed row. Mirrors {@code CommandCodec}'s one known type. */
	static CommandInfo project(GameCommand c) {
		if (c instanceof SetTaxRateCommand s)
			return new CommandInfo(s.tick(), "setTaxRate", s.lever().name(), s.rate());
		return new CommandInfo(c.tick(), c.getClass().getSimpleName(), null, null);
	}

	/** One row of {@link #listSessions()}. */
	public record SessionInfo(String id, String scenario, long seed, String state, long tick) {}

	/** One applied command in {@link #getCommandLog}; {@code lever}/{@code rate} null for other types. */
	public record CommandInfo(long tick, String type, String lever, Double rate) {}
}
