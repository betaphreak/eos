package com.civstudio.server.mcp;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import com.civstudio.data.WorldSources;
import com.civstudio.scenario.ScenarioDef;
import com.civstudio.scenario.ScenarioRegistry;
import com.civstudio.server.HostedSession;
import com.civstudio.server.SessionHost;
import com.civstudio.server.SessionSpec;
import com.civstudio.server.command.SetTaxRateCommand;

/**
 * The Phase-2 live-session <b>write</b> tools (see {@code docs/mcp-server.md}): create/control a
 * hosted session and submit player commands, over the same {@code /mcp} endpoint as the read tools.
 * Each is a thin peer of {@link com.civstudio.server.web.SessionController}'s create/control/commands
 * — same seams ({@link SessionHost} / {@link HostedSession}), same validation — but <b>admin-gated</b>
 * ({@link McpAuthz#requireAdmin()}) rather than owner-gated, since an MCP call carries no per-session
 * owner identity as cleanly as an authenticated REST request.
 *
 * <p>The one legal write path stays {@link HostedSession#submit(com.civstudio.server.command.GameCommand)}
 * / the control methods; a command is tick-stamped and applied at the deterministic top of its tick,
 * so the run stays reproducible/replayable (the reproducibility invariant in the design note).
 */
@Component
public class SessionWriteMcpTools {

	private final SessionHost host;
	private final McpAuthz authz;

	public SessionWriteMcpTools(SessionHost host, McpAuthz authz) {
		this.host = host;
		this.authz = authz;
	}

	@McpTool(name = "create_session",
			description = "Found and start a hosted session from a spec (admin only). Defaults to the "
					+ "caravan demo (seed 7654321, province 4411). The scenario names a ScenarioDef in "
					+ "the registry (its founding shape + balance profile); an unknown one is rejected "
					+ "with the valid keys. Returns the session id, state, and what it founded — shape, "
					+ "balance profile, and the content version (a run reproduces as seed + "
					+ "contentVersion + command log).")
	public CreateResult createSession(
			@McpToolParam(description = "Scenario key from the registry (default 'caravan-demo')",
					required = false) String scenario,
			@McpToolParam(description = "Run seed (default 7654321)", required = false) Long seed,
			@McpToolParam(description = "World-map province id (default 4411)", required = false)
			Integer provinceId) {
		authz.requireAdmin();
		long s = seed == null ? 7654321L : seed;
		String sc = scenario == null ? SessionSpec.CARAVAN_DEMO : scenario;
		int p = provinceId == null ? 4411 : provinceId;
		// validate up front for a NEW session — reject an unknown key with the valid ones, rather
		// than letting SessionHost.build silently found standard (that fallback is for RESTORING a
		// session whose scenario string predates the registry, not for a fresh create).
		ScenarioDef def = ScenarioRegistry.get().resolve(sc);
		if (def == null)
			throw new IllegalArgumentException("unknown scenario '" + sc + "'; valid keys: "
					+ ScenarioRegistry.get().all().stream().map(ScenarioDef::key).toList());
		HostedSession hs = host.create(new SessionSpec(s, sc, p), authz.userId());
		hs.kind().begin(hs);   // each kind begins its own way (docs/session-management.md)
		return new CreateResult(hs.id(), hs.clock().name(), hs.outcome().name(),
				def.shape().name(), def.balanceProfile(), WorldSources.contentVersion());
	}

	/** The floor on the tick interval a `rate` action may set (ms) — a guard so a hosted session
	 *  can't be driven faster than one tick per {@value} ms, keeping the server responsive. */
	static final long MIN_TICK_RATE_MILLIS = 2000;

