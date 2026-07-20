package com.civstudio.settlement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.civstudio.simulation.SimulationConfig;
import com.civstudio.simulation.SimulationHarness;

/**
 * Drives the {@link SettlementTier} food-box growth (Phase B): a well-fed colony climbs the
 * ladder but is capped by its site ceiling, its size&sup2; household floor and the {@link
 * SettlementTier#METROPOLIS} population gate; a starving colony descends a rung.
 */
class SettlementGrowthTest {

	private static final int EARGATE = 2;        // an ordinary (single-urban-plot) site
	private static final int DHENIJANSAR = 4411; // a city_terrain (multi-urban-plot) site

	private static Settlement standardColony(int provinceId) {
		SimulationConfig cfg = SimulationConfig.DEFAULT;
		SimulationHarness h = SimulationHarness.create(cfg, 7654321L, provinceId);
		h.foundStandardColony();
		Settlement c = h.getColony();
		c.start();
		return c;
	}

	// the highest rung a colony with `hh` households can climb to, bounded by the site ceiling
	// (food and — below METROPOLIS — the population gate not being the limiter).
	private static SettlementTier reachableTop(int hh, SettlementTier maxTier) {
		SettlementTier top = SettlementTier.CAMP;
		for (SettlementTier t : SettlementTier.values()) {
			if (t.ordinal() > maxTier.ordinal() || hh < t.minHouseholds())
				break;
			top = t;
		}
		return top;
	}

	@Test
	void wellFedColonyClimbsToItsHouseholdAndSiteCeiling() {
		Settlement c = standardColony(EARGATE);
		assertEquals(SettlementTier.SMALLHOLDING, c.getMaxTier(),
				"an ordinary single-urban-plot site caps at SMALLHOLDING");
		int hh = c.householdCount();
		assertTrue(hh >= SettlementTier.SMALLHOLDING.minHouseholds(),
				"a standard colony has enough households to reach its SMALLHOLDING ceiling");

		c.setTier(SettlementTier.CAMP);   // pretend it founded low (the Phase D behaviour)
		c.setFoodBox(10_000_000);         // ample food, so only households / the site ceiling limit it
		c.newDay();

		assertEquals(reachableTop(hh, c.getMaxTier()), c.getTier(),
				"it climbs from CAMP to its household/site ceiling (SMALLHOLDING)");
		assertTrue(c.getTier().atLeast(SettlementTier.COTTAGE), "food-fuelled growth climbs past CAMP");
	}

	@Test
	void metropolisPopulationGateHoldsBackAWellFedTown() {
		Settlement c = standardColony(DHENIJANSAR);
		assertEquals(SettlementTier.METROPOLIS, c.getMaxTier(),
				"a city_terrain site can reach METROPOLIS");

		c.setTier(SettlementTier.TOWN);
		c.setFoodBox(10_000_000); // food is not the limiter
		int residents = c.totalResidents();
		c.newDay();

		if (residents < SettlementTier.METROPOLIS_POP_GATE)
			assertEquals(SettlementTier.TOWN, c.getTier(),
					"the sub-1000 population gate blocks METROPOLIS despite ample food");
		else
			assertEquals(SettlementTier.METROPOLIS, c.getTier(),
					"a >=1000-person town with ample food becomes a METROPOLIS");
	}

	@Test
	void aStarvingBootedSettlementDescendsToItsEconomyFloorSmallholding() {
		Settlement c = standardColony(DHENIJANSAR); // founds MATURE at METROPOLIS (a ruler economy)
		assertTrue(c.getTier().atLeast(SettlementTier.TOWN), "the demo city founds district-bearing");

		c.setFoodBox(-10_000_000); // a deep, sustained food deficit — outruns any day's surplus
		c.newDay();

		// a colony that has BOOTED its ruler economy floors its starvation-descent at SMALLHOLDING —
		// the sub-SMALLHOLDING tiers are only for un-booted foraging camps (Phase E), so it never
		// starves down into an incoherent "ruler at a camp tier" state. If it cannot even sustain a
		// Smallholding, its workforce drains and it dissolves into a caravan (SettlementLifecycle).
		assertEquals(SettlementTier.SMALLHOLDING, c.getTier(),
				"a starving booted colony descends to its economy floor (SMALLHOLDING), not a camp tier");
		assertEquals(0.0, c.getFoodBox(), 1e-9, "the food box floors at 0 at the economy floor");
	}

	@Test
	void foodWastageBanksSmallSurplusesFullyButSaturatesLargeOnes() {
		// a deficit is never wasted
		assertEquals(-5.0, FoodEconomy.applyFoodWastage(-5, 10), 1e-9);
		// surplus up to consumption banks in full
		assertEquals(8.0, FoodEconomy.applyFoodWastage(8, 10), 1e-9);
		// a huge surplus saturates toward the asymptote (start + 1/growthFactor = 10 + 20 = 30)
		double huge = FoodEconomy.applyFoodWastage(1_000_000, 10);
		assertTrue(huge > 29 && huge < 31, "a huge daily surplus saturates near the asymptote, not banks in full");
	}
}
