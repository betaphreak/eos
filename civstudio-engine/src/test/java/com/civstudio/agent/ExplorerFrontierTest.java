package com.civstudio.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.civstudio.geo.WorldMap;
import com.civstudio.settlement.GameSession;
import com.civstudio.settlement.Settlement;
import com.civstudio.simulation.SimulationConfig;
import com.civstudio.simulation.SimulationHarness;
import com.civstudio.util.Rng;

/**
 * <b>Frontier-seeking</b> (docs/explorer-caravan.md): an explorer's OUTBOUND leg heads for the
 * nearest ground <i>no band has set foot on</i>, replacing the random walk — a uniform pick among
 * the nearest land neighbours, which sent a levy back over ground it had just trailed as readily as
 * into new country, because nothing told it where it had been.
 * <p>
 * The frontier signal is {@code GameSession.hasPlotPool}: a plot pool is materialised precisely
 * where someone reached into the ground (a band camped or trailed, a colony was founded), so its
 * <b>absence proves the province is untouched</b> — the only side the search needs certainty on. A
 * province that <i>has</i> a pool may merely have been founded on rather than walked; that is fine,
 * since a settled province is not a frontier either.
 * <p>
 * This test lives in {@code com.civstudio.agent} so it can call the protected {@code
 * chooseWanderTarget} directly. That matters: an earlier version of this test drove a whole march
 * and asserted the band rarely re-trod its own path — and it <b>passed against the old random walk
 * too</b>, proving nothing. Asserting the choice itself is what actually discriminates.
 * <p>
 * NB discovery stays <b>opportunistic</b>: a band forages what its route crosses. A band cannot
 * target a bonus it has not discovered yet, so frontier-seeking picks the <i>direction</i>, not the
 * prize.
 */
class ExplorerFrontierTest {

	private static final int DHENIJANSAR = 4411;

	private static SimulationHarness colony() {
		SimulationConfig cfg = SimulationConfig.DEFAULT.toBuilder().durationYears(1).build();
		SimulationHarness h = SimulationHarness.create(cfg, 7654321, DHENIJANSAR);
		h.foundStandardColony(i -> cfg.eFirm().savings(), i -> cfg.nFirm().savings(), i -> 15);
		return h;
	}

	// a levy mustered from the colony's pool, provisioned for a long trip
	private static ExplorerCaravan levy(SimulationHarness h) {
		Settlement colony = h.getColony();
		LocalDate today = colony.getDate();
		List<Member> draftees = new ArrayList<>();
		for (Member m : h.getRetinue().getMembers())
			if (m.isAdult(today)) {
				draftees.add(m);
				if (draftees.size() == 5)
					break;
			}
		assertEquals(5, draftees.size(), "the founded pool has adults to draft");
		return ExplorerCaravan.muster(colony,
				draftees, draftees.size() * MarchingCaravan.WANDERING_RATION.perDay() * 200);
	}

	// the band's home province's land neighbours
	private static List<Integer> landNeighbours(GameSession session, int province) {
		WorldMap map = session.getWorldMap();
		List<Integer> out = new ArrayList<>();
		for (int nb : map.neighbors(province))
			if (map.province(nb).isLand())
				out.add(nb);
		return out;
	}

