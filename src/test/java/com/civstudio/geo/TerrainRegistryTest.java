package com.civstudio.geo;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Verifies the Phase-0 plot data layer: the {@link TerrainRegistry} loads the
 * committed {@code /terrains.json}, {@code /features.json} and {@code
 * /improvements.json} resources (emitted by the {@code geo.export} exporters from
 * {@code data/CIV4*.xml}), and a handful of yields / clear-costs / improvement
 * yields are spot-checked against the source XML. See {@code docs/plots.md}.
 */
class TerrainRegistryTest {

	@Test
	void loadsTheCuratedSubsets() {
		TerrainRegistry reg = TerrainRegistry.load();
		// the curated subset sizes pinned in docs/plots.md
		assertEquals(16, reg.terrains().size(), "curated land terrains");
		assertEquals(10, reg.features().size(), "curated land features");
		assertEquals(12, reg.improvements().size(), "curated firm-building improvements");
	}

	@Test
	void terrainYieldsMatchXml() {
		TerrainRegistry reg = TerrainRegistry.load();

		Terrain grass = reg.terrain("TERRAIN_GRASSLAND");
		assertNotNull(grass);
		assertArrayEquals(new int[] { 2, 0, 0 }, grass.yields());
		assertTrue(grass.bFound());
		assertEquals(0, grass.buildModifier());

		Terrain rocky = reg.terrain("TERRAIN_ROCKY");
		assertArrayEquals(new int[] { 0, 2, 0 }, rocky.yields());
		assertEquals(50, rocky.buildModifier());

		// desert is barren (no food/prod/commerce) and not settleable
		Terrain desert = reg.terrain("TERRAIN_DESERT");
		assertArrayEquals(new int[] { 0, 0, 0 }, desert.yields());
		assertFalse(desert.bFound());

		// hills/peaks are a PlotType axis, not terrains — never in the set
		assertNull(reg.terrain("TERRAIN_HILL"));
		assertNull(reg.terrain("TERRAIN_PEAK"));
	}

	@Test
	void featureYieldsAndClearCostsMatchXml() {
		TerrainRegistry reg = TerrainRegistry.load();

		Feature jungle = reg.feature("FEATURE_JUNGLE");
		assertNotNull(jungle);
		assertArrayEquals(new int[] { -1, 0, 0 }, jungle.yieldChanges());
		assertEquals(40, jungle.clearCost());
		assertTrue(jungle.validTerrains().contains("TERRAIN_GRASSLAND"));

		Feature forest = reg.feature("FEATURE_FOREST");
		assertArrayEquals(new int[] { 0, 1, 0 }, forest.yieldChanges());

		Feature oasis = reg.feature("FEATURE_OASIS");
		assertArrayEquals(new int[] { 3, 0, 2 }, oasis.yieldChanges());
		assertTrue(oasis.requiresFlatlands());

		Feature flood = reg.feature("FEATURE_FLOOD_PLAINS");
		assertArrayEquals(new int[] { 2, 0, 0 }, flood.yieldChanges());
		assertTrue(flood.requiresRiver());
	}

	@Test
	void improvementYieldsMatchXml() {
		TerrainRegistry reg = TerrainRegistry.load();

		Improvement farm = reg.improvement("IMPROVEMENT_FARM");
		assertNotNull(farm);
		assertArrayEquals(new int[] { 2, 0, 0 }, farm.yieldChanges());
		assertEquals(30, farm.buildCost());
		assertEquals("TECH_AGRICULTURE", farm.prereqTech());
		assertTrue(farm.freshWaterMakesValid());
		assertTrue(farm.validTerrains().contains("TERRAIN_GRASSLAND"));

		Improvement mine = reg.improvement("IMPROVEMENT_MINE");
		assertArrayEquals(new int[] { 0, 4, 0 }, mine.yieldChanges());
		assertEquals(24, mine.buildCost());
		assertTrue(mine.hillsMakesValid());

		// lumbermill works a forest feature, reading production + commerce
		Improvement lumber = reg.improvement("IMPROVEMENT_LUMBERMILL");
		assertArrayEquals(new int[] { 0, 6, 4 }, lumber.yieldChanges());
		assertTrue(lumber.validFeatures().contains("FEATURE_FOREST"));
	}

	@Test
	void unknownTypesReturnNull() {
		TerrainRegistry reg = TerrainRegistry.load();
		assertNull(reg.terrain("TERRAIN_NONEXISTENT"));
		assertNull(reg.feature("FEATURE_NONEXISTENT"));
		assertNull(reg.improvement("IMPROVEMENT_NONEXISTENT"));
	}
}
