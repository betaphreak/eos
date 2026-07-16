package com.civstudio.geo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.civstudio.util.Rng;

/**
 * The per-province urban core (see {@code docs/urban-plots.md} + {@code docs/plot-generator.md}).
 * Urban is now an <b>overlay flag</b> on the plot's natural terrain/relief — not a base terrain
 * (the synthetic {@code TERRAIN_URBAN} ground was retired: a city sits ON real ground). An ordinary
 * LAND province gets exactly one {@code urban()} core plot at its Civ4 {@code foundValue} site, and
 * a {@code city_terrain} province (Dhenijansar) is <b>fully urban</b> — every plot flagged urban, the
 * city render layer covering it with its Civ6 district tiles.
 */
class UrbanTerrainTest {

	@Test
	void ordinaryLandProvinceGetsOneUrbanCorePlot() throws Exception {
		WorldMap map = WorldMap.load();
		Province p = map.provinces().stream()
				.filter(x -> x.type() == ProvinceType.LAND && !x.city() && x.plots() > 20)
				.findFirst().orElseThrow(() -> new AssertionError("no ordinary LAND province"));

		ProvincePlotField field = ProvincePlotField.generate(
				p, TerrainRegistry.load(), ProvinceRaster.load(), new Rng(7));

		long urban = field.plots().stream()
				.filter(ProvincePlotField.ProvincePlot::urban).count();
		assertEquals(1, urban, "an ordinary province gets exactly one urban core plot");
		// the urban core keeps its NATURAL terrain (the built-up flag is an overlay, not a terrain)
		field.plots().stream().filter(ProvincePlotField.ProvincePlot::urban).forEach(pl -> {
			assertNotEquals("TERRAIN_URBAN", pl.terrain().type(), "urban is an overlay, ground stays natural");
			assertNull(pl.feature(), "no wild feature on built-up ground");
			assertNull(pl.bonus(), "the resource is built over");
		});
	}

	@Test
	void cityProvinceIsFullyUrban() throws Exception {
		WorldMap map = WorldMap.load();
		Province dh = map.findByName("Dhenijansar").orElseThrow();
		assertTrue(dh.city(), "Dhenijansar is a city_terrain province");

		ProvincePlotField field = ProvincePlotField.generate(
				dh, TerrainRegistry.load(), ProvinceRaster.load(), new Rng(7));

		// a city_terrain province is one sprawling city — every plot is flagged urban (the render
		// layer covers it), with no farmland/features/resources showing through, but the ground
		// keeps its natural terrain/relief beneath the overlay.
		for (var pl : field.plots()) {
			assertTrue(pl.urban(), "every city plot is flagged urban");
			assertNotEquals("TERRAIN_URBAN", pl.terrain().type(), "the ground beneath stays natural terrain");
			assertNull(pl.feature(), "no wild feature on built-up ground");
			assertNull(pl.bonus(), "no resource on built-up ground");
		}
	}
}
