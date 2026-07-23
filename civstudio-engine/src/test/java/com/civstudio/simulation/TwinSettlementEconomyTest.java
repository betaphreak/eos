package com.civstudio.simulation;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.civstudio.geo.Province;
import com.civstudio.settlement.GameSession;
import com.civstudio.settlement.ProvincePlotPool;
import com.civstudio.settlement.Settlement;

/**
 * Smoke test for {@link TwinSettlementEconomy}, the worked example of two
 * settlements sharing <b>one</b> province. Upper and Lower are both founded into
 * Dhenijansar and run <b>concurrently</b> — one thread per settlement, coordinated
 * by {@link SessionRunner}'s lockstep day-barrier — claiming from the one shared
 * {@link ProvincePlotPool} (at min-distance-spaced centers) and competing for its
 * 74 plots. Running it end-to-end covers the whole parallel path over a shared
 * mutable resource: the thread-safe pool's concurrent claim/release, the
 * per-colony name/demography isolation, the per-thread log binding, and the
 * Phaser barrier coping with the two colonies dissolving at different times
 * ({@link SessionRunner} rethrows any thread failure). The run returns the Upper
 * harness (Lower is also built and run on its own thread).
 */
@Tag("full-run")
class TwinSettlementEconomyTest {

	@Test
	void bothSettlementsShareDhenijansarRunningConcurrentlyToCompletion() {
		SimulationHarness upper = assertDoesNotThrow(TwinSettlementEconomy::run);

		// the returned colony was founded into Dhenijansar (its climate / latitude)
		Settlement colony = upper.getColony();
		assertEquals("Dhenijansar", colony.getProvince().name());
		assertEquals(23.16, colony.getLatitude(), 1e-6);

		// the default tiered banking: commoner copper, the ennobled export nobles'
		// silver, the ruler's gold
		assertEquals(3, upper.getBanks().size(),
				"Upper should carry copper, silver and gold banks");

		// like every ruler-bearing colony it founds and replaces from a finite pool,
		// so once the reserve drains it departs as a caravan (ran clean, no -ea trip)
		SimulationAssertions.assertDepartedAsCaravan(upper);

		// both settlements have died, so all their plots were released back to the
		// shared province pool — a dead colony holds no territory (Phase 4a)
		ProvincePlotPool pool = colony.getSession().provincePlotPool(colony.getProvince());
		assertEquals(pool.size(), pool.freeCount(),
				"a dead colony's plots return to the province pool");
	}

	@Test
	void theTwoSettlementsDrawFromOneSharedProvincePool() {
		// both are founded into the same province, so they share its single plot pool
		// (cached per province) — the substrate that lets several settlements coexist
		GameSession s = new GameSession(TwinSettlementEconomy.SEED);
		Province dh = s.getWorldMap().province(TwinSettlementEconomy.DHENIJANSAR);

		ProvincePlotPool pool = s.provincePlotPool(dh);
		assertSame(pool, s.provincePlotPool(dh), "one shared pool per province");
		assertEquals(74, pool.size(), "Dhenijansar's 74 plots are the shared field");
	}
}
