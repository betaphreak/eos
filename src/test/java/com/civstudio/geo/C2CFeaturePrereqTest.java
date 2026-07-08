package com.civstudio.geo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.civstudio.util.Rng;

/**
 * Locks the three prerequisites shared by the C2C {@code addFeatures} port (see
 * {@code docs/c2c-generator-port.md}): the Python-scale temperature tent, the
 * {@link PyTerrain} category classifier (and its name swap), and the {@link
 * WeightedPick} port of {@code probabilityArray}. Getting any of these wrong shifts
 * every ported feature threshold, so they are pinned independently of the map.
 */
class C2CFeaturePrereqTest {

	private static Terrain terrain(String type) {
		return new Terrain(type, new int[] { 0, 0, 0 }, true, 0, 0, 1);
	}

	@Test
	void temperatureTentMatchesThePythonScale() {
		// getTileTemperature: equator = climateTemperature (40), pole = (90*0.4)-50 = -14
		assertEquals(40.0, ClimateProfile.pyTemperature(0), 1e-9, "equator");
		assertEquals(-14.0, ClimateProfile.pyTemperature(90), 1e-9, "pole");
		assertEquals(-14.0, ClimateProfile.pyTemperature(-90), 1e-9, "south pole (symmetric)");
		// linear tent: the midpoint latitude is the midpoint temperature
		assertEquals(13.0, ClimateProfile.pyTemperature(45), 1e-9, "mid-latitude");
		assertEquals(-14.0, ClimateProfile.LOWEST_TEMPERATURE, 1e-9, "lowest = -14 with defaults");
	}

	@Test
	void pyCategoryHonoursTheColdTerrainNameSwap() {
		// the swap: eos TAIGA = script "tundra"; eos TUNDRA = script "permafrost";
		// eos ICE/PERMAFROST = script "snow" — all cold (no jungle)
		assertEquals(PyTerrain.TUNDRA, PyTerrain.of(terrain("TERRAIN_TAIGA")));
		assertEquals(PyTerrain.PERMAFROST, PyTerrain.of(terrain("TERRAIN_TUNDRA")));
		assertEquals(PyTerrain.SNOW, PyTerrain.of(terrain("TERRAIN_ICE")));
		assertEquals(PyTerrain.SNOW, PyTerrain.of(terrain("TERRAIN_PERMAFROST")));
		assertTrue(PyTerrain.of(terrain("TERRAIN_TAIGA")).isCold());
		assertFalse(PyTerrain.of(terrain("TERRAIN_GRASSLAND")).isCold());

		// diversified variants fold onto their parent category
		assertEquals(PyTerrain.DESERT, PyTerrain.of(terrain("TERRAIN_DUNES")));
		assertEquals(PyTerrain.DESERT, PyTerrain.of(terrain("TERRAIN_SCRUB")));
		assertEquals(PyTerrain.PLAINS, PyTerrain.of(terrain("TERRAIN_ROCKY")));
		assertEquals(PyTerrain.GRASS, PyTerrain.of(terrain("TERRAIN_LUSH")));
		assertEquals(PyTerrain.MARSH, PyTerrain.of(terrain("TERRAIN_MARSH")));
	}

	@Test
	void weightedPickIsProportionalAndAccumulatesRepeats() {
		// empty bag -> null; ignores non-positive weights
		WeightedPick<String> empty = new WeightedPick<>();
		empty.add(0, "zero").add(-3, "neg");
		assertTrue(empty.isEmpty());
		assertNull(empty.randomItem(new Rng(1)));

		// a value added twice accumulates: {A: 1+1=2, B: 2} -> A and B roughly equal
		Rng rng = new Rng(12345);
		Map<String, Integer> hits = new HashMap<>();
		for (int i = 0; i < 20000; i++) {
			WeightedPick<String> bag = new WeightedPick<>();
			bag.add(1, "A").add(1, "A").add(2, "B");
			hits.merge(bag.randomItem(rng), 1, Integer::sum);
		}
		double ratio = hits.get("A") / (double) hits.get("B");
		assertTrue(ratio > 0.9 && ratio < 1.1,
				"accumulated A (2) should ~match B (2), ratio=" + ratio);

		// null is a legitimate stored value (a "no feature" outcome)
		WeightedPick<String> withNone = new WeightedPick<>();
		withNone.add(1000, null).add(1, "rare");
		int none = 0;
		for (int i = 0; i < 1000; i++)
			if (withNone.randomItem(rng) == null)
				none++;
		assertTrue(none > 900, "the heavy null entry should dominate, got " + none);
	}
}
