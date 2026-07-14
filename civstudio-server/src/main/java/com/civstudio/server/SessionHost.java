package com.civstudio.server;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import com.civstudio.agent.CaravanRole;
import com.civstudio.agent.ExplorerCaravan;
import com.civstudio.agent.MarchingCaravan;
import com.civstudio.agent.Member;
import com.civstudio.agent.MilitaryCaravan;
import com.civstudio.agent.Retinue;
import com.civstudio.agent.SettlerCaravan;
import com.civstudio.agent.WorkerCaravan;
import com.civstudio.bank.Bank;
import com.civstudio.bank.BankConfig;
import com.civstudio.geo.Province;
import com.civstudio.geo.WorldMap;
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
 * client asks for a spec; the host founds the session's world (a standard colony plus, for
 * the demo, six marching caravans), registers it, and hands it back for the transport to
 * subscribe to.
 */
@Component
public final class SessionHost {

	// the demo's six wandering bands
	private static final int DEMO_CARAVANS = 6;

	// settlers per demo band (one becomes the leader; the rest follow and march)
	private static final int DEMO_BAND_SIZE = 10;

	// a generous larder stocked on each demo band so it marches a long way before its food
	// runs out (the demo is about the march; foraging is off, so the larder only depletes)
	private static final double DEMO_BAND_LARDER = 6000;

	// each demo band's carried money (copper)
	private static final double DEMO_BAND_HOARD = 1000;

	// graph distance (in land hops from the founding province) a demo destination must be
	// at least, so the directed bands march visibly across the map rather than settling next
	// door; and the search horizon that bounds the BFS
	private static final int DEMO_MIN_HOPS = 8;
	private static final int DEMO_MAX_HOPS = 40;

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
		Settlement colony = h.getColony();
		// spectator sessions show the district view (docs/district-buildout.md D5): let the hosted
		// colony auto-build its unlocked buildings onto its district plots as it researches. Off by
		// default in the engine (byte-identical headless runs); render-only, so no economic change.
		colony.setAutoBuildDistricts(true);
		GameSession session = colony.getSession();
		if (SessionSpec.CARAVAN_DEMO.equals(spec.scenario()))
			seedDemoCaravans(session, colony, spec.provinceId());
		HostedSession hs = new HostedSession(id, owner, spec, session, List.of(colony), commandStore,
				chatStore);
		// resume: replay any previously-persisted commands (keyed by the surrogate id) into the
		// fresh session's log so state = f(spec, command log) holds across a restart
		for (GameCommand cmd : commandStore.load(id))
			hs.replay(cmd);
		return hs;
	}

	// seed the demo's six directed caravans at the founding province, each marching toward a
	// distant, land-reachable destination so they fan out visibly across the map — one of
	// each caravan role (cycling settler / worker / explorer / military) so the live map
	// shows the flavors side by side
	private void seedDemoCaravans(GameSession session, Settlement colony, int start) {
		WorldMap map = session.getWorldMap();
		List<Integer> destinations = distantLandProvinces(map, start, DEMO_CARAVANS);
		// the explorer is no longer a directed marcher but a food-import levy a colony musters
		// under food pressure (docs/explorer-caravan.md) — it is wired into the live colony in a
		// later phase, so the directed-march demo cycles only the other three flavors for now
		CaravanRole[] roles = { CaravanRole.SETTLER, CaravanRole.WORKER, CaravanRole.MILITARY };
		for (int i = 0; i < DEMO_CARAVANS; i++) {
			// each band banks its (throwaway) reserve at its own bank off the colony, then
			// takes a leader out of a fresh following and marches
			Bank bank = new Bank(BankConfig.DEFAULT, colony);
			Retinue following = new Retinue(DEMO_BAND_SIZE, bank, colony);
			Member leader = following.promoteHighestSkilled();
			following.stockLarder(DEMO_BAND_LARDER);
			MarchingCaravan band = bandForRole(roles[i % roles.length], leader, following,
					start, session);
			// a directed band marches to a fixed destination (see MarchingCaravan.tick); if
			// the map offered fewer distant sites than bands, the extras wander instead
			if (i < destinations.size())
				band.setDestination(destinations.get(i));
			session.addCaravan(band);
		}
	}

	// build a demo band of the given role at the founding province
	private static MarchingCaravan bandForRole(CaravanRole role, Member leader,
			Retinue following, int start, GameSession session) {
		return switch (role) {
			case SETTLER -> new SettlerCaravan(leader, following, DEMO_BAND_HOARD, start, session);
			case WORKER -> new WorkerCaravan(leader, following, DEMO_BAND_HOARD, start, session);
			case MILITARY -> new MilitaryCaravan(leader, following, DEMO_BAND_HOARD, start, session);
			// explorers are mustered from the live colony, not seeded here (docs/explorer-caravan.md)
			case EXPLORER -> throw new UnsupportedOperationException(
					"explorer bands are mustered from a colony, not seeded in the demo");
		};
	}

	// pick up to `n` distant, land-reachable destination provinces, spread out by direction.
	// A breadth-first walk over land neighbours from `start` collects reachable land
	// provinces in increasing-distance order; the far ones (>= DEMO_MIN_HOPS) are sampled
	// evenly so the chosen destinations point the bands different ways.
	private static List<Integer> distantLandProvinces(WorldMap map, int start, int n) {
		Set<Integer> visited = new HashSet<>();
		visited.add(start);
		Deque<Integer> current = new ArrayDeque<>();
		current.add(start);
		List<Integer> reachable = new ArrayList<>(); // land provinces, nearest-first
		List<Integer> distant = new ArrayList<>();    // those at least DEMO_MIN_HOPS away
		for (int depth = 1; depth <= DEMO_MAX_HOPS && !current.isEmpty(); depth++) {
			Deque<Integer> next = new ArrayDeque<>();
			for (int p : current)
				for (int nb : map.neighbors(p)) {
					if (!visited.add(nb))
						continue;
					Province pv = map.province(nb);
					if (!pv.isLand())
						continue; // land routing stays on land — don't cross water
					next.add(nb);
					reachable.add(nb);
					if (depth >= DEMO_MIN_HOPS)
						distant.add(nb);
				}
			current = next;
		}
		// prefer the far sites; fall back to whatever land is reachable on a small map
		List<Integer> pool = distant.size() >= n ? distant : reachable;
		return sampleEvenly(pool, n);
	}

	// take up to n items evenly spaced across the list (directional spread, since BFS order
	// sweeps outward ring by ring)
	private static List<Integer> sampleEvenly(List<Integer> items, int n) {
		if (items.isEmpty() || n <= 0)
			return List.of();
		if (items.size() <= n)
			return List.copyOf(items);
		List<Integer> picked = new ArrayList<>(n);
		for (int i = 0; i < n; i++)
			picked.add(items.get((int) ((long) i * items.size() / n)));
		return picked;
	}
}
