package com.civstudio.settlement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;

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
	void claimHomePlotOverflowsToLandlessWhenTheSiteIsFull() {
		Settlement c = dhenijansar();
		int cap = c.getMaxPlots();
		int seated = 0;
		boolean sawLandless = false;
		for (int i = 0; i < cap + 5; i++) {
			Plot p = c.claimHomePlot(new PlotOccupant() {
			});
			if (p == null) {
				sawLandless = true;
				break;
			}
			seated++;
		}
		assertTrue(seated > 0, "some households were seated on home plots");
		assertTrue(sawLandless,
				"beyond the site's workable plots, claiming returns null (the landless overflow)");
		assertTrue(seated <= cap, "no more than the province's plots were seated, was " + seated);
	}

	@Test
	void aFreedHomePlotIsReclaimedByTheNextHousehold() {
		Settlement c = dhenijansar();
		PlotOccupant a = new PlotOccupant() {
		};
		Plot pa = c.claimHomePlot(a);
		assertNotNull(pa, "the first household is seated on a home plot");

		// a's death frees its plot; the next household reclaims it (the turnover that keeps the
		// landed core on the land — see Settlement.newDay / docs/plot-working-plan.md P1)
		c.vacatePlot(a);
		Plot pb = c.claimHomePlot(new PlotOccupant() {
		});
		assertSame(pa, pb, "a freed home plot is reclaimed by the next household");
	}
}
