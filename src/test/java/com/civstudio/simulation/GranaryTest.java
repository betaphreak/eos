package com.civstudio.simulation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.civstudio.agent.Granary;
import com.civstudio.market.ConsumerGoodMarket;

/**
 * The ever-normal {@link Granary} ({@code docs/granary.md}) on the
 * standard colony: the ruler-run food buffer that stabilizes the necessity price by
 * buying gluts at the floor and selling into scarcity at the ceiling. Phase-1 coverage
 * over a short pre-collapse horizon — that the granary is part of the standard founding,
 * that it actually trades (banks the early food surplus), that its cash P&L is
 * reconciled into the ruler's treasury (it hoards no idle money), and that it banks in
 * copper without adding a fourth bank.
 */
class GranaryTest {

	@Test
	void granaryBanksTheEarlyFoodSurplusAndReconcilesToTheRuler() {
		// a standard colony on a short horizon (it would not collapse this soon); the
		// founding food sector over-supplies early, so the necessity price runs below the
		// granary's floor and the granary buys the surplus into its reserve
		SimulationConfig cfg = SimulationConfig.DEFAULT.toBuilder()
				.durationYears(1).build();
		SimulationHarness h = SimulationHarness.create(cfg, 7654321);
		h.foundStandardColony(i -> cfg.eFirm().savings(),
				i -> cfg.nFirm().savings(), i -> 15);

		Granary granary = h.getGranary();
		assertNotNull(granary, "a standard colony is founded with a granary");

		h.run();

		// the granary intervened: it bought the early glut into its strategic reserve
		assertTrue(granary.getTotalBought() > 0,
				"the granary should have bought the early food surplus, got "
						+ granary.getTotalBought());
		assertTrue(granary.getStock() > 0,
				"the granary should be holding a food reserve");

		// its cash P&L is the crown's: each step it reconciles its account to ~0 against
		// the gold treasury, so it hoards no idle money (the buy-low/sell-high value lands
		// in the ruler's treasury instead)
		assertEquals(0, granary.getCash(), 1.0,
				"the granary reconciles its cash to the ruler, holding ~0 itself");

		// the necessity price stayed bounded over the (healthy) horizon — no crash, no
		// skyrocket
		ConsumerGoodMarket nMkt = h.getNecessityMkt();
		double price = nMkt.getLastMktPrice();
		assertTrue(price > 0 && price < 10 * nMkt.getInitialPrice(),
				"the necessity price should stay in a sane band, was " + price);

		// the granary banks in copper — it adds no fourth bank to the three-currency
		// hierarchy (commoners copper, nobles silver, ruler gold)
		assertEquals(3, h.getBanks().size(),
				"expected three banks (copper, silver, gold)");
	}
}
