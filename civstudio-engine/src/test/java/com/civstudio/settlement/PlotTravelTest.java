package com.civstudio.settlement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

import com.civstudio.geo.Province;

/**
 * Phase-2b coverage for the travel-time ladder (see {@code docs/plots.md}): the
 * Fibonacci {@link TravelLadder} and its {@code workFactor}, and {@link
 * Settlement#plotTravelTime} — the round-trip commute a worker pays to reach its
 * firm's plot, which the labor market folds into each worker's delivered labor.
 * The coupling is bypassed (commute 0) for a province-less colony and for a
 * center-grouped / unseated firm.
 */
class PlotTravelTest {

	private static final LocalDate START = LocalDate.of(1444, 12, 11);

	@Test
	void ladderFollowsTheFibonacciSequence() {
		// T(0)=0 (center), then 1,1,2,3,5,8,13,21,34,55,...
		long[] expected = { 0, 1, 1, 2, 3, 5, 8, 13, 21, 34, 55, 89 };
		for (int i = 0; i < expected.length; i++)
			assertEquals(expected[i], TravelLadder.oneWaySeconds(i),
					"T(" + i + ")");
	}

	@Test
	void workFactorDeductsOverheadAndCommute() {
		// center plot: only the market overhead N out of the window D
		assertEquals(0.9, TravelLadder.workFactor(0, 100, 1000), 1e-9);
		// a commute that, with N, meets or exceeds D leaves no useful labor
		assertEquals(0.0, TravelLadder.workFactor(950, 100, 1000), 1e-9);
		// a vanished work window (polar night) yields nothing
		assertEquals(0.0, TravelLadder.workFactor(0, 100, 0), 1e-9);
	}

	@Test
	void commuteRisesWithPlotIndexInAProvince() {
		Province dh = new GameSession(7).getWorldMap().findByName("Dhenijansar")
				.orElseThrow();
		Settlement c = new GameSession(7).newSettlement("A", START, 30, 26, 5, 2, dh);

		// claim a run of plots; each lands on the next workable plot, so the commute
		// is non-decreasing in claim order (peaks may be skipped over, leaving the
		// seated indices non-contiguous, so we assert the rise, not an exact T(index))
		double[] commutes = new double[8];
		for (int i = 0; i < commutes.length; i++) {
			PlotOccupant o = new PlotOccupant() {
			};
			c.claimPlot(o);
			commutes[i] = c.plotTravelTime(o);
		}
		for (int i = 1; i < commutes.length; i++)
			assertTrue(commutes[i] >= commutes[i - 1],
					"commute should not fall as plots are claimed: " + commutes[i]
							+ " < " + commutes[i - 1]);
		assertTrue(commutes[commutes.length - 1] > commutes[0],
				"a far plot commutes more than the first");

		// an unseated occupant pays no commute (center-grouped / pending)
		assertEquals(0.0, c.plotTravelTime(new PlotOccupant() {
		}), 1e-9);
	}

	@Test
	void provinceLessColonyHasNoCommute() {
		Settlement bare = new GameSession(7).newSettlement("Bare", START, 30, 26, 5, 2,
				51.5074, -0.1278);
		PlotOccupant o = new PlotOccupant() {
		};
		c_claimMany(bare, 6, o); // seat o on a high-index plot
		assertEquals(0.0, bare.plotTravelTime(o),
				"a province-less colony bypasses the travel coupling");
		assertTrue(bare.getPlotCount() >= 6);
	}

	// claim `before` throwaway plots, then claim `last` on the next plot
	private static void c_claimMany(Settlement c, int before, PlotOccupant last) {
		for (int i = 0; i < before; i++)
			c.claimPlot(new PlotOccupant() {
			});
		c.claimPlot(last);
	}
}
