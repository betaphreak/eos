package com.civstudio.geo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
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
	void loadsTheAreaRegionAndContinentTiers() {
		WorldMap map = WorldMap.load();
		// pins the committed snapshots (non-empty Anbennar blocks; the 7
		// geographic continents, the utility pseudo-continents skipped)
		assertEquals(1570, map.areas().size());
		assertEquals(178, map.regions().size());
		assertEquals(31, map.superRegions().size());
		assertEquals(7, map.continents().size());
		assertFalse(map.hasContinent("debug_continent"));
		assertFalse(map.hasContinent("new_world"));
		assertThrows(IllegalArgumentException.class, () -> map.area("nope"));
		assertThrows(IllegalArgumentException.class, () -> map.region("nope"));
		assertThrows(IllegalArgumentException.class, () -> map.superRegion("nope"));
		assertThrows(IllegalArgumentException.class, () -> map.continent("nope"));
	}

	@Test
	void dhenijansarRollsUpToItsSuperRegion() {
		WorldMap map = WorldMap.load();
		Province d = map.findByName("Dhenijansar").orElseThrow();
		SuperRegion sr = map.superRegionOf(d.id()).orElseThrow();
		assertEquals("rahen_superregion", sr.rawKey());
		assertEquals("Rahen", sr.name());
		// restrict_charter must not have leaked in as a "region"
		assertFalse(sr.regionKeys().contains("restrict_charter"));
		// the super-region nests its region and (transitively) the province
		Region region = map.regionOf(d.id()).orElseThrow();
		assertTrue(map.regionsInSuperRegion("rahen_superregion").contains(region));
		assertTrue(map.provincesInSuperRegion("rahen_superregion").contains(d));
		assertThrows(UnsupportedOperationException.class,
				() -> map.provincesInSuperRegion("rahen_superregion").clear());
	}

	@Test
	void dhenijansarSitsOnItsContinent() {
		WorldMap map = WorldMap.load();
		Province d = map.findByName("Dhenijansar").orElseThrow();
		assertEquals("asia", d.continentKey());
		Continent c = map.continentOf(d.id()).orElseThrow();
		assertEquals("asia", c.rawKey());
		assertEquals("Asia", c.name());
		assertTrue(map.provincesInContinent("asia").contains(d));
		assertThrows(UnsupportedOperationException.class,
				() -> map.provincesInContinent("asia").clear());
	}

	@Test
	void dhenijansarSitsInItsAreaAndRegion() {
		WorldMap map = WorldMap.load();
		Province d = map.findByName("Dhenijansar").orElseThrow();
		// province -> area -> region hierarchy resolves both ways
		assertEquals("inner_rahen_area", d.areaKey());
		Area area = map.areaOf(d.id()).orElseThrow();
		assertEquals("inner_rahen_area", area.rawKey());
		assertEquals("Inner Rahen", area.name());
		assertTrue(map.provincesInArea("inner_rahen_area").contains(d));

		Region region = map.regionOf(d.id()).orElseThrow();
		assertEquals("rahen_coast_region", region.rawKey());
		assertEquals("Rahen Coast", region.name());
		assertTrue(map.areasInRegion("rahen_coast_region").contains(area));
		// region membership is the union of its areas' provinces
		assertTrue(map.provincesInRegion("rahen_coast_region").contains(d));
	}

	@Test
	void hierarchyIsInternallyConsistent() {
		WorldMap map = WorldMap.load();
		// a province resolved up the tier must appear back in that tier's
		// membership (areas are the source of truth — the province's own
		// region_key is the legacy DB value and a handful are stale, so we check
		// the area tier's internal consistency, not agreement with region_key)
		boolean sawArea = false, sawRegion = false;
		for (Province p : map.provinces()) {
			Optional<Area> a = map.areaOf(p.id());
			if (a.isPresent()) {
				assertTrue(map.provincesInArea(a.get().rawKey()).contains(p),
						"province " + p.id() + " missing from its area");
				sawArea = true;
			}
			Optional<Region> r = map.regionOf(p.id());
			if (r.isPresent()) {
				assertTrue(map.provincesInRegion(r.get().rawKey()).contains(p),
						"province " + p.id() + " missing from its region");
				sawRegion = true;
			}
		}
		assertTrue(sawArea, "some province resolves an area");
		assertTrue(sawRegion, "some province resolves a region via its area");
	}

	@Test
	void membershipListsAreImmutable() {
		WorldMap map = WorldMap.load();
		assertThrows(UnsupportedOperationException.class,
				() -> map.provincesInRegion("rahen_coast_region").clear());
		assertThrows(UnsupportedOperationException.class,
				() -> map.areasInRegion("rahen_coast_region").clear());
		assertThrows(UnsupportedOperationException.class,
				() -> map.provincesInArea("inner_rahen_area").clear());
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
