package com.civstudio.geo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * The C2C-ported terrain algorithm ({@link ClimateTerrainGenerator}) — the temperature×humidity band
 * pool (Stage 1), the diversify variants (Stage 2), and the Anbennar adaptations (dry-desert gate,
 * humidity-gated marsh, the barren wasteland pool). Pure logic, no raster.
 */
class ClimateTerrainGeneratorTest {

	@Test
	void hotDryLandsInDesert() {
		Map<String, Double> w = ClimateTerrainGenerator.pool(35, 0.10);
		assertTrue(w.containsKey("TERRAIN_DESERT"), "hot+dry → desert, got " + w);
	}

	@Test
	void coolDryStaysSteppeNotMarsh() {
		// the Anbennar dry-desert gate: a dry province reads desert even when cooled below the C2C hot cutoff
		Map<String, Double> arid = ClimateTerrainGenerator.pool(20, 0.10);
		assertTrue(arid.containsKey("TERRAIN_DESERT"), "cool arid → desert via the dry gate, got " + arid);
		// and a dry province gets no marsh (wetland needs moisture)
		assertFalse(arid.containsKey("TERRAIN_MARSH"), "dry province should not marsh, got " + arid);
	}

	@Test
	void humidTemperateFavoursGrassAndMarsh() {
		Map<String, Double> w = ClimateTerrainGenerator.pool(15, 0.60);
		assertTrue(w.containsKey("TERRAIN_GRASSLAND"), w.toString());
		assertTrue(w.containsKey("TERRAIN_MARSH"), "humid → marsh, got " + w);
		assertFalse(w.containsKey("TERRAIN_DESERT"), "temperate humid → no desert, got " + w);
	}

	@Test
	void coldLandsInTheColdTerrains() {
		Map<String, Double> w = ClimateTerrainGenerator.pool(-15, 0.50);
		assertTrue(w.containsKey("TERRAIN_TUNDRA") || w.containsKey("TERRAIN_PERMAFROST"), "cold → tundra/permafrost, got " + w);
		assertFalse(w.containsKey("TERRAIN_GRASSLAND"), "deep cold → no grass, got " + w);
	}

	@Test
	void diversifyOnlyForTheThreeBaseTerrains() {
		assertEquals(4, ClimateTerrainGenerator.diversify("TERRAIN_DESERT").size());   // desert/salt/dunes/scrub
		assertTrue(ClimateTerrainGenerator.diversify("TERRAIN_GRASSLAND").containsKey("TERRAIN_LUSH"));
		assertTrue(ClimateTerrainGenerator.diversify("TERRAIN_PLAINS").containsKey("TERRAIN_ROCKY"));
		assertNull(ClimateTerrainGenerator.diversify("TERRAIN_MARSH"), "marsh does not diversify");
		assertNull(ClimateTerrainGenerator.diversify("TERRAIN_TAIGA"));
	}

	@Test
	void barrenPoolIsClimateAppropriate() {
		assertTrue(ClimateTerrainGenerator.barrenPool(30).containsKey("TERRAIN_DESERT"), "hot waste → desert");
		assertTrue(ClimateTerrainGenerator.barrenPool(-5).containsKey("TERRAIN_PERMAFROST"), "cold waste → permafrost");
		assertTrue(ClimateTerrainGenerator.barrenPool(15).containsKey("TERRAIN_ROCKY"), "temperate waste → rocky");
	}

	@Test
	void poolNeverEmpty() {
		// a temperature that lands in no band still yields a ground (grassland fallback)
		assertFalse(ClimateTerrainGenerator.pool(1000, 0.5).isEmpty());
	}
}
