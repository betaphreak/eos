package com.civstudio.geo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Phase 1 of the urban plots work (see {@code docs/urban-plots.md}): the authored
 * {@code TERRAIN_URBAN} built-up city ground loads with its hand-set yields, and the
 * {@link ProvinceType#URBAN} type is a settleable, passable, surface (non-underground)
 * land type. No province is marked {@code URBAN} and no plot is assigned the terrain yet
 * (that is Phase 2/4), so this only guards the substrate.
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
	void urbanTypeIsSettleableSurfaceLand() {
		ProvinceType urban = ProvinceType.URBAN;
		assertTrue(urban.isSettleable(), "a colony may found on urban ground");
		assertTrue(urban.isPassable(), "caravans may route through");
		assertTrue(urban.isLand(), "urban is dry land");
		assertFalse(urban.isUnderground(), "urban is a surface terrain");
	}
}
