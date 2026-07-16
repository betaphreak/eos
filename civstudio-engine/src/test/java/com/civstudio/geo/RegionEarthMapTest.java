package com.civstudio.geo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * Verifies the committed region→Earth-country mapping loads, holds its
 * uniqueness invariant, and covers every live land region of the world map
 * (so a renamed or newly-added region can't silently go unnamed).
 */
class RegionEarthMapTest {

	@Test
	void loadsAndCountriesAreUnique() {
		RegionEarthMap map = RegionEarthMap.load();
		// 145 ISO regions + shadow_swamp (BW) = 146
		assertEquals(146, map.size());
		// load() already rejects duplicates; assert the invariant explicitly too
		List<String> codes = new ArrayList<>(map.countries());
		assertEquals(codes.size(), new HashSet<>(codes).size(),
				"every region maps to a unique country");
	}

	@Test
	void knownRegionsResolve() {
		RegionEarthMap map = RegionEarthMap.load();
		assertEquals("IN", map.countryOf("rahen_coast_region").orElseThrow());
		assertEquals("US", map.countryOf("titanoflora_riverlands_region").orElseThrow());
		assertEquals("BW", map.countryOf("shadow_swamp_region").orElseThrow());
		// deepwoods_portal was Transnistria (PMR) → replaced with a real country
		assertEquals("IT", map.countryOf("deepwoods_portal_region").orElseThrow());
		assertTrue(map.countryOf("no_such_region").isEmpty());
	}

	@Test
	void coversEveryLiveLandRegion() {
		RegionEarthMap map = RegionEarthMap.load();
		WorldMap world = WorldMap.load();
		// every region a settleable (land) province belongs to must be mapped
		Set<String> landRegions = new HashSet<>();
		for (Province p : world.settleableProvinces())
			world.regionOf(p.id()).map(Region::rawKey).ifPresent(landRegions::add);
		assertFalse(landRegions.isEmpty(), "sanity: some land regions exist");
		// throws with the offending keys if any live land region is unmapped
		map.validateCoverage(landRegions);
	}

	@Test
	void validateCoverageFlagsAnUnmappedRegion() {
		RegionEarthMap map = RegionEarthMap.load();
		assertThrows(IllegalStateException.class,
				() -> map.validateCoverage(List.of("totally_made_up_region")));
		// but the excluded dev-only region is tolerated
		map.validateCoverage(List.of("debug_region"));
	}
}
