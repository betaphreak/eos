package com.civstudio.geo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Unit checks on the EU4 {@code terrain.bmp}/{@code trees.bmp} palette decoding —
 * that the index tables transcribed from {@code data/anbennar/terrain.txt} map to the
 * intended Civ4 terrain/relief, that EU4's relief-in-the-palette is lifted onto the
 * orthogonal {@link PlotType} axis, and that water/unmapped indices decode to a
 * {@code null} ground (so the caller falls back to climate generation).
 */
class MapTerrainCodecTest {

	private static final TerrainRegistry REG = TerrainRegistry.load();

	@Test
	void groundMapsLandIndicesAndNullsWater() {
		assertEquals("TERRAIN_GRASSLAND", MapTerrainCodec.ground(0, REG).type()); // grasslands
		assertEquals("TERRAIN_PLAINS", MapTerrainCodec.ground(4, REG).type());    // plains
		assertEquals("TERRAIN_DESERT", MapTerrainCodec.ground(3, REG).type());    // desert
		assertEquals("TERRAIN_MARSH", MapTerrainCodec.ground(9, REG).type());     // marsh
		assertEquals("TERRAIN_LUSH", MapTerrainCodec.ground(254, REG).type());    // jungle
		// a hill grounds on its underlying ground (grass), not a "hill terrain"
		assertEquals("TERRAIN_GRASSLAND", MapTerrainCodec.ground(1, REG).type());
		// 15/17 ocean, 35 coastline, and any unknown index have no land terrain
		assertNull(MapTerrainCodec.ground(15, REG));
		assertNull(MapTerrainCodec.ground(17, REG));
		assertNull(MapTerrainCodec.ground(35, REG));
		assertNull(MapTerrainCodec.ground(-1, REG));
	}

	@Test
	void reliefLiftsHillsAndMountainsOntoThePlotTypeAxis() {
		assertEquals(PlotType.HILL, MapTerrainCodec.relief(1));  // hills
		assertEquals(PlotType.HILL, MapTerrainCodec.relief(23)); // highlands
		assertEquals(PlotType.PEAK, MapTerrainCodec.relief(6));  // mountain
		assertEquals(PlotType.PEAK, MapTerrainCodec.relief(2));  // desert_mountain
		assertEquals(PlotType.PEAK, MapTerrainCodec.relief(16)); // snow
		assertEquals(PlotType.FLAT, MapTerrainCodec.relief(0));  // grasslands
		assertEquals(PlotType.FLAT, MapTerrainCodec.relief(4));  // plains
	}

	@Test
	void isWoodyMatchesTheTreeBlockCovers() {
		// forest / woods / jungle / palms are woody
		assertTrue(MapTerrainCodec.isWoody(3));  // forest
		assertTrue(MapTerrainCodec.isWoody(13)); // jungle
		assertTrue(MapTerrainCodec.isWoody(12)); // palms
		assertTrue(MapTerrainCodec.isWoody(18)); // woods
		// bare, savana and shadow_swamp are not tree cover
		assertFalse(MapTerrainCodec.isWoody(0));  // bare
		assertFalse(MapTerrainCodec.isWoody(27)); // savana
		assertFalse(MapTerrainCodec.isWoody(31)); // shadow_swamp
		assertFalse(MapTerrainCodec.isWoody(-1)); // no overlay
	}
}
