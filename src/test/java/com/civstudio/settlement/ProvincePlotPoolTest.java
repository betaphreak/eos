package com.civstudio.settlement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;

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

	@Test
	void provinceFoundedColonyClaimsItsPlotsFromTheSharedPool() {
		GameSession s = new GameSession(7);
		Province dh = dhenijansar(s);
		Settlement colony = s.newSettlement("A", START, 30, 26, 5, 2, dh);
		ProvincePlotPool pool = s.provincePlotPool(dh);
		int free0 = pool.freeCount();

		// genesis-claim a run of occupants (peaks count toward the cap, so the colony may
		// lay more plots than occupants)
		for (int i = 0; i < 12; i++)
			colony.claimPlot(new PlotOccupant() {
			});

		assertFalse(colony.getPlots().isEmpty(), "colony laid plots");
		for (Plot p : colony.getPlots()) {
			assertTrue(p.x() >= 0 && p.y() >= 0, "a claimed plot carries a raster position (pool-sourced)");
			assertSame(colony, p.owner(), "a claimed plot is owned by the colony");
		}
		// every plot the colony holds came out of the pool's free count
		assertEquals(colony.getPlotCount(), free0 - pool.freeCount(),
				"colony plots match the pool's claimed count");

		// a province-less colony does not use the pool — its plots have no raster position
		Settlement bare = s.newSettlement("Bare", START, 30, 26, 5, 2, 51.5074, -0.1278);
		bare.claimPlot(new PlotOccupant() {
		});
		assertEquals(-1, bare.getPlots().get(0).x(), "a province-less plot has no raster position");
	}

	@Test
	void twoSettlementsInOneProvinceClaimSpacedDisjointPlots() {
		GameSession s = new GameSession(11);
		Province dh = dhenijansar(s);
		Settlement upper = s.newSettlement("Upper", START, 30, 26, 5, 2, dh);
		Settlement lower = s.newSettlement("Lower", START, 30, 26, 5, 2, dh);
		ProvincePlotPool pool = s.provincePlotPool(dh);

		// each lays a cluster of plots; both draw from the SAME shared province pool
		for (int i = 0; i < 8; i++)
			upper.claimPlot(new PlotOccupant() {
			});
		for (int i = 0; i < 8; i++)
			lower.claimPlot(new PlotOccupant() {
			});

		// disjoint: every plot is owned by exactly one of them, none shared
		Set<Plot> upperPlots = new HashSet<>(upper.getPlots());
		for (Plot p : upper.getPlots())
			assertSame(upper, p.owner());
		for (Plot p : lower.getPlots()) {
			assertSame(lower, p.owner());
			assertFalse(upperPlots.contains(p), "the two settlements never share a plot");
		}

		// both claims came out of the one shared pool's free count
		assertEquals(pool.size() - upper.getPlotCount() - lower.getPlotCount(), pool.freeCount(),
				"both settlements draw from the same pool");

		// min-distance spacing: Lower's center (its first plot) founds OUTSIDE Upper's
		// footprint — farther from Upper's center than any of Upper's own plots are
		Plot uCenter = upper.getPlots().get(0);
		Plot lCenter = lower.getPlots().get(0);
		long lowerSeparation = sqDist(lCenter, uCenter);
		long upperRadius = 0;
		for (Plot p : upper.getPlots())
			upperRadius = Math.max(upperRadius, sqDist(p, uCenter));
		assertTrue(lowerSeparation > upperRadius,
				"Lower founds spaced outside Upper's footprint (sep=" + lowerSeparation
						+ ", radius=" + upperRadius + ")");
	}

	private static long sqDist(Plot a, Plot b) {
		long dx = a.x() - b.x(), dy = a.y() - b.y();
		return dx * dx + dy * dy;
	}
}
