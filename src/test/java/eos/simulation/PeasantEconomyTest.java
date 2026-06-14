package eos.simulation;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import eos.agent.PeasantPool;

/**
 * The peasant pool ({@code docs/peasant-pool.md}): the ruler founds and replaces the
 * labor force by promotion from a finite pool it feeds. This checks the relief flows
 * (the ruler is billed) and that, with no inflow yet, the pool drains and the colony
 * ultimately collapses.
 */
class PeasantEconomyTest {

	@Test
	void poolIsFedThenTheColonyCollapses() {
		SimulationHarness h = PeasantEconomy.run();
		PeasantPool pool = h.getPeasantPool();
		assertNotNull(pool, "PeasantEconomy should create a peasant pool");

		// the pool was fed: it bought necessity on the market and billed the ruler
		assertTrue(pool.getTotalBilledToRuler() > 0,
				"the ruler should have been billed for peasant relief");

		// the finite pool drains and the colony collapses
		SimulationAssertions.assertCollapsed(h);
	}
}
