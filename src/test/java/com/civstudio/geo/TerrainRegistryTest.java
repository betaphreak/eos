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
 * committed {@code /terrains.json}, {@code /features.json}, {@code
 * /improvements.json} and {@code /bonuses.json} resources (emitted by the {@code
 * geo.export} exporters from {@code data/CIV4*.xml}), and a handful of yields /
 * clear-costs / improvement and bonus yields are spot-checked against the source
 * XML. See {@code docs/plots.md}.
 */
class TerrainRegistryTest {

	@Test
	void loadsTheCuratedSubsets() {
		TerrainRegistry reg = TerrainRegistry.load();
		// the curated subset sizes pinned in docs/plots.md — 16 settleable land
		// terrains plus the 8 shelf water terrains (coast/sea + polar/tropical, lake
		// shore/lake) the coastal-shelf plots ground on (see docs/coastlines.md)
		assertEquals(24, reg.terrains().size(), "curated land + shelf water terrains");
		assertEquals(11, reg.features().size(), "curated land features + FEATURE_ICE");
		assertEquals(12, reg.improvements().size(), "curated firm-building improvements");
		// bonuses are exported in full (no curated subset), so the count is the
		// whole CIV4BonusInfos.xml set
		assertEquals(106, reg.bonuses().size(), "all bonus resources");
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
	void bonusYieldsAndConstraintsMatchXml() {
		TerrainRegistry reg = TerrainRegistry.load();

		// barley: a crop with a commerce edge, exercising all three placement lists
		Bonus barley = reg.bonus("BONUS_BARLEY");
		assertNotNull(barley);
		assertEquals(BonusClass.CROP, barley.bonusClass());
		assertArrayEquals(new int[] { 3, 0, 1 }, barley.yieldChanges());
		assertEquals("TECH_GATHERING", barley.techReveal());
		assertEquals(1, barley.health());
		assertTrue(barley.validTerrains().contains("TERRAIN_GRASSLAND"));
		assertTrue(barley.validFeatures().contains("FEATURE_FLOOD_PLAINS"));
		assertTrue(barley.validFeatureTerrains().contains("TERRAIN_DESERT"));

		// corn: latitude-banded
		Bonus corn = reg.bonus("BONUS_CORN");
		assertArrayEquals(new int[] { 4, 0, 0 }, corn.yieldChanges());
		assertEquals(20, corn.minLatitude());
		assertEquals(50, corn.maxLatitude());

		// olives: a luxury contributing happiness (a dormant amenity)
		Bonus olives = reg.bonus("BONUS_OLIVES");
		assertEquals(BonusClass.LUXURY, olives.bonusClass());
		assertEquals(1, olives.happiness());
	}

	@Test
	void bonusClassUniqueRangesMatchXml() {
		// baked into the enum from data/civ4/CIV4BonusClassInfos.xml
		assertEquals(2, BonusClass.CROP.uniqueRange());
		assertEquals(3, BonusClass.LIVESTOCK.uniqueRange());
		assertEquals(1, BonusClass.STRATEGIC.uniqueRange());
		assertEquals(0, BonusClass.SEAFOOD.uniqueRange());
		// key round-trips through the JSON form
		assertEquals(BonusClass.CROP, BonusClass.fromKey("BONUSCLASS_CROP"));
		assertEquals("BONUSCLASS_CROP", BonusClass.CROP.key());
		assertNull(BonusClass.fromKey(null));
	}

	@Test
	void unknownTypesReturnNull() {
		TerrainRegistry reg = TerrainRegistry.load();
		assertNull(reg.terrain("TERRAIN_NONEXISTENT"));
		assertNull(reg.feature("FEATURE_NONEXISTENT"));
		assertNull(reg.improvement("IMPROVEMENT_NONEXISTENT"));
		assertNull(reg.bonus("BONUS_NONEXISTENT"));
	}
}
