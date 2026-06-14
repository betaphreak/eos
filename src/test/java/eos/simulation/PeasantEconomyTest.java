package eos.simulation;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import eos.agent.PeasantPool;

/**
 * Phase 2 of the peasant pool ({@code docs/peasant-pool.md}): the pool exists and
 * is fed. {@link PeasantEconomy} seeds a pool the ruler feeds; this checks that the
 * relief actually flows (the ruler is billed), that with no inflow the pool drains
 * by old age over the run, and that feeding it does not wreck the colony.
 */
class PeasantEconomyTest {

	@Test
	void poolIsFedAndDrainsWhileTheColonyStaysHealthy() {
		SimulationHarness h = PeasantEconomy.run();
		PeasantPool pool = h.getPeasantPool();
		assertNotNull(pool, "PeasantEconomy should create a peasant pool");

		// the pool was fed: it bought necessity on the market and billed the ruler
		assertTrue(pool.getTotalBilledToRuler() > 0,
				"the ruler should have been billed for peasant relief");

		// with no inflow yet, the pool drains by old-age attrition over the run
		assertTrue(pool.size() < PeasantEconomy.PEASANT_RESERVE,
				"the pool should have drained below its seed size, got " + pool.size());

		// feeding the pool did not starve the colony or blow up prices
		assertTrue(h.currentLaborerCount() >= 0.85 * h.getCfg().numLaborers(),
				"laborer population should be sustained, got "
						+ h.currentLaborerCount());
		double np = h.getNecessityMkt().getLastMktPrice();
		assertTrue(Double.isFinite(np) && np > 0,
				"necessity price should stay finite and positive, got " + np);
	}
}
