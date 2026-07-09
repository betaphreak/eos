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

import com.civstudio.agent.Member;
import com.civstudio.agent.MigrantCaravan;
import com.civstudio.agent.Retinue;
import com.civstudio.bank.Bank;
import com.civstudio.bank.BankConfig;
import com.civstudio.geo.Province;
import com.civstudio.geo.WorldMap;
import com.civstudio.settlement.GameSession;
import com.civstudio.settlement.Settlement;
import com.civstudio.simulation.SimulationConfig;
import com.civstudio.simulation.SimulationHarness;

/**
 * Owns the live {@link HostedSession hosted sessions} of one server process, keyed by
 * {@link SessionSpec#id() session id} — the "many sessions per JVM" registry Phase 0 made
 * safe (each session's log/output is now per-session; see {@code docs/client-server.md}). A
 * client asks for a spec; the host founds the session's world (a standard colony plus, for
 * the demo, six marching caravans), registers it, and hands it back for the transport to
 * subscribe to.
 */
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

	/**
	 * Found and register a session from {@code spec} (idempotent by id: an existing session
	 * with the same id is returned as-is). The session is built but not started — the caller
	 * sets the tick rate and starts it.
	 *
	 * @param spec the founding spec
	 * @return the hosted session
	 */
	public HostedSession create(SessionSpec spec) {
		return sessions.computeIfAbsent(spec.id(), k -> build(spec));
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

	// found the session's world from the spec. Only the caravan demo is wired for Phase A;
	// other scenario ids reuse the same standard-colony founding without the bands.
	private HostedSession build(SessionSpec spec) {
		SimulationConfig cfg = SimulationConfig.DEFAULT;
		// SimulationHarness.create builds the GameSession, founds the colony into the
		// province, and installs the (now per-session) log — see docs/client-server.md
		SimulationHarness h = SimulationHarness.create(cfg, spec.seed(), spec.provinceId());
		h.foundStandardColony(i -> cfg.eFirm().savings(), i -> cfg.nFirm().savings(),
				i -> 15);
		Settlement colony = h.getColony();
		GameSession session = colony.getSession();
		if (SessionSpec.CARAVAN_DEMO.equals(spec.scenario()))
			seedDemoCaravans(session, colony, spec.provinceId());
		return new HostedSession(spec, session, List.of(colony));
	}

	// seed the demo's six directed caravans at the founding province, each marching toward a
	// distant, land-reachable destination so they fan out visibly across the map
	private void seedDemoCaravans(GameSession session, Settlement colony, int start) {
		WorldMap map = session.getWorldMap();
		List<Integer> destinations = distantLandProvinces(map, start, DEMO_CARAVANS);
		for (int i = 0; i < DEMO_CARAVANS; i++) {
			// each band banks its (throwaway) reserve at its own bank off the colony, then
			// takes a leader out of a fresh following and marches
			Bank bank = new Bank(BankConfig.DEFAULT, colony);
			Retinue following = new Retinue(DEMO_BAND_SIZE, bank, colony);
			Member leader = following.promoteHighestSkilled();
			following.stockLarder(DEMO_BAND_LARDER);
			MigrantCaravan band = new MigrantCaravan(leader, following, DEMO_BAND_HOARD,
					start, session);
			// a directed band marches to a fixed destination (see MigrantCaravan.tick); if
			// the map offered fewer distant sites than bands, the extras wander instead
			if (i < destinations.size())
				band.setDestination(destinations.get(i));
			session.addCaravan(band);
		}
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
