package com.civstudio.settlement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.civstudio.geo.Improvement;
import com.civstudio.geo.Province;
import com.civstudio.geo.TerrainRegistry;
import com.civstudio.tech.Sector;

/**
 * Phase-2/3 coverage for the terrain &rarr; TFP coupling (see {@code docs/plots.md}):
 * a province colony reads its plot's food yield as a productivity factor, calibrated
 * so the default Dhenijansar colony's aggregate food factor — measured against the
 * <b>developed farm</b> (terrain food + the {@code FARM} improvement's +2, as a
 * seated necessity firm has) — averages ≈ 1.0, while the coupling is bypassed for a
 * province-less colony and gated off for every non-food sector this cut.
 */
class PlotYieldTest {

	private static final LocalDate START = LocalDate.of(1444, 12, 11);

	private static final Improvement FARM =
			TerrainRegistry.load().improvement("IMPROVEMENT_FARM");

	// genesis-append n plots, returning the dummy occupants seated on them
	private static List<PlotOccupant> seatN(Settlement c, int n) {
		List<PlotOccupant> occupants = new ArrayList<>();
		for (int i = 0; i < n; i++) {
			PlotOccupant o = new PlotOccupant() {
			};
			c.claimPlot(o);
			occupants.add(o);
		}
		return occupants;
	}

	@Test
	void defaultColonyFoodFactorAveragesAboutOne() {
		Province dh = new GameSession(7).getWorldMap().findByName("Dhenijansar")
				.orElseThrow();
		Settlement c = new GameSession(7).newSettlement("A", START, 30, 26, 5, 2, dh);

		double sum = 0;
		List<PlotOccupant> occ = seatN(c, 60);
		// a seated necessity firm raises a FARM on cleared land — develop each plot
		// the way founding does, so the factor measured is the developed-farm food TFP
		for (Plot p : c.getPlots())
			p.raiseImprovement(FARM, true);
		for (PlotOccupant o : occ)
			sum += c.plotYieldFactor(o, Sector.NECESSITY);
		double mean = sum / occ.size();
		// REFERENCE[food] is calibrated to Dhenijansar's climate plus the FARM's +2,
		// so the mean food factor sits near 1.0 (the default colony's food TFP ≈ its
		// pre-rework value)
		assertTrue(Math.abs(mean - 1.0) < 0.2,
				"mean food yield factor should be ~1.0, was " + mean);
	}

	@Test
	void nonFoodSectorsAreGatedOffThisCut() {
		Province dh = new GameSession(7).getWorldMap().findByName("Dhenijansar")
				.orElseThrow();
		Settlement c = new GameSession(7).newSettlement("A", START, 30, 26, 5, 2, dh);
		PlotOccupant o = seatN(c, 1).get(0);

		// only food is live this cut — enjoyment/capital/export read the neutral 1.0
		assertEquals(1.0, c.plotYieldFactor(o, Sector.ENJOYMENT), 1e-9);
		assertEquals(1.0, c.plotYieldFactor(o, Sector.CAPITAL), 1e-9);
		assertEquals(1.0, c.plotYieldFactor(o, Sector.EXPORT), 1e-9);
	}

	@Test
	void provinceLessColonyBypassesTheCoupling() {
		Settlement bare = new GameSession(7).newSettlement("Bare", START, 30, 26, 5, 2,
				51.5074, -0.1278);
		for (PlotOccupant o : seatN(bare, 20))
			assertEquals(1.0, bare.plotYieldFactor(o, Sector.NECESSITY), 1e-9,
					"a province-less colony takes no terrain yield factor");
	}

	@Test
	void anUnseatedOccupantIsLandIndependent() {
		Province dh = new GameSession(7).getWorldMap().findByName("Dhenijansar")
				.orElseThrow();
		Settlement c = new GameSession(7).newSettlement("A", START, 30, 26, 5, 2, dh);
		// an occupant that never claimed a plot (center-grouped / pending) reads 1.0
		assertEquals(1.0, c.plotYieldFactor(new PlotOccupant() {
		}, Sector.NECESSITY), 1e-9);
	}
}
