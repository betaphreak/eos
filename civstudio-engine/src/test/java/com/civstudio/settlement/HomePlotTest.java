package com.civstudio.settlement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.civstudio.geo.Province;

/**
 * Field-level coverage for the <b>home-plot</b> mechanics of the plot-working economy ({@code
 * docs/plot-working-plan.md} P1): a landed household is seated on a workable plot it farms for
 * subsistence food, the plot's food yield scales its larder top-up, the site's workable plots cap how
 * many households can be landed (the overflow is landless), and a freed plot is reclaimed by the next
 * household (turnover). The end-to-end survival of a settled colony is covered by {@code
 * com.civstudio.simulation.HomePlotEconomyTest}.
 */
class HomePlotTest {

	private static final LocalDate START = LocalDate.of(1444, 12, 11);

	private static Settlement dhenijansar() {
		Province dh = new GameSession(7).getWorldMap().findByName("Dhenijansar").orElseThrow();
		return new GameSession(7).newSettlement("A", START, 30, 26, 5, 2, dh);
	}

	@Test
	void homePlotFoodYieldScalesWithPlotFoodAndIsZeroWhenLandless() {
		Settlement c = dhenijansar();
		assertEquals(0.0, c.homePlotFoodYield(null), 1e-9,
				"a landless household (null plot) draws no plot food");

		PlotOccupant occupant = new PlotOccupant() {
		};
		Plot p = c.claimHomePlot(occupant);
		assertNotNull(p, "a fresh colony can seat a home plot");
		assertTrue(p.isWorkable(), "a home plot is workable ground (peaks are skipped)");

		double expected = Math.max(0, p.yields()[0]) * Settlement.HOUSEHOLD_PLOT_RATE;
		assertEquals(expected, c.homePlotFoodYield(p), 1e-9,
				"home-plot food is the plot's food yield times the self-sufficiency rate");
		assertTrue(c.homePlotFoodYield(p) > 0, "a Dhenijansar home plot yields food");
	}

	@Test
	void claimHomePlotSharesPlotsUnderCrowdingRatherThanGoingLandless() {
		Settlement c = dhenijansar();
		// seat more households than the province has plots: the P2 model shares plots (Malthus) rather
		// than turning the overflow landless, so every household still gets a (possibly shared) plot
		int n = c.getMaxPlots() + 20;
		List<Plot> assigned = new ArrayList<>();
		for (int i = 0; i < n; i++) {
			Plot p = c.claimHomePlot(new PlotOccupant() {
			});
			assertNotNull(p, "a household always gets a (possibly shared) home plot — no landless overflow");
			assigned.add(p);
		}

		// more households than distinct plots → at least one plot is shared (load >= 2)
		int maxLoad = 0;
		for (Plot p : new HashSet<>(assigned))
			maxLoad = Math.max(maxLoad, c.homePlotLoad(p));
		assertTrue(maxLoad >= 2, "crowding shares a plot among multiple households (max load " + maxLoad + ")");

		// a shared plot's food splits equally: each household on a load-N plot gets 1/N of its yield×rate
		Plot shared = assigned.stream().filter(p -> c.homePlotLoad(p) >= 2).findFirst().orElseThrow();
		int load = c.homePlotLoad(shared);
		double expected = Math.max(0, shared.yields()[0]) * Settlement.HOUSEHOLD_PLOT_RATE / load;
		assertEquals(expected, c.homePlotFoodYield(shared), 1e-9,
				"a shared plot's food splits equally among the " + load + " households on it");
	}

	@Test
	void spreadsToDensityOneBeforeCrowding() {
		Settlement c = dhenijansar();
		// the first handful of households each get their own plot (density 1) — the colony spreads
		// across fresh land before it ever doubles up
		Plot p1 = c.claimHomePlot(new PlotOccupant() {
		});
		Plot p2 = c.claimHomePlot(new PlotOccupant() {
		});
		assertNotSame(p1, p2, "a sparse colony spreads households onto distinct plots (density 1)");
		assertEquals(1, c.homePlotLoad(p1));
		assertEquals(1, c.homePlotLoad(p2));
	}

	@Test
	void aFreedHomePlotIsReusedByTheNextHousehold() {
		Settlement c = dhenijansar();
		Plot pa = c.claimHomePlot(new PlotOccupant() {
		});
		assertNotNull(pa, "the first household is seated on a home plot");
		assertEquals(1, c.homePlotLoad(pa));

		// the household dies and releases its share; the freed land (load 0) is reused before any fresh
		// plot is claimed — the turnover that keeps the core on the land (Settlement.newDay / P2)
		c.releaseHomePlot(pa);
		assertEquals(0, c.homePlotLoad(pa));
		Plot pb = c.claimHomePlot(new PlotOccupant() {
		});
		assertSame(pa, pb, "a freed home plot is reused by the next household");
		assertEquals(1, c.homePlotLoad(pb));
	}
}
