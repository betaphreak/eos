package eos.simulation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import eos.agent.PeasantPool;

/**
 * Phase 3 of the peasant pool ({@code docs/peasant-pool.md}): promotion. A dead
 * laborer is replaced by the ruler elevating the ablest peasant from the pool into
 * a fresh laborer household. This checks the mechanism fires — peasants <em>are</em>
 * promoted — and the documented consequence: with only a finite reserve and no
 * inflow, promotion cannot sustain the colony. The reserve drains, deaths go
 * unreplaced, the shrinking workforce produces less food, and the colony
 * <b>collapses</b> (the refill that would prevent this is a later phase).
 */
class MeritocraticEconomyTest {

	@Test
	void promotionFiresThenTheFiniteReserveDrainsAndTheColonyCollapses() {
		SimulationHarness h = MeritocraticEconomy.run();
		PeasantPool pool = h.getPeasantPool();
		assertNotNull(pool, "MeritocraticEconomy should create a peasant pool");

		// social mobility fired: peasants were promoted into laborer households
		assertTrue(pool.getPromotedCount() > 0,
				"expected peasants to have been promoted, got "
						+ pool.getPromotedCount());

		// the full three-currency hierarchy is present (commoners copper, nobles
		// silver, ruler gold)
		assertEquals(3, h.getBanks().size(),
				"expected three banks (copper, silver, gold)");

		// the finite reserve cannot sustain the colony: it drains, deaths go
		// unreplaced, and the colony collapses (no inflow yet)
		assertTrue(h.getColony().isDead(),
				"expected the colony to have collapsed once the reserve ran dry");
		assertEquals(0, h.currentLaborerCount(),
				"a collapsed colony has no laborers left");
	}
}
