package com.civstudio.settlement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

import com.civstudio.geo.Province;

/**
 * Phase-2a checks on the shared, claimable {@link ProvincePlotPool}: a province's
 * land pixels become free, position-bearing plots; the pool is generated once and
 * cached per session; and claim/release moves plot ownership. The pool is pure
 * additive infrastructure here — {@code Settlement} is not yet wired to it — so
 * the rest of the model is unaffected (see {@code docs/province-plots.md}).
 */
class ProvincePlotPoolTest {

	private static final LocalDate START = LocalDate.of(1444, 12, 11);

	private static Province dhenijansar(GameSession s) {
		return s.getWorldMap().findByName("Dhenijansar").orElseThrow();
	}

	@Test
	void poolIsTheProvinceFieldAsFreePositionedPlots() {
		GameSession s = new GameSession(42);
		Province dh = dhenijansar(s);
		ProvincePlotPool pool = s.provincePlotPool(dh);

		assertEquals(dh.plots(), pool.size(), "one plot per province land pixel");
		assertEquals(pool.size(), pool.freeCount(), "all plots start free");
		for (Plot p : pool.plots()) {
			assertTrue(p.x() >= 0 && p.y() >= 0, "plot carries a raster position");
			assertNotNull(p.terrain(), "plot has terrain");
			assertNull(p.owner(), "plot starts unowned");
		}
		// generated once and cached per (session, province)
		assertSame(pool, s.provincePlotPool(dh), "pool is cached");
	}

	@Test
	void poolIsDeterministicAcrossSessionsOfTheSameSeed() {
		ProvincePlotPool a = poolFor(new GameSession(7));
		ProvincePlotPool b = poolFor(new GameSession(7));
		assertEquals(a.size(), b.size());
		for (int i = 0; i < a.size(); i++) {
			Plot pa = a.plots().get(i), pb = b.plots().get(i);
			assertEquals(pa.x(), pb.x());
			assertEquals(pa.y(), pb.y());
			assertSame(pa.plotType(), pb.plotType(), "same relief at " + i);
			assertEquals(pa.terrain().type(), pb.terrain().type(), "same terrain at " + i);
		}
	}

	@Test
	void claimTransfersOwnershipAndReleaseFreesIt() {
		GameSession s = new GameSession(3);
		Province dh = dhenijansar(s);
		Settlement colony = s.newSettlement("Test", START, 30, 26, 5, 2, dh);
		ProvincePlotPool pool = s.provincePlotPool(dh);

		int free0 = pool.freeCount();
		Plot plot = pool.plots().get(0);

		pool.claim(plot, colony);
		assertSame(colony, plot.owner(), "claim transfers ownership");
		assertEquals(free0 - 1, pool.freeCount());
		// a claimed plot cannot be re-claimed
		assertThrows(IllegalArgumentException.class, () -> pool.claim(plot, colony));

		pool.release(plot);
		assertNull(plot.owner(), "release frees the plot");
		assertEquals(free0, pool.freeCount());
	}

	private static ProvincePlotPool poolFor(GameSession s) {
		return s.provincePlotPool(dhenijansar(s));
	}
}
