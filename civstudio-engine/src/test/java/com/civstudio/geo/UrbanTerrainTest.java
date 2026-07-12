package com.civstudio.geo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.civstudio.util.Rng;

/**
 * The authored {@code TERRAIN_URBAN} built-up city ground and the per-province urban core
 * (see {@code docs/urban-plots.md}): the terrain loads with its hand-set yields, an ordinary
 * LAND province gets exactly one urban core plot at its Civ4 {@code foundValue} site, and a
 * {@code city_terrain} province (Dhenijansar) gets a denser core while keeping its farmland
 * hinterland. Cities are <em>not</em> a wholly-urban province type.
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
	void cityProvinceGetsADenserCoreButKeepsItsHinterland() throws Exception {
		WorldMap map = WorldMap.load();
		Province dh = map.findByName("Dhenijansar").orElseThrow();
		assertTrue(dh.city(), "Dhenijansar is a city_terrain province");

		ProvincePlotField field = ProvincePlotField.generate(
				dh, TerrainRegistry.load(), ProvinceRaster.load(), new Rng(7));

		long urban = field.plots().stream()
				.filter(pl -> "TERRAIN_URBAN".equals(pl.terrain().type())).count();
		assertTrue(urban > 1, "a city has a denser core (>1 urban plot), got " + urban);
		// but it keeps its farmland: most of the province is not urban
		assertTrue(urban < field.size() / 2, "the rural hinterland is kept (" + urban
				+ "/" + field.size() + " urban)");
	}
}
