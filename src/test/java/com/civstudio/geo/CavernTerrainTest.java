package com.civstudio.geo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.civstudio.geo.ProvincePlotField.ProvincePlot;
import com.civstudio.util.Rng;

/**
 * The underground cave-floor terrain (see {@code docs/underworld.md}): the authored
 * {@code TERRAIN_CAVERN} / {@code TERRAIN_MUSHROOM_FOREST} load with their hand-set
 * yields, an underground ({@link ProvinceType#CAVERN}) province's plots are all flat
 * cave floor (not the mountains the raster reads), and the surface Haless
 * {@code mushroom_forest_region} gets its fungal-woodland ground.
 */
class CavernTerrainTest {

	@Test
	void registryCarriesTheAuthoredCaveTerrains() {
		TerrainRegistry reg = TerrainRegistry.load();

		Terrain cavern = reg.terrain("TERRAIN_CAVERN");
		assertNotNull(cavern, "TERRAIN_CAVERN present");
		assertEquals(1, cavern.yield(0), "cavern food (scarce)");
		assertEquals(2, cavern.yield(1), "cavern production (ore-rich)");
		assertTrue(cavern.bFound(), "cavern is settleable");

		Terrain shroom = reg.terrain("TERRAIN_MUSHROOM_FOREST");
		assertNotNull(shroom, "TERRAIN_MUSHROOM_FOREST present");
		assertEquals(2, shroom.yield(0), "mushroom-forest food");
	}

	@Test
	void cavernProvinceGeneratesFlatCaveFloor() throws Exception {
		WorldMap map = WorldMap.load();
		Province cave = map.provinces().stream()
				.filter(Province::isUnderground).filter(p -> p.plots() > 10)
				.findFirst().orElseThrow(() -> new AssertionError("no sizeable CAVERN province"));

		ProvincePlotField field = ProvincePlotField.generate(
				cave, TerrainRegistry.load(), ProvinceRaster.load(), new Rng(7));

		assertTrue(field.size() > 0, "cave province has land plots");
		for (ProvincePlot p : field.plots()) {
			assertEquals("TERRAIN_CAVERN", p.terrain().type(), "every plot is cave floor");
			assertEquals(PlotType.FLAT, p.plotType(), "flat cave floor, not the raster's peaks");
		}
	}

	@Test
	void surfaceMushroomForestGetsItsGround() throws Exception {
		WorldMap map = WorldMap.load();
		Province shroom = map.provinces().stream()
				.filter(p -> "mushroom_forest_region".equals(p.regionKey()))
				.findFirst().orElseThrow(() -> new AssertionError("no mushroom_forest_region province"));

		ProvincePlotField field = ProvincePlotField.generate(
				shroom, TerrainRegistry.load(), ProvinceRaster.load(), new Rng(7));

		for (ProvincePlot p : field.plots())
			assertEquals("TERRAIN_MUSHROOM_FOREST", p.terrain().type(),
					"surface mushroom forest ground");
	}
}
