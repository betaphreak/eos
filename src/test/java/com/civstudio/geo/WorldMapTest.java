package com.civstudio.geo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * Verifies the world map loads from the committed {@code /provinces.json}
 * resource, that its neighbor adjacency is a self-consistent undirected graph,
 * and that a known province (the founding default, Dhenijansar) carries the
 * geography the geography note pins. See {@code docs/geography.md}.
 */
class WorldMapTest {

	@Test
	void loadsTheCommittedSnapshot() {
		WorldMap map = WorldMap.load();
		// pins the committed export snapshot (full Anbennar world, keyed on the
		// distinct province_id after the two double-imported dups were merged)
		assertEquals(5264, map.size());
		assertTrue(map.settleableProvinces().size() < map.size(),
				"some provinces are water, not all settleable");
	}

	@Test
	void adjacencyIsSymmetricAndResolves() {
		WorldMap map = WorldMap.load();
		for (Province p : map.provinces())
			for (int n : p.neighbors()) {
				assertTrue(map.contains(n),
						"province " + p.id() + " neighbor " + n + " missing");
				assertTrue(map.neighbors(n).contains(p.id()),
						"edge " + p.id() + " -> " + n + " is not mirrored");
			}
	}

	@Test
	void dhenijansarHasItsPinnedGeography() {
		WorldMap map = WorldMap.load();
		Province d = map.findByName("Dhenijansar").orElseThrow();
		assertEquals(4411, d.id()); // its province_id (the used id), not a DB surrogate
		assertEquals(23.16, d.latitude(), 1e-6);
		assertEquals(76.43, d.longitude(), 1e-6);
		assertEquals(74, d.plots());
		assertEquals(16, d.waterPlots());
		assertEquals(ProvinceType.LAND, d.type());
		assertTrue(d.isSettleable());
		assertTrue(d.isCoastal());
		assertEquals("rahen_coast_region", d.regionKey());
		assertEquals(Set.of(4385, 4405, 4410, 4412), new HashSet<>(d.neighbors()));
	}

	@Test
	void pathWalksTheNeighborGraph() {
		WorldMap map = WorldMap.load();
		Province d = map.findByName("Dhenijansar").orElseThrow();
		int neighbor = d.neighbors().get(0);
		// a direct neighbor is a two-node path
		assertEquals(List.of(d.id(), neighbor), map.path(d.id(), neighbor));
		// to self is the singleton
		assertEquals(List.of(d.id()), map.path(d.id(), d.id()));
	}

	@Test
	void neighborsAreImmutableAndEndpointsValidated() {
		WorldMap map = WorldMap.load();
		Province d = map.findByName("Dhenijansar").orElseThrow();
		assertThrows(UnsupportedOperationException.class,
				() -> d.neighbors().add(999));
		assertThrows(IllegalArgumentException.class, () -> map.province(-1));
		assertThrows(IllegalArgumentException.class, () -> map.path(d.id(), -1));
	}

	@Test
	void regionKeyIsAStableStringOrNull() {
		WorldMap map = WorldMap.load();
		boolean sawNull = false;
		for (Province p : map.provinces()) {
			String key = p.regionKey();
			if (key == null)
				sawNull = true; // open-ocean provinces have no region
			else
				assertFalse(key.isBlank(), "province " + p.id() + " has a blank region key");
		}
		assertTrue(sawNull, "some provinces have no region");
	}

	@Test
	void seaProvinceIsNotSettleable() {
		WorldMap map = WorldMap.load();
		boolean sawWater = false;
		for (Province p : map.provinces())
			if (p.type() != ProvinceType.LAND) {
				assertFalse(p.isSettleable());
				sawWater = true;
			}
		assertTrue(sawWater);
	}
}
