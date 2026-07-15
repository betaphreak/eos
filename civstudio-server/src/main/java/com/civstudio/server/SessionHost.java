package com.civstudio.server;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import com.civstudio.settlement.GameSession;
import com.civstudio.settlement.Settlement;
import com.civstudio.server.chat.ChatStore;
import com.civstudio.server.chat.InMemoryChatStore;
import com.civstudio.server.command.CommandStore;
import com.civstudio.server.command.GameCommand;
import com.civstudio.server.command.NoOpCommandStore;
import com.civstudio.simulation.SimulationConfig;
import com.civstudio.simulation.SimulationHarness;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Owns the live {@link HostedSession hosted sessions} of one server process, keyed by
 * {@link SessionSpec#id() session id} — the "many sessions per JVM" registry Phase 0 made
 * safe (each session's log/output is now per-session; see {@code docs/client-server.md}). A
 * client asks for a spec; the host founds the session's world (a standard colony, which musters
 * its own emergent explorer levies), registers it, and hands it back for the transport to
 * subscribe to.
 */
@Component
public final class SessionHost {

	private final ConcurrentMap<String, HostedSession> sessions = new ConcurrentHashMap<>();

	private final CommandStore commandStore;
	private final ChatStore chatStore;

	/** In-memory only — for constructing a host directly (e.g. in a test), no persistence. */
	public SessionHost() {
		this(new NoOpCommandStore(), new InMemoryChatStore());
	}

	/**
	 * @param commandStore durable command-log storage — a {@link NoOpCommandStore} when no
	 *                     datasource is configured (see {@code PersistenceConfig})
	 * @param chatStore    lobby chat storage — an {@link InMemoryChatStore} when no datasource
	 */
	@Autowired
	public SessionHost(CommandStore commandStore, ChatStore chatStore) {
		this.commandStore = commandStore;
		this.chatStore = chatStore;
	}

	/**
	 * Found and register an <em>unowned</em> (public) session from {@code spec} — the
	 * server-seeded demo and the existing tests. Equivalent to {@link #create(SessionSpec,
	 * String) create(spec, null)}.
	 *
	 * @param spec the founding spec
	 * @return the hosted session
	 */
	public HostedSession create(SessionSpec spec) {
		return create(spec, null);
	}

	/**
	 * Found and register a session from {@code spec} owned by {@code owner} (idempotent by the
	 * derived {@linkplain #sessionKey key}: re-founding the same spec+owner returns the existing
	 * session, so a persisted command log replays onto it — {@code state = f(spec, log)}). Two
	 * different owners founding the same spec get two independent sessions. The session is built
	 * but not started — the caller sets the tick rate and starts it.
	 *
	 * @param spec  the founding spec (the determinism root — ownership is metadata alongside it,
	 *              never part of the spec)
	 * @param owner the owning {@code app_user} id, or {@code null} for an unowned/public session
	 * @return the hosted session
	 */
	public HostedSession create(SessionSpec spec, String owner) {
		String id = sessionKey(spec, owner);
		return sessions.computeIfAbsent(id, k -> build(id, owner, spec));
	}

	/**
	 * The surrogate session id: the spec id for an unowned session (unchanged — the demo keeps
	 * its stable {@code "caravan-demo-<seed>"} id), else the spec id tagged with the owner so
	 * owned runs of the same spec (and different owners' runs) never collide. Kept deterministic
	 * so a re-founded spec+owner resumes its command log; Phase 2's session registry can replace
	 * this with an opaque minted id (see {@code docs/authentication.md}).
	 *
	 * @param spec  the founding spec
	 * @param owner the owning user id, or {@code null}/blank for an unowned/public session
	 * @return the session key
	 */
	public static String sessionKey(SessionSpec spec, String owner) {
		return owner == null || owner.isBlank() ? spec.id() : spec.id() + "@" + owner.trim();
	}

	/** The session with this id, or {@code null}. */
	public HostedSession get(String id) {
		return sessions.get(id);
	}

	/** All hosted sessions. */
	public Collection<HostedSession> list() {
		return List.copyOf(sessions.values());
	}

	/** Stop and remove a session (no-op if absent). */
	public void remove(String id) {
		HostedSession hs = sessions.remove(id);
		if (hs != null)
			hs.stop();
	}

	/** Stop and remove every session — the server-shutdown path. */
	public void stopAll() {
		for (String id : List.copyOf(sessions.keySet()))
			remove(id);
	}

	/**
	 * Graceful shutdown: stop every hosted session when the Spring context closes (a SIGTERM to
	 * the container, or a test context teardown). Stopping a session emits its final snapshot,
	 * which completes any open SSE stream — so the embedded server isn't left waiting on an
	 * open-ended async request. Replaces the old {@code ServerMain} JVM shutdown hook.
	 */
	@PreDestroy
	public void shutdown() {
		stopAll();
	}

	// found the session's world from the spec. Only the caravan demo is wired for Phase A;
	// other scenario ids reuse the same standard-colony founding without the bands.
	private HostedSession build(String id, String owner, SessionSpec spec) {
		SimulationConfig cfg = SimulationConfig.DEFAULT;
		// SimulationHarness.create builds the GameSession, founds the colony into the
		// province, and installs the (now per-session) log — see docs/client-server.md
		SimulationHarness h = SimulationHarness.create(cfg, spec.seed(), spec.provinceId());
		h.foundStandardColony(i -> cfg.eFirm().savings(), i -> cfg.nFirm().savings(),
				i -> 15);
		// a City colony musters winter foraging expeditions by default (docs/explorer-caravan.md),
		// which it drives and the render feed draws on the map; a Village founds none.
		Settlement colony = h.getColony();
		// spectator sessions show the district view (docs/district-buildout.md D5): let the hosted
		// colony auto-build its unlocked buildings onto its district plots as it researches. Off by
		// default in the engine (byte-identical headless runs); render-only, so no economic change.
		colony.setAutoBuildDistricts(true);
		GameSession session = colony.getSession();
		// No bands are hand-seeded any more: a City colony musters its OWN winter foraging explorers
		// (installExplorerProvisioning — the ruler drafts the pool's least-skilled adults each lean
		// season, docs/explorer-caravan.md). Those emergent levies pioneer trails the route layer draws
		// (gap B, docs/route-rendering.md), so the demo needs no directed caravans of its own.
		HostedSession hs = new HostedSession(id, owner, spec, session, List.of(colony), commandStore,
				chatStore);
		// resume: replay any previously-persisted commands (keyed by the surrogate id) into the
		// fresh session's log so state = f(spec, command log) holds across a restart
		for (GameCommand cmd : commandStore.load(id))
			hs.replay(cmd);
		return hs;
	}

}
