package eos.simulation;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eos.bank.Bank;

/**
 * Shared invariant checks for the simulation smoke tests. The core "healthy
 * finished colony" definition — population sustained, prices finite and
 * positive, banks finite intermediaries — lives in the production helper {@link
 * ColonyHealth} (also used by {@link ScaleSweep}), so it is defined once; this
 * class adapts it to JUnit assertions and layers the test-only check that the
 * default runs' banks stay finite intermediaries on top.
 */
final class SimulationAssertions {

	// the closed default runs sustain a large population (the founding 450
	// laborers, replaced 1:1 as heads die of old age)
	private static final long MIN_DEFAULT_LABORERS = 401;

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

		// additionally, every default bank stays a finite intermediary. Equity is no
		// longer pinned at zero: every settlement now has an export sector (a {@link
		// eos.agent.firm.StrategicFirm}) that injects its export earnings into the
		// bank holding its account, so that bank's equity grows over the run. We
		// therefore require equity to be finite (and the loan pool too), not zero.
		assertTrue(!h.getBanks().isEmpty(), "expected at least one bank");
		for (Bank bank : h.getBanks()) {
			assertTrue(Double.isFinite(bank.getEquity()),
					"bank equity not finite: " + bank.getEquity());
			assertTrue(Double.isFinite(bank.getTotalLoan()),
					"totalLoan not finite: " + bank.getTotalLoan());
		}
	}
}