	@McpTool(name = "control_session",
			description = "Control a session's run loop (admin only): action = pause | resume | step | "
					+ "rate | stop. 'step' advances `value` ticks (default 1); 'rate' sets the tick "
					+ "interval in ms (`value`), floored at 2000 ms (2 s) — faster is rejected up to the floor.")
	public ControlResult controlSession(
			@McpToolParam(description = "Session id", required = true) String sessionId,
			@McpToolParam(description = "pause | resume | step | rate | stop", required = true)
			String action,
			@McpToolParam(description = "step count (for 'step') or ms interval (for 'rate', min 2000)",
					required = false) Long value) {
		authz.requireAdmin();
		HostedSession hs = require(sessionId);
		switch (action == null ? "" : action) {
			case "pause" -> hs.pause();
			case "resume" -> hs.resume();
			case "step" -> hs.step(value != null ? value.intValue() : 1);
			// clamp UP to the floor: never let MCP drive a session faster than one tick / 2 s
			case "rate" -> hs.setTickRateMillis(Math.max(MIN_TICK_RATE_MILLIS, value != null ? value : 3000));
			case "stop" -> hs.stop();
			default -> throw new IllegalArgumentException("unknown action: " + action
					+ " (pause|resume|step|rate|stop)");
		}
		return new ControlResult(hs.id(), hs.clock().name(), hs.outcome().name(), hs.tick());
	}

	@McpTool(name = "submit_command",
			description = "Submit a player command to a session (admin only). Today: type 'setTaxRate', "
					+ "lever 'bankProfit' or 'nobleIncome', rate in [0,1]. Tick-stamped to the next tick "
					+ "unless a later `tick` is given (never retro-mutates the in-flight day).")
	public CommandResult submitCommand(
			@McpToolParam(description = "Session id", required = true) String sessionId,
			@McpToolParam(description = "Command type (currently 'setTaxRate')", required = true)
			String type,
			@McpToolParam(description = "Lever: 'bankProfit' or 'nobleIncome'", required = true)
			String lever,
			@McpToolParam(description = "Tax rate in [0,1]", required = true) double rate,
			@McpToolParam(description = "Colony name to act on; omit in a single-colony session to "
					+ "move them all", required = false) String colony,
			@McpToolParam(description = "Apply tick (defaults to now+1)", required = false) Long tick) {
		authz.requireAdmin();
		HostedSession hs = require(sessionId);
		if (!"setTaxRate".equals(type))
			throw new IllegalArgumentException("unknown command type: " + type);
		SetTaxRateCommand.Lever l = parseLever(lever);
		if (l == null)
			throw new IllegalArgumentException("unknown lever: " + lever + " (bankProfit|nobleIncome)");
		if (!(rate >= 0 && rate <= 1))
			throw new IllegalArgumentException("rate must be in [0,1], got " + rate);
		// an agent names its colony like any other player would; unknown names are rejected rather
		// than silently moving nothing (docs/spectator-lobby.md Phase 2)
		String target = colony == null || colony.isBlank() ? null : colony;
		if (target != null && hs.colonyByName(target) == null)
			throw new IllegalArgumentException("no colony " + target + " in session " + sessionId);
		long next = hs.tick() + 1;
		long applyTick = Math.max(next, tick != null ? tick : next);
		hs.submit(new SetTaxRateCommand(applyTick, target, l, rate));
		return new CommandResult(true, l.name(), rate, applyTick);
	}

	private HostedSession require(String sessionId) {
		HostedSession hs = host.get(sessionId);
		if (hs == null)
			throw new IllegalArgumentException("no session " + sessionId);
		return hs;
	}

	// map the wire lever name (camelCase, or the enum name) to the enum — mirrors SessionController
	private static SetTaxRateCommand.Lever parseLever(String wire) {
		if (wire == null)
			return null;
		return switch (wire) {
			case "bankProfit", "BANK_PROFIT" -> SetTaxRateCommand.Lever.BANK_PROFIT;
			case "nobleIncome", "NOBLE_INCOME" -> SetTaxRateCommand.Lever.NOBLE_INCOME;
			default -> null;
		};
	}

	/**
	 * Result of {@link #createSession}: the session id and clock/outcome, plus what it founded —
	 * the scenario's {@code shape} and {@code balanceProfile}, and the {@code contentVersion} the
	 * session was founded against ({@code null} = unknown, the classpath source). The last three let
	 * the caller confirm it got the tuning it meant, and record the version a replay needs.
	 */
	public record CreateResult(String id, String clockState, String outcome, String shape,
			String balanceProfile, String contentVersion) {}

	/** Result of {@link #controlSession}. */
	public record ControlResult(String id, String clockState, String outcome, long tick) {}

	/** Result of {@link #submitCommand}. */
	public record CommandResult(boolean accepted, String lever, double rate, long tick) {}
}
