package com.civstudio.simulation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.civstudio.agent.Member;
import com.civstudio.agent.MigrantCaravan;
import com.civstudio.agent.Retinue;
import com.civstudio.bank.Bank;
import com.civstudio.bank.BankConfig;
import com.civstudio.geo.Province;
import com.civstudio.geo.WorldMap;
import com.civstudio.settlement.GameSession;
import com.civstudio.settlement.Settlement;
import com.civstudio.util.Rng;

/**
 * Graph movement and the wander-and-settle decision for a {@link MigrantCaravan}
 * (caravan-trade Phase A, A4). A band anchored to a province moves one hop per day
 * along neighbor edges, and wanders the graph to the nearest viable site — a
 * settleable {@link Province} large enough to found into, not the one it abandoned —
 * where it marks itself ready to settle.
 */
class MigrantCaravanTest {

	// two directly-adjacent LAND provinces (the Hanseatic pair) and a far-away one
	private static final int WITHACEN = 515;
	private static final int HOPESPEAK = 519;
	private static final int DHENIJANSAR = 4411;

	// build an on-graph band of `followers` people anchored at `provinceId`, hosted on a
	// throwaway (never-run) muster colony just to mint its following
	private static MigrantCaravan bandAt(GameSession session, int provinceId,
			int followers) {
		SimulationConfig cfg = SimulationConfig.DEFAULT;
		Settlement muster = session.newSettlement("muster", cfg.startDate(),
				cfg.meanInitAgeYears(), cfg.targetNStock(), cfg.meanSkillMale(),
				cfg.meanSkillFemale(), 0, 0);
		Bank bank = new Bank(BankConfig.DEFAULT, muster);
		Retinue following = new Retinue(followers, bank, muster);
		Member leader = following.promoteHighestSkilled();
		return new MigrantCaravan(leader, following, 1000, provinceId,
				session.getWorldMap());
	}

	@Test
	void movesToANeighbourAndRejectsANonNeighbour() {
		GameSession session = new GameSession(11);
		WorldMap map = session.getWorldMap();
		assertTrue(map.neighbors(WITHACEN).contains(HOPESPEAK),
				"fixture: Withacen and Hopespeak are adjacent");

		MigrantCaravan band = bandAt(session, WITHACEN, 50);
		assertTrue(band.onGraph());

		band.moveTo(HOPESPEAK);
		assertEquals(HOPESPEAK, band.getProvinceId(), "the band moved one hop");
		assertEquals(map.province(HOPESPEAK).latitude(), band.getLatitude(),
				"its coordinates follow the province");

		assertThrows(IllegalArgumentException.class, () -> band.moveTo(DHENIJANSAR),
				"a non-neighbour is rejected");
	}

	@Test
	void offGraphBandCannotMoveOnTheGraph() {
		GameSession session = new GameSession(12);
		Settlement muster = session.newSettlement("muster",
				SimulationConfig.DEFAULT.startDate(), 35, 26, 5, 2, 51.5, -0.13);
		Bank bank = new Bank(BankConfig.DEFAULT, muster);
		Retinue following = new Retinue(5, bank, muster);
		Member leader = following.promoteHighestSkilled();
		MigrantCaravan band = new MigrantCaravan(leader, following, 0, 51.5, -0.13);

		assertFalse(band.onGraph(), "a bare-coordinate band is off the graph");
		assertThrows(IllegalStateException.class, () -> band.moveTo(HOPESPEAK));
	}

	@Test
	void wandersOffTheOriginToAViableSiteAndSettles() {
		GameSession session = new GameSession(99);
		WorldMap map = session.getWorldMap();
		MigrantCaravan band = bandAt(session, WITHACEN, 50);
		Rng rng = session.getBandRng();

		int days = 0;
		while (!band.isReadyToSettle() && days < 2000) {
			band.tick(rng);
			days++;
		}

		assertTrue(band.isReadyToSettle(),
				"the band reached a viable site and settled");
		assertNotEquals(WITHACEN, band.getProvinceId(),
				"it re-founds at a fresh site, not the abandoned origin");
		Province chosen = map.province(band.getProvinceId());
		assertTrue(chosen.isSettleable(), "the chosen site is settleable land");
		assertTrue(chosen.plots() >= Settlement.MIN_FOUNDING_PLOTS,
				"the chosen site has enough plots to found into");
	}

	@Test
	void sessionRunnerAdvancesABandEachDay() {
		// a normal colony on a short horizon, with a wandering band registered to the
		// same session before the run: the day-barrier should tick the band each day
		SimulationConfig cfg =
				SimulationConfig.DEFAULT.toBuilder().durationYears(1).build();
		SimulationHarness h = SimulationHarness.create(cfg, 4242, DHENIJANSAR);
		h.foundStandardColony(i -> cfg.eFirm().savings(),
				i -> cfg.nFirm().savings(), i -> 15);
		GameSession session = h.getColony().getSession();

		MigrantCaravan band = bandAt(session, WITHACEN, 50);
		session.addCaravan(band);

		SessionRunner.runConcurrently(List.of(h));

		assertTrue(band.isReadyToSettle(),
				"the session runner advanced the band to a viable site over the run");
		assertNotEquals(WITHACEN, band.getProvinceId(),
				"the band left its origin province while the colony ran");
	}
}
