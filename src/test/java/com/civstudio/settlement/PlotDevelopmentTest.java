package com.civstudio.settlement;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.civstudio.geo.Feature;
import com.civstudio.geo.Improvement;
import com.civstudio.geo.PlotType;
import com.civstudio.geo.Terrain;
import com.civstudio.geo.TerrainRegistry;

/**
 * Phase-3 coverage for plot development — the three Civ4 land legs on a {@link Plot}
 * (terrain + wild feature + improvement) and the wild/cleared lifecycle (see {@code
 * docs/plots.md}). A bare plot yields only its terrain; a feature-bearing plot is
 * <b>wild</b> and folds the feature's yield (the forage seam); raising a {@code FARM}
 * <b>clears</b> the feature and folds the improvement's yield instead; a forage
 * {@code CAMP} raised without clearing leaves the plot wild.
 */
class PlotDevelopmentTest {

	private static final TerrainRegistry REG = TerrainRegistry.load();
	private static final Terrain GRASSLAND = REG.terrain("TERRAIN_GRASSLAND");
	private static final Feature JUNGLE = REG.feature("FEATURE_JUNGLE");
	private static final Feature FOREST = REG.feature("FEATURE_FOREST");
	private static final Improvement FARM = REG.improvement("IMPROVEMENT_FARM");
	private static final Improvement CAMP = REG.improvement("IMPROVEMENT_HUNTING_CAMP");

	@Test
	void bareUndevelopedPlotYieldsOnlyItsTerrain() {
		Plot p = new Plot(0, GRASSLAND, PlotType.FLAT, null);
		assertArrayEquals(new int[] { 2, 0, 0 }, p.yields());
		assertFalse(p.isWild(), "a featureless plot is not wild");
		assertFalse(p.isCleared());
		assertNull(p.improvement());
		assertEquals(0, p.clearCost(), 1e-9);
		assertTrue(p.isWorkable(), "flat ground is workable");
	}

	@Test
	void featureBearingPlotIsWildAndFoldsFeatureYield() {
		// forest adds +1 production on top of the terrain; the plot is wild (uncleared,
		// feature-bearing) and carries the feature's clear cost
		Plot p = new Plot(1, GRASSLAND, PlotType.FLAT, FOREST);
		assertTrue(p.isWild());
		assertArrayEquals(new int[] { 2, 1, 0 }, p.yields());
		assertEquals(FOREST.clearCost(), p.clearCost(), 1e-9);
	}

	@Test
	void aHillAddsAProductionBonusAndIsWorkableWhileAPeakIsNot() {
		// a hill adds +1 production to the plot's yield and stays workable
		Plot hill = new Plot(2, GRASSLAND, PlotType.HILL, null);
		assertTrue(hill.isWorkable());
		assertArrayEquals(new int[] { 2, 1, 0 }, hill.yields()); // grassland + hill prod
		assertTrue(hill.plotType().makesMineValid());

		// a peak is unworkable rough ground
		Plot peak = new Plot(3, GRASSLAND, PlotType.PEAK, null);
		assertFalse(peak.isWorkable());
	}

	@Test
	void raisingAFarmClearsTheFeatureAndFoldsTheImprovementYield() {
		// jungle is -1 food with a clear cost; before clearing the plot is wild
		Plot p = new Plot(4, GRASSLAND, PlotType.FLAT, JUNGLE);
		assertTrue(p.isWild());
		assertArrayEquals(new int[] { 1, 0, 0 }, p.yields()); // 2 grassland - 1 jungle
		assertEquals(JUNGLE.clearCost(), p.clearCost(), 1e-9);

		// raising a FARM clears the feature: it is no longer wild, the feature drops
		// out of the yield, and the FARM's +2 food is folded in
		p.raiseImprovement(FARM, true);
		assertFalse(p.isWild());
		assertTrue(p.isCleared());
		assertEquals(FARM, p.improvement());
		assertEquals(0, p.clearCost(), 1e-9);
		assertArrayEquals(new int[] { 4, 0, 0 }, p.yields()); // 2 grassland + 2 farm
	}

	@Test
	void aForageCampLeavesThePlotWild() {
		// the forage path raises a CAMP without clearing — the plot stays wild and its
		// feature yield still counts (gathering off the wild land), plus the camp's
		Plot p = new Plot(5, GRASSLAND, PlotType.FLAT, FOREST);
		p.raiseImprovement(CAMP, false);
		assertTrue(p.isWild(), "raising a camp without clearing leaves the plot wild");
		assertFalse(p.isCleared());
		assertEquals(CAMP, p.improvement());
		// terrain + forest feature + camp improvement all stack while wild
		int[] expected = GRASSLAND.yields().clone();
		for (int i = 0; i < 3; i++)
			expected[i] += FOREST.yieldChange(i) + CAMP.yieldChange(i);
		assertArrayEquals(expected, p.yields());
	}
}
