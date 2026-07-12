package com.civstudio.simulation;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.civstudio.agent.SettlerCaravan;
import com.civstudio.bank.Bank;
import com.civstudio.settlement.Settlement;

/**
 * Shared invariant checks for the simulation smoke tests. The core "healthy
 * finished colony" definition — population sustained, prices finite and
 * positive, banks finite intermediaries — lives in the production helper {@link
 * ColonyHealth}, so it is defined once; this class adapts it to JUnit assertions
 * and layers the test-only check that the default runs' banks stay finite
 * intermediaries on top.
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

	/**
	 * Assert the colony <b>departed as a Caravan</b>: it ran to completion (no
	 * {@code -ea} invariant thrown — checked by the caller) and, once its workforce
	 * drained below the dissolution floor, crossed the {@code HOLDING → CARAVAN} hinge
	 * — its settled life ended and the survivors took to the road as a wandering band
	 * (see {@code docs/caravan.md}). This is the contract for every ruler-bearing colony
	 * now that the labor force is founded and replaced by promotion from a finite peasant
	 * pool: once the reserve drains, deaths go unreplaced, the workforce falls past the
	 * floor, and the colony dissolves rather than simply vanishing.
	 *
	 * @param h
	 *            a finished harness
	 */
	static void assertDepartedAsCaravan(SimulationHarness h) {
		Settlement colony = h.getColony();
		assertTrue(colony.isDead(),
				"expected the colony's settled life to have ended (it dissolved)");
		SettlerCaravan band = colony.getDepartedBand();
		assertNotNull(band, "a dissolved ruler-bearing colony departs as a Caravan");
		assertTrue(band.getFollowing().isWandering(),
				"the departed band's following wanders");
		assertTrue(band.getFollowing().size() > 0,
				"the band carries its surviving people, got "
						+ band.getFollowing().size());
		assertTrue(Double.isFinite(band.getHoard()) && band.getHoard() > 0,
				"the band carries a conserved hoard, got " + band.getHoard());
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
