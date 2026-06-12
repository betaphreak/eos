package eos.simulation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eos.bank.Bank;
import eos.market.ConsumerGoodMarket;

/**
 * Shared invariant checks for the simulation smoke tests. A healthy run sustains
 * its laborer population, leaves market prices finite and positive, and leaves
 * each default bank a finite, zero-profit intermediary.
 */
final class SimulationAssertions {

	// estate inheritance routes a deceased household's net worth through the
	// bank's equity (in on its death step, back out when the heir is funded);
	// at rest the two net out, so allow only floating-point slack here.
	private static final double EQUITY_EPS = 1e-3;

	private SimulationAssertions() {
	}

	/** Assert the post-run colony is healthy. */
	static void assertHealthy(SimulationHarness h) {
		// the colony did not collapse: the population is sustained. This counts
		// heirs that succeeded the founding cohort (the founders themselves age
		// and die of old age, each replaced by a successor household).
		long alive = h.currentLaborerCount();
		assertTrue(alive > 400,
				"expected >400 laborers alive, got " + alive);

		// consumer-good prices are finite and positive
		assertFinitePositivePrice(h.getEnjoymentMkt());
		assertFinitePositivePrice(h.getNecessityMkt());

		// every (default) bank is a finite, zero-profit intermediary
		assertTrue(!h.getBanks().isEmpty(), "expected at least one bank");
		for (Bank bank : h.getBanks()) {
			assertEquals(0.0, bank.getEquity(), EQUITY_EPS,
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