	/**
	 * <b>The discriminating case.</b> With the band's immediate neighbours split into walked and
	 * unwalked, every target it picks is an <b>unwalked</b> one — never the explored ground beside
	 * it. A random walk picks uniformly among all of them, so it fails this outright.
	 */
	@Test
	void theBandTargetsUnexploredGroundOverExploredGroundBesideIt() {
		SimulationHarness h = colony();
		Settlement colony = h.getColony();
		GameSession session = colony.getSession();
		int home = colony.getProvince().id();

		List<Integer> neighbours = landNeighbours(session, home);
		assertTrue(neighbours.size() >= 3,
				"the test needs a home with several land neighbours to split (has "
						+ neighbours.size() + ")");

		// mark HALF the neighbours explored, by materialising their pools (what a band that camped
		// or trailed through them would have done); the rest stay virgin
		Set<Integer> explored = new HashSet<>();
		for (int i = 0; i < neighbours.size() / 2; i++) {
			int nb = neighbours.get(i);
			session.provincePlotPool(session.getWorldMap().province(nb));
			explored.add(nb);
		}
		assertFalse(explored.isEmpty(), "some neighbours are now explored");
		Set<Integer> virgin = new HashSet<>(neighbours);
		virgin.removeAll(explored);
		assertFalse(virgin.isEmpty(), "...and some are still virgin");

		// ask for a target many times: the RNG only breaks ties WITHIN the nearest unexplored
		// layer, so no draw may ever land on explored ground while virgin ground sits beside it
		ExplorerCaravan band = levy(h);
		Rng rng = session.getBandRng();
		for (int i = 0; i < 60; i++) {
			OptionalInt target = band.chooseWanderTarget(rng);
			assertTrue(target.isPresent(), "the band always has somewhere to go");
			int t = target.getAsInt();
			assertFalse(explored.contains(t),
					"the band picked ground it had already walked while unwalked ground lay just as "
							+ "close — that is the random walk, not frontier-seeking (picked " + t
							+ ", explored=" + explored + ", virgin=" + virgin + ")");
			assertTrue(virgin.contains(t),
					"the nearest frontier is an unwalked neighbour, so that is what it should pick "
							+ "(picked " + t + ", virgin=" + virgin + ")");
		}
	}

	/**
	 * Ringed by walked ground, the band is <b>not stranded</b>: it falls back to the old
	 * any-land-neighbour pick rather than returning nothing and freezing. This is what keeps a levy
	 * deep inside settled country moving.
	 */
	@Test
	void aBandRingedByExploredGroundFallsBackRatherThanFreezing() {
		SimulationHarness h = colony();
		Settlement colony = h.getColony();
		GameSession session = colony.getSession();
		int home = colony.getProvince().id();

		// explore the whole neighbourhood: every land neighbour, and every one of THEIR neighbours,
		// so the nearest frontier is far enough out that the fallback is what answers
		List<Integer> neighbours = landNeighbours(session, home);
		for (int nb : neighbours) {
			session.provincePlotPool(session.getWorldMap().province(nb));
			for (int nn : landNeighbours(session, nb))
				session.provincePlotPool(session.getWorldMap().province(nn));
		}

		ExplorerCaravan band = levy(h);
		Rng rng = session.getBandRng();
		OptionalInt target = band.chooseWanderTarget(rng);
		assertTrue(target.isPresent(),
				"ringed by explored ground the band must still find somewhere to go — the fallback");
		assertTrue(session.getWorldMap().province(target.getAsInt()).isLand(),
				"and it is land");
	}

	/**
	 * The frontier test is a <b>read</b>, not a {@code computeIfAbsent}: asking whether a province
	 * is explored must not explore it. Otherwise the BFS would generate the plot field of every
	 * province it walks past — the expensive thing the signal exists to avoid.
	 */
	@Test
	void askingWhetherAProvinceIsExploredDoesNotExploreIt() {
		SimulationHarness h = colony();
		GameSession session = h.getColony().getSession();
		int home = h.getColony().getProvince().id();

		int virgin = -1;
		for (int nb : landNeighbours(session, home))
			if (!session.hasPlotPool(nb)) {
				virgin = nb;
				break;
			}
		assertTrue(virgin > 0, "the colony has an unexplored land neighbour to test with");

		for (int i = 0; i < 5; i++)
			assertFalse(session.hasPlotPool(virgin),
					"asking must not build the pool it asks about");

		session.provincePlotPool(session.getWorldMap().province(virgin));
		assertTrue(session.hasPlotPool(virgin), "reaching into the ground marks it explored");
	}
}
