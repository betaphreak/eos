package eos.simulation;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import eos.agent.PeasantPool;

/**
 * Phase 3 of the peasant pool ({@code docs/peasant-pool.md}): promotion. A dead
 * laborer is replaced by the ruler elevating the ablest peasant from the pool into
 * a fresh laborer household. This checks the mechanism fires — peasants <em>are</em>
 * promoted — and the documented consequence: with only a finite reserve and no
 * inflow, the labor force <b>declines</b> once the pool drains (promotion alone
 * cannot sustain the colony; the refill is a later phase). The run is short so the
 * colony is still alive at the horizon.
 */
class MeritocraticEconomyTest {

	@Test
	void promotionReplacesDeadLaborersAndTheReserveDrains() {
		SimulationHarness h = MeritocraticEconomy.run();
		PeasantPool pool = h.getPeasantPool();
		assertNotNull(pool, "MeritocraticEconomy should create a peasant pool");

		// social mobility fired: peasants were promoted into laborer households
		assertTrue(pool.getPromotedCount() > 0,
				"expected peasants to have been promoted, got "
						+ pool.getPromotedCount());

		// the finite reserve cannot sustain the labor force, so it declines (the
		// depletion a later phase's refill will address) — but the colony is still
		// alive at this short horizon
		long pop = h.currentLaborerCount();
		assertTrue(pop < h.getCfg().numLaborers(),
				"expected the labor force to have declined from "
						+ h.getCfg().numLaborers() + ", got " + pop);
		assertTrue(pop > 0, "the colony should still be alive, got " + pop);

		// no price runaway over the short horizon
		double np = h.getNecessityMkt().getLastMktPrice();
		assertTrue(Double.isFinite(np) && np > 0,
				"necessity price should stay finite and positive, got " + np);
	}
}
