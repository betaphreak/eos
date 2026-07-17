package com.civstudio.geo.names;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.civstudio.geo.Province;
import com.civstudio.geo.Region;
import com.civstudio.geo.RegionEarthMap;
import com.civstudio.geo.WorldMap;
import com.civstudio.settlement.GameSession;

/**
 * The committed GeoNames subset ({@link GeoNamesSubset}) — that it ships on the classpath, covers
 * every mapped country, and (the load-bearing property) keeps enough places per country to name that
 * country's <b>largest single province</b> without pool exhaustion. See {@code docs/plot-place-naming.md}.
 */
class GeoNamesSubsetTest {

	private final WorldMap world = new GameSession(1).getWorldMap();
	private final RegionEarthMap earth = RegionEarthMap.load();

	@Test
	void subsetShipsOnTheClasspath() {
		assertTrue(GeoNamesSubset.isAvailable(), "committed subset " + GeoNamesSubset.RESOURCE
				+ " must be on the classpath (run GeoNamesSubsetExporter)");
	}

	@Test
	void coversEveryMappedCountry() throws IOException {
		Set<String> countries = new HashSet<>(earth.countries());
		Map<String, CountryGazetteer> gaz = GeoNamesSubset.load(countries);
		assertEquals(countries, gaz.keySet(), "one gazetteer per mapped country");
		for (Map.Entry<String, CountryGazetteer> e : gaz.entrySet())
			assertFalse(e.getValue().isEmpty(), e.getKey() + " has no places in the subset");
	}

	@Test
	void eachCountryPoolCoversItsLargestProvince() throws IOException {
		// Uniqueness is per province with cross-province reuse (PlaceNamer), so a country's pool need
		// only name its largest province. Kuwait/Trinidad's largest province out-counts even the full
		// dump's pool (they recycle names with the dump too), so they are allowed to fall short.
		Set<String> poolLimited = Set.of("KW", "TT");
		Map<String, CountryGazetteer> gaz = GeoNamesSubset.load(new HashSet<>(earth.countries()));
		for (Region region : world.regions()) {
			String iso = earth.countryOf(region.rawKey()).orElse(null);
			if (iso == null || poolLimited.contains(iso))
				continue;
			int maxProv = 0;
			for (Province p : world.provincesInRegion(region.rawKey()))
				maxProv = Math.max(maxProv, p.plots());
			int pool = gaz.get(iso).size();
			assertTrue(pool >= maxProv, region.rawKey() + " (" + iso + ") pool=" + pool
					+ " < largest province " + maxProv + " — would exhaust and recycle names");
		}
	}

	@Test
	void placesRoundTripWithAValidBox() throws IOException {
		CountryGazetteer fr = GeoNamesSubset.load(Set.of("FR")).get("FR");
		assertFalse(fr.isEmpty(), "France should have places");
		assertTrue(fr.latMin() <= fr.latMax() && fr.lonMin() <= fr.lonMax(), "valid lat/lon box");
	}
}
