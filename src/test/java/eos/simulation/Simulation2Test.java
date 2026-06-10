package eos.simulation;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Smoke test for the heterogeneous, single-bank simulation (randomized initial
 * state). Runs the full economy and checks it stays healthy.
 */
class Simulation2Test {

	@Test
	void runsToCompletionWithHealthyEconomy() {
		SimulationHarness h = assertDoesNotThrow(Simulation2::run);
		assertEquals(1, h.getBanks().size(), "Simulation2 uses one bank");
		SimulationAssertions.assertHealthy(h);
	}
}
