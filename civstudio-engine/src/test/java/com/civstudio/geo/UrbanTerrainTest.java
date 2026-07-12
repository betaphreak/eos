package com.civstudio.geo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.civstudio.util.Rng;

/**
 * The authored {@code TERRAIN_URBAN} built-up city ground and the per-province urban core
 * (see {@code docs/urban-plots.md} + {@code docs/plot-generator.md}): the terrain loads with its
 * hand-set yields, an ordinary LAND province gets exactly one urban core plot at its Civ4
 * {@code foundValue} site, and a {@code city_terrain} province (Dhenijansar) is <b>fully urban</b> —
 * every plot paved, the city render layer covering it (its future Civ6 district tiles).
 */
class UrbanTerrainTest {

	@Test
	void registryCarriesTheUrbanTerrain() {
		TerrainRegistry reg = TerrainRegistry.load();

		Terrain urban = reg.terrain("TERRAIN_URBAN");
		assertNotNull(urban, "TERRAIN_URBAN present");
		assertEquals(1, urban.yield(0), "urban food (meager)");
		assertEquals(1, urban.yield(1), "urban production");
		assertEquals(3, urban.yield(2), "urban commerce (trade/tax-heavy)");
		assertTrue(urban.bFound(), "urban is settleable");
	}

	@Test
	void ordinaryLandProvinceGetsOneUrbanCorePlot() throws Exception {
		WorldMap map = WorldMap.load();
		Province p = map.provinces().stream()
				.filter(x -> x.type() == ProvinceType.LAND && !x.city() && x.plots() > 20)
				.findFirst().orElseThrow(() -> new AssertionError("no ordinary LAND province"));

		ProvincePlotField field = ProvincePlotField.generate(
				p, TerrainRegistry.load(), ProvinceRaster.load(), new Rng(7));

		long urban = field.plots().stream()
				.filter(pl -> "TERRAIN_URBAN".equals(pl.terrain().type())).count();
		assertEquals(1, urban, "an ordinary province gets exactly one urban core plot");
		// the core plot is flat built ground, not the raster's relief
		field.plots().stream().filter(pl -> "TERRAIN_URBAN".equals(pl.terrain().type()))
				.forEach(pl -> assertEquals(PlotType.FLAT, pl.plotType(), "flat city ground"));
	}

	@Test
	void cityProvinceIsFullyUrban() throws Exception {
		WorldMap map = WorldMap.load();
		Province dh = map.findByName("Dhenijansar").orElseThrow();
		assertTrue(dh.city(), "Dhenijansar is a city_terrain province");

		ProvincePlotField field = ProvincePlotField.generate(
				dh, TerrainRegistry.load(), ProvinceRaster.load(), new Rng(7));

		// a city_terrain province is one sprawling city — every plot is paved URBAN ground (the render
		// layer covers it), with no farmland/features/resources showing through.
		for (var pl : field.plots()) {
			assertEquals("TERRAIN_URBAN", pl.terrain().type(), "every city plot is urban");
			assertEquals(PlotType.FLAT, pl.plotType(), "flat built ground");
			assertNull(pl.feature(), "no wild feature on built ground");
			assertNull(pl.bonus(), "no resource on built ground");
		}
	}
}
