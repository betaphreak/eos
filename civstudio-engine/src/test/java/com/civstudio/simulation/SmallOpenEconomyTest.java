package com.civstudio.simulation;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Smoke test for the small open colony (two banks, the minimum stable firm
 * count, mortality and immigration-driven growth). The shared {@code
 * assertHealthy} does not apply: this run sustains far fewer than 400 laborers
 * and bank A intentionally carries equity (the open-colony money buffer), so
 * the invariants are checked directly here. The defining property is that the
 * population <i>grows</i> past its starting size rather than holding flat.
 */
@Tag("full-run")
class SmallOpenEconomyTest {

	// laborers the run starts with (cf. SmallOpenEconomy's config)
	private static final int INITIAL_LABORERS = 90;

	@Test
	void runsToCompletionWithGrowingPopulation() {
		SimulationHarness h = assertDoesNotThrow(SmallOpenEconomy::run);

		assertEquals(2, h.getBanks().size(), "SmallOpenEconomy uses two banks");

		// core health, with the defining property that immigration grows the
		// population *past* its starting size (mortality's 1:1 replacement alone
		// would only hold it flat): require at least INITIAL_LABORERS + 1 alive.
		// This also covers finite/positive prices and both banks staying finite,
		// active intermediaries — so cross-bank settlement works.
		SimulationAssertions.assertCoreHealthy(h, INITIAL_LABORERS + 1);
		assertTrue(h.currentLaborerCount() > INITIAL_LABORERS,
				"expected population to grow past " + INITIAL_LABORERS
						+ ", got " + h.currentLaborerCount());
	}
}
