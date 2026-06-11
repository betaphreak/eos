package eos.simulation;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import eos.bank.Bank;
import eos.market.ConsumerGoodMarket;

/**
 * Smoke test for the small open economy (two banks, the minimum stable firm
 * count, mortality and immigration-driven growth). The shared {@code
 * assertHealthy} does not apply: this run sustains far fewer than 400 laborers
 * and bank A intentionally carries equity (the open-economy money buffer), so
 * the invariants are checked directly here. The defining property is that the
 * population <i>grows</i> past its starting size rather than holding flat.
 */
class SmallOpenEconomyTest {

	// laborers the run starts with (cf. SmallOpenEconomy's config)
	private static final int INITIAL_LABORERS = 90;

	@Test
	void runsToCompletionWithGrowingPopulation() {
		SimulationHarness h = assertDoesNotThrow(SmallOpenEconomy::run);

		assertEquals(2, h.getBanks().size(), "SmallOpenEconomy uses two banks");

		// immigration grows the population beyond its starting size (mortality's
		// 1:1 replacement alone would only hold it flat)
		long alive = h.currentLaborerCount();
		assertTrue(alive > INITIAL_LABORERS,
				"expected population to grow past " + INITIAL_LABORERS
						+ ", got " + alive);

		// consumer-good prices stay finite and positive (no runaway)
		assertFinitePositivePrice(h.getEnjoymentMkt());
		assertFinitePositivePrice(h.getNecessityMkt());

		// both banks are active intermediaries, so cross-bank settlement works
		for (Bank bank : h.getBanks()) {
			assertTrue(Double.isFinite(bank.getTotalDeposit())
					&& bank.getTotalDeposit() > 0,
					"expected positive finite total deposit on each bank");
			assertTrue(Double.isFinite(bank.getLoanIR())
					&& Double.isFinite(bank.getDepositIR()),
					"expected finite interest rates on each bank");
		}
	}

	private static void assertFinitePositivePrice(ConsumerGoodMarket m) {
		double p = m.getLastMktPrice();
		assertTrue(Double.isFinite(p) && p > 0,
				"expected finite positive price, got " + p);
	}
}
