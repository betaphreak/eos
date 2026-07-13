package com.civstudio.settlement;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.civstudio.geo.Bonus;
import com.civstudio.geo.Improvement;
import com.civstudio.geo.PlotType;
import com.civstudio.geo.Province;
import com.civstudio.geo.Terrain;
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

	private static final TerrainRegistry REG = TerrainRegistry.load();
	private static final Improvement FARM = REG.improvement("IMPROVEMENT_FARM");
	private static final Terrain GRASSLAND = REG.terrain("TERRAIN_GRASSLAND");
	// BONUS_CORN is a CROP (necessity) resource, yield [4,0,0]; BONUS_OLIVES is a LUXURY
	// (enjoyment) resource that nonetheless carries food (yield [2,0,2]) — the foil that
	// proves the food wiring is gated on the bonus's consumer-good class, not its raw food.
	private static final Bonus CORN = REG.bonus("BONUS_CORN");
	private static final Bonus OLIVES = REG.bonus("BONUS_OLIVES");

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
		// a seated necessity firm raises a FARM on cleared land — develop each workable
		// plot the way founding does (peaks are never seated), so the factor measured
		// is the developed-farm food TFP
		for (Plot p : c.getDistrictPlots())
			if (p.isWorkable())
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
	void aFoodBonusAddsItsFoodYield() {
		// a bare grassland plot vs. the same plot bearing a CROP (necessity) resource:
		// the food channel rises by the bonus's food yield, the others are untouched
		Plot bare = new Plot(0, 0, GRASSLAND, PlotType.FLAT, null, null);
		Plot corn = new Plot(0, 0, GRASSLAND, PlotType.FLAT, null, CORN);
		assertEquals(bare.yields()[0] + CORN.yieldChange(0), corn.yields()[0],
				"a CROP bonus adds its food yield to the plot");
		assertEquals(bare.yields()[1], corn.yields()[1]);
		assertEquals(bare.yields()[2], corn.yields()[2]);
		// and a richer food plot reads a higher necessity yield factor
		assertTrue(corn.yieldFactor(Sector.NECESSITY) > bare.yieldFactor(Sector.NECESSITY),
				"a food bonus raises the necessity yield factor");
	}

	// the NECESSITY yield factor of a developed FARM on the given terrain
	private static double farmedFood(Terrain terrain) {
		Plot p = new Plot(0, 0, terrain, PlotType.FLAT, null, null);
		p.raiseImprovement(FARM, true);
		return p.yieldFactor(Sector.NECESSITY);
	}

	@Test
	void coldTerrainFarmsFoodPoorly() {
		// a developed FARM (+2 food) on grassland vs the cold terrains: the same improvement, but the
		// cold-food penalty makes an arctic farm yield a fraction of a temperate one, so a tundra /
		// glacier colony is food-scarce however it works the land (arctic colonies feel the harshness)
		double grass = farmedFood(GRASSLAND);
		double taiga = farmedFood(REG.terrain("TERRAIN_TAIGA"));
		double tundra = farmedFood(REG.terrain("TERRAIN_TUNDRA"));
		double permafrost = farmedFood(REG.terrain("TERRAIN_PERMAFROST"));
		double glacier = farmedFood(REG.terrain("TERRAIN_GLACIER"));

		assertTrue(tundra < grass * 0.5,
				"a tundra farm feeds far worse than grassland (" + tundra + " vs " + grass + ")");
		assertTrue(taiga < grass, "boreal taiga feeds worse than temperate grassland");
		assertTrue(permafrost < tundra, "permafrost is harsher than tundra");
		assertTrue(glacier <= permafrost, "glacier is the harshest cold ground");
	}

	@Test
	void aNonFoodBonusDoesNotAddFood() {
		// BONUS_OLIVES is a LUXURY (enjoyment) resource and carries food in its raw yield,
		// but the wiring is gated on the bonus's consumer-good class — so it adds nothing
		// to the necessity firm's food (enjoyment bonuses are dormant this cut)
		Plot bare = new Plot(0, 0, GRASSLAND, PlotType.FLAT, null, null);
		Plot olives = new Plot(0, 0, GRASSLAND, PlotType.FLAT, null, OLIVES);
		assertArrayEquals(bare.yields(), olives.yields(),
				"a non-necessity bonus leaves the (food-only) yields unchanged this cut");
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
