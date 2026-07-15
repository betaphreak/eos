package com.civstudio.settlement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.civstudio.simulation.SimulationConfig;
import com.civstudio.simulation.SimulationHarness;

/**
 * Drives the {@link SettlementTier} growth accumulator (Phase B): a colony seeded low climbs the
 * ladder as development accrues, but stops at its site's {@link Settlement#getMaxTier() ceiling},
 * and the {@link SettlementTier#METROPOLIS} population gate holds back a too-small town.
 */
class SettlementGrowthTest {

	private static final int EARGATE = 2;        // an ordinary (single-urban-plot) site
	private static final int DHENIJANSAR = 4411; // a city_terrain (multi-urban-plot) site

	private static Settlement standardColony(int provinceId) {
		SimulationConfig cfg = SimulationConfig.DEFAULT;
		SimulationHarness h = SimulationHarness.create(cfg, 7654321L, provinceId);
		h.foundStandardColony(i -> cfg.eFirm().savings(), i -> cfg.nFirm().savings(), i -> 15);
		Settlement c = h.getColony();
		c.start();
		return c;
	}

	@Test
	void climbsFromCampAndStopsAtTheSiteCeiling() {
		Settlement c = standardColony(EARGATE);
		assertEquals(SettlementTier.SMALLHOLDING, c.getMaxTier(),
				"an ordinary single-urban-plot site caps at SMALLHOLDING");
		assertTrue(c.totalResidents() > 0, "the founded colony has residents");

		c.setTier(SettlementTier.CAMP); // pretend it founded low (the Phase D behaviour)
		for (int i = 0; i < 8; i++)
			c.newDay();

		assertEquals(SettlementTier.SMALLHOLDING, c.getTier(),
				"it climbs CAMP -> ... -> SMALLHOLDING and stops at its ceiling (never TOWN)");
	}

	@Test
	void metropolisGateHoldsBackASubThousandTown() {
		Settlement c = standardColony(DHENIJANSAR);
		assertEquals(SettlementTier.METROPOLIS, c.getMaxTier(),
				"a city_terrain site can reach METROPOLIS");

		c.setTier(SettlementTier.TOWN);
		int residents = c.totalResidents();
		assertTrue(residents > 0 && residents < SettlementTier.METROPOLIS_POP_GATE,
				"the demo colony is well under the 1000-person metropolis gate");

		for (int i = 0; i < 8; i++)
			c.newDay();

		assertEquals(SettlementTier.TOWN, c.getTier(),
				"development accrues but the sub-1000 population gate blocks METROPOLIS");
		assertTrue(c.getDevelopment() >= SettlementTier.TOWN.upgradeDays(),
				"development cleared TOWN's cost — only the population gate held it back");
	}
}
