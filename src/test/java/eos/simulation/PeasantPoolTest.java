package eos.simulation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import eos.agent.PeasantPool;

/**
 * The peasant pool ({@code docs/peasant-pool.md}) on the standard colony: the ruler
 * founds the labor force by <b>promotion</b> from a finite pool it <b>feeds</b>.
 * This consolidates the pool-specific coverage that previously lived in the
 * (now-removed) {@code PeasantEconomy}/{@code MeritocraticEconomy} smoke tests —
 * that promotion actually fires and that relief is billed to the ruler — onto the
 * canonical {@code foundStandardColony} path, over a short pre-collapse horizon.
 */
class PeasantPoolTest {

	@Test
	void laborForceIsPromotedFromThePoolAndReliefIsBilled() {
		// a standard colony on a short horizon (it would not collapse this soon)
		SimulationConfig cfg = SimulationConfig.DEFAULT.toBuilder()
				.durationYears(1).build();
		SimulationHarness h = SimulationHarness.create(cfg, 7654321);
		h.foundStandardColony(i -> cfg.eFirm().savings(),
				i -> cfg.nFirm().savings(), i -> 15);
		h.run();

		PeasantPool pool = h.getPeasantPool();
		assertNotNull(pool, "a standard colony founds its labor force from a pool");

		// social mobility fired: the ruler promoted peasants into laborer households
		// (the day-0 founding promotion, plus any death replacements)
		assertTrue(pool.getPromotedCount() > 0,
				"expected peasants to have been promoted, got "
						+ pool.getPromotedCount());

		// the standing reserve was fed: the pool bought necessity and billed the ruler
		assertTrue(pool.getTotalBilledToRuler() > 0,
				"the ruler should have been billed for peasant relief");

		// the full three-currency hierarchy is present (commoners copper, the
		// ennobled export aristocracy silver, the ruler gold)
		assertEquals(3, h.getBanks().size(),
				"expected three banks (copper, silver, gold)");
	}
}
