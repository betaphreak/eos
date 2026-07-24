package com.civstudio.settlement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
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

		Plot p = c.claimHomePlot();
		assertNotNull(p, "a fresh colony can seat a home plot");
		assertTrue(p.isWorkable(), "a home plot is workable ground (peaks are skipped)");

		double expected = Math.max(0, p.yields()[0]) * Settlement.HOUSEHOLD_PLOT_RATE;
		assertEquals(expected, c.homePlotFoodYield(p), 1e-9,
				"home-plot food is the plot's food yield times the self-sufficiency rate");
		assertTrue(c.homePlotFoodYield(p) > 0, "a Dhenijansar home plot yields food");
	}

	@Test
	void emptyPlotsAreSingleHouseholdFarmsAndOverflowIsLandlessWithoutBuiltGround() {
		Settlement c = dhenijansar();
		// the "has a regular building" model (user ruling 2026-07-24): an empty plot is a
		// single-household FARM — never shared. A bare colony has no developed ground to stack
		// housing on, so once its workable land is each taken by one household the overflow is
		// landless (claimHomePlot returns null).
		int n = c.getMaxPlots() + 20;
		int seated = 0, landless = 0;
		List<Plot> assigned = new ArrayList<>();
		for (int i = 0; i < n; i++) {
			Plot p = c.claimHomePlot();
			if (p == null) {
				landless++;
				continue;
			}
			seated++;
			assigned.add(p);
			assertEquals(1, c.homePlotLoad(p), "an empty plot is a single-household farm — never shared");
		}
		assertTrue(seated > 0, "the colony seats households on empty farm plots");
		assertTrue(landless > 0, "with no built ground, the overflow beyond empty plots is landless");
		assertEquals(seated, new HashSet<>(assigned).size(), "each farm plot holds exactly one household");
	}

	@Test
	void householdsStackAsHousingOnBuiltGround() {
		Settlement c = dhenijansar();
		// exhaust the empty workable land — each plot a single-household farm
		List<Plot> farms = new ArrayList<>();
		for (Plot p = c.claimHomePlot(); p != null; p = c.claimHomePlot())
			farms.add(p);
		assertTrue(farms.size() > 0, "the colony seats households on its empty land");
		assertNull(c.claimHomePlot(), "no empty land and no developed ground → landless");

		// develop one plot with a REGULAR (non-housing) building — it becomes stacked-housing ground,
		// no longer farmed. A household with no empty land now stacks there rather than going landless.
		Plot dev = farms.get(0);
		dev.addBuilding(new Building("BUILDING_TESTER_CIVIC", null));
		assertTrue(dev.hasRegularBuilding(), "a non-housing building makes the plot developed ground");
		assertEquals(0.0, c.homePlotFoodYield(dev), 1e-9, "developed ground yields no subsistence food");

		int before = c.homePlotLoad(dev);
		Plot stacked = c.claimHomePlot();
		assertSame(dev, stacked, "a household with no empty land stacks as housing on developed ground");
		assertEquals(before + 1, c.homePlotLoad(dev), "the developed plot now houses another household");
	}

	@Test
	void spreadsToDensityOneBeforeCrowding() {
		Settlement c = dhenijansar();
		// the first handful of households each get their own plot (density 1) — the colony spreads
		// across fresh land before it ever doubles up
		Plot p1 = c.claimHomePlot();
		Plot p2 = c.claimHomePlot();
		assertNotSame(p1, p2, "a sparse colony spreads households onto distinct plots (density 1)");
		assertEquals(1, c.homePlotLoad(p1));
		assertEquals(1, c.homePlotLoad(p2));
	}

	@Test
	void aFreedHomePlotIsReusedByTheNextHousehold() {
		Settlement c = dhenijansar();
		Plot pa = c.claimHomePlot();
		assertNotNull(pa, "the first household is seated on a home plot");
		assertEquals(1, c.homePlotLoad(pa));

		// the household dies and releases its share; the freed land (load 0) is reused before any fresh
		// plot is claimed — the turnover that keeps the core on the land (Settlement.newDay / P2)
		c.releaseHomePlot(pa);
		assertEquals(0, c.homePlotLoad(pa));
		Plot pb = c.claimHomePlot();
		assertSame(pa, pb, "a freed home plot is reused by the next household");
		assertEquals(1, c.homePlotLoad(pb));
	}
}
