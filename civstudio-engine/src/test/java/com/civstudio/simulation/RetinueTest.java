package com.civstudio.simulation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.civstudio.agent.Member;
import org.junit.jupiter.api.Test;

import com.civstudio.agent.SettlerCaravan;
import com.civstudio.agent.Retinue;
import com.civstudio.good.RationSize;

/**
 * The peasant pool ({@code docs/peasant-pool.md}) on the standard colony: the ruler
 * founds the labor force by <b>promotion</b> from a finite pool it <b>feeds</b>.
 * This consolidates the pool-specific coverage that previously lived in the
 * (now-removed) {@code PeasantEconomy}/{@code MeritocraticEconomy} smoke tests —
 * that promotion actually fires and that relief is billed to the ruler — onto the
 * canonical {@code foundStandardColony} path, over a short pre-collapse horizon.
 */
class RetinueTest {

	@Test
	void laborForceIsPromotedFromThePoolAndReliefIsBilled() {
		// a standard colony on a short horizon (it would not collapse this soon)
		SimulationConfig cfg = SimulationConfig.DEFAULT.toBuilder()
				.durationYears(1).build();
		SimulationHarness h = SimulationHarness.create(cfg, 7654321);
		h.foundStandardColony();

		Retinue retinue = h.getRetinue();
		assertNotNull(retinue, "a standard colony founds its labor force from a pool");

		// the pool opens with a deep larder (10× the per-peasant buffer — see
		// Retinue.BUFFER_DAYS), so it would live off that food for over a year before
		// touching the market. To exercise the relief-funding path on this short horizon,
		// draw the larder down near the buy-threshold (leaving ~1 day per peasant) so the
		// pool must restock on the market — which is what bills the ruler.
		retinue.drawPromotionStock(retinue.getLarder() - retinue.size());
		h.run();

		// social mobility fired: the ruler promoted peasants into laborer households
		// (the day-0 founding promotion, plus any death replacements)
		assertTrue(retinue.getPromotedCount() > 0,
				"expected peasants to have been promoted, got "
						+ retinue.getPromotedCount());

		// the standing reserve was fed: the pool bought necessity and billed the ruler
		assertTrue(retinue.getTotalBilledToRuler() > 0,
				"the ruler should have been billed for peasant relief");

		// the full three-currency hierarchy is present (commoners copper, the
		// ennobled export aristocracy silver, the ruler gold)
		assertEquals(3, h.getBanks().size(),
				"expected three banks (copper, silver, gold)");
	}

	@Test
	void detachedRetinueWandersOnTheSnackRationWithoutMarketOrPatron() {
		SimulationConfig cfg = SimulationConfig.DEFAULT.toBuilder()
				.durationYears(1).build();
		SimulationHarness h = SimulationHarness.create(cfg, 7654321);
		h.foundStandardColony();
		Retinue retinue = h.getRetinue();
		assertNotNull(retinue);

		// settled by default: the poor-relief ration, not wandering
		assertFalse(retinue.isWandering(), "a founded retinue starts settled");
		assertEquals(RationSize.SIMPLE, retinue.getRation());

		// forming a Caravan around it detaches it into the wandering mode
		Member leader = h.getColony().getRuler().getHead();
		new SettlerCaravan(leader, retinue, 0, 51.5, -0.13);
		assertTrue(retinue.isWandering(), "a caravan's following wanders");
		assertEquals(RationSize.SNACK, retinue.getRation());

		// a wandering step eats the lean SNACK ration from the carried larder (the
		// larder is seeded large, so no one starves), bills no patron, and touches no
		// market
		double billedBefore = retinue.getTotalBilledToRuler();
		retinue.act();
		assertEquals(retinue.size() * RationSize.SNACK.perDay(),
				retinue.getLastConsumed(), 1e-9,
				"wandering retinue eats the SNACK ration from its larder");
		assertEquals(billedBefore, retinue.getTotalBilledToRuler(), 1e-9,
				"a wandering retinue bills no ruler");
	}
}
