package eos.simulation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eos.bank.Bank;

/**
 * Shared invariant checks for the simulation smoke tests. The core "healthy
 * finished colony" definition — population sustained, prices finite and
 * positive, banks finite intermediaries — lives in the production helper {@link
 * ColonyHealth} (also used by {@link ScaleSweep}), so it is defined once; this
 * class adapts it to JUnit assertions and layers the test-only bank
 * zero-profit check for the closed default runs on top.
 */
final class SimulationAssertions {

	// the closed default runs sustain a large population (the founding 450
	// laborers, replaced 1:1 as heads die of old age)
	private static final long MIN_DEFAULT_LABORERS = 401;

	// estate inheritance routes a deceased household's net worth through the
	// bank's equity (in on its death step, back out when the heir is funded);
	// at rest the two net out, so allow only floating-point slack here.
	private static final double EQUITY_EPS = 1e-3;

	private SimulationAssertions() {
	}

	/**
	 * Assert the core colony-health invariants (via {@link ColonyHealth}) hold,
	 * with at least {@code minLaborers} laborers still alive. Used by the runs
	 * whose population floor or bank-equity expectations differ from the closed
	 * default, which layer their own extra checks on top.
	 */
	static void assertCoreHealthy(SimulationHarness h, long minLaborers) {
		String reason = ColonyHealth.diagnose(h, minLaborers);
		assertNull(reason, () -> "unhealthy colony: " + reason);
	}

	/** Assert the post-run closed default colony is healthy. */
	static void assertHealthy(SimulationHarness h) {
		// the colony did not collapse and its banks are finite intermediaries.
		// The population count includes heirs that succeeded the founding cohort
		// (founders age and die of old age, each replaced by a successor).
		assertCoreHealthy(h, MIN_DEFAULT_LABORERS);

		// additionally, every default bank is a zero-profit intermediary with a
		// finite loan pool (ColonyHealth already covers deposits and rates)
		assertTrue(!h.getBanks().isEmpty(), "expected at least one bank");
		for (Bank bank : h.getBanks()) {
			assertEquals(0.0, bank.getEquity(), EQUITY_EPS,
					"default bank must make zero profit");
			assertTrue(Double.isFinite(bank.getTotalLoan()),
					"totalLoan not finite: " + bank.getTotalLoan());
		}
	}
}
