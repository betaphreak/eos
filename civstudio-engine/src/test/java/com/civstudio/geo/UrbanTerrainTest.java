package com.civstudio.geo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The authored {@code TERRAIN_URBAN} built-up city ground (see {@code docs/urban-plots.md}):
 * it loads from the registry with its hand-set yields. This is the plot terrain the
 * per-province urban core is stamped with (a city keeps its real land hinterland and gains
 * one or more urban plots — cities are <em>not</em> a wholly-urban province type). Here we
 * only guard the terrain substrate; the per-plot assignment is covered where it lands.
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
}
