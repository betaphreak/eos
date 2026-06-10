package eos.simulation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eos.bank.Bank;
import eos.market.ConsumerGoodMarket;

/**
 * Shared invariant checks for the simulation smoke tests. A healthy run keeps
 * (almost) all laborers alive, leaves market prices finite and positive, and
 * leaves each default bank a finite, zero-profit intermediary.
 */
final class SimulationAssertions {

	private SimulationAssertions() {
	}

	/** Assert the post-run economy is healthy. */
	static void assertHealthy(SimulationHarness h) {
		// the economy did not collapse: the vast majority of laborers survive
		long alive = h.aliveLaborerCount();
		assertTrue(alive > 400,
				"expected >400 laborers alive, got " + alive);

		// consumer-good prices are finite and positive
		assertFinitePositivePrice(h.getEnjoymentMkt());
		assertFinitePositivePrice(h.getNecessityMkt());

		// every (default) bank is a finite, zero-profit intermediary
		assertTrue(!h.getBanks().isEmpty(), "expected at least one bank");
		for (Bank bank : h.getBanks()) {
			assertEquals(0.0, bank.getEquity(),
					"default bank must make zero profit");
			assertFinite(bank.getTotalDeposit(), "totalDeposit");
			assertFinite(bank.getTotalLoan(), "totalLoan");
			assertFinite(bank.getLoanIR(), "loanIR");
			assertFinite(bank.getDepositIR(), "depositIR");
			assertTrue(bank.getTotalDeposit() > 0,
					"expected positive total deposit");
		}
	}

	private static void assertFinitePositivePrice(ConsumerGoodMarket m) {
		double p = m.getLastMktPrice();
		assertTrue(Double.isFinite(p) && p > 0,
				"expected finite positive price, got " + p);
	}

	private static void assertFinite(double v, String name) {
		assertTrue(Double.isFinite(v), name + " not finite: " + v);
	}
}
