package com.civstudio.simulation;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Smoke test for the two-colony {@link HanseaticEconomy}, the worked example of
 * several colonies coexisting in one {@code GameSession} and now running
 * <b>concurrently</b> — one thread per settlement, coordinated by {@link
 * SessionRunner}'s lockstep day-barrier. Exercising it end-to-end covers the
 * whole parallel path: per-colony name/demography isolation, the thread-safe
 * session state, the per-thread log binding, and the Phaser barrier coping with
 * the two colonies dissolving at (independently drawn) different times.
 * <p>
 * Like the other ruler-bearing colonies, each Hanseatic colony founds and
 * replaces its labor force from a finite peasant pool, so it runs clean (no
 * {@code -ea} invariant tripped on either thread — {@link SessionRunner}
 * rethrows any thread failure) and then <b>departs as a Caravan</b> once its
 * reserve drains. The run returns the Lübeck harness (Bad Schwartau is also
 * built and run on its own thread); we assert Lübeck dissolved and carries the
 * default three banks (commoner copper, the ennobled export nobles' silver, the
 * ruler's gold).
 */
class HanseaticEconomyTest {

	@Test
	void bothColoniesRunConcurrentlyToCompletionThenDepart() {
		SimulationHarness lubeck = assertDoesNotThrow(HanseaticEconomy::run);
		assertEquals(3, lubeck.getBanks().size(),
				"Lübeck should carry copper, silver and gold banks");
		SimulationAssertions.assertDepartedAsCaravan(lubeck);
	}
}
