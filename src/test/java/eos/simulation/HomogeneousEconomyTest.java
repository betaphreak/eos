package eos.simulation;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Smoke test for the homogeneous, single-bank simulation. Runs the full
 * colony (assertions enabled via Surefire) and checks it stays healthy. A
 * fresh JVM is forked per test class, so the process-global {@code SimLog}
 * logging handler starts clean.
 */
class HomogeneousEconomyTest {

	@Test
	void runsToCompletionWithHealthyEconomy() {
		SimulationHarness h = assertDoesNotThrow(HomogeneousEconomy::run);
		assertEquals(1, h.getBanks().size(), "HomogeneousEconomy uses one bank");
		SimulationAssertions.assertHealthy(h);
	}
}
