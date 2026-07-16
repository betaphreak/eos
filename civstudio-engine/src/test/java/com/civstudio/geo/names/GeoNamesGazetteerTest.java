package com.civstudio.geo.names;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.IntPredicate;

import org.junit.jupiter.api.Test;

/**
 * Fixture-based checks for the GeoNames parser, its class/population filters, and
 * the two {@link CountryGazetteer} spatial queries — no multi-GB dump required.
 */
class GeoNamesGazetteerTest {

	/** Build a 19-column allCountries row with only the consumed columns set. */
	private static String row(int id, String name, double lat, double lon, char fclass, String cc,
			long pop) {
		String[] f = new String[19];
		java.util.Arrays.fill(f, "");
		f[0] = Integer.toString(id);
		f[1] = name;
		f[4] = Double.toString(lat);
		f[5] = Double.toString(lon);
		f[6] = String.valueOf(fclass);
		f[8] = cc;
		f[14] = Long.toString(pop);
		return String.join("\t", f);
	}

	@Test
	void parseAppliesClassAndPopulationFilters() {
		// kept: populated town under the cap
		assertNotNull(GeoNamesGazetteer.parse(row(1, "Town", 10, 10, 'P', "XX", 5000)));
		// dropped: over the 250k population cap
		assertNull(GeoNamesGazetteer.parse(row(2, "Metropolis", 10, 10, 'P', "XX", 500_000)));
		// dropped: excluded feature class (road)
		assertNull(GeoNamesGazetteer.parse(row(3, "Road", 10, 10, 'R', "XX", 0)));
		// kept: population-0 natural feature (mountain) — fallback fill
		GeoNamesGazetteer.ParsedRow hill = GeoNamesGazetteer.parse(row(4, "Peak", 10, 10, 'T', "XX", 0));
		assertNotNull(hill);
		assertEquals("XX", hill.country());
		assertEquals("Peak", hill.place().name());
		// dropped: malformed short row
		assertNull(GeoNamesGazetteer.parse("just\tone\ttwo"));
	}

	@Test
	void loadBucketsByCountry() throws IOException {
		String dump = String.join("\n",
				row(1, "Bigtown", 10.00, 10.00, 'P', "XX", 200_000),
				row(2, "Smalltown", 10.01, 10.01, 'P', "XX", 5_000),
				row(3, "Hillfort", 10.005, 10.005, 'T', "XX", 0),
				row(4, "Megacity", 10.00, 10.00, 'P', "XX", 500_000), // filtered (pop)
				row(5, "Route7", 10.00, 10.00, 'R', "XX", 0),         // filtered (class)
				row(6, "Elsewhere", 50.0, 50.0, 'P', "YY", 1_000));
		Map<String, CountryGazetteer> g = GeoNamesGazetteer.load(
				new BufferedReader(new StringReader(dump)), Set.of("XX", "YY"));
		assertEquals(3, g.get("XX").size()); // Megacity + Route7 excluded
		assertEquals(1, g.get("YY").size());
	}

	@Test
	void largestInRectPrefersPopulationAndSkipsUsed() throws IOException {
		CountryGazetteer xx = load3();
		Set<Integer> used = new HashSet<>();
		IntPredicate isUsed = used::contains;

		GeoNamesPlace a = xx.largestInRect(9.9, 10.1, 9.9, 10.1, isUsed);
		assertEquals("Bigtown", a.name()); // pop 200k wins
		used.add(a.id());
		GeoNamesPlace b = xx.largestInRect(9.9, 10.1, 9.9, 10.1, isUsed);
		assertEquals("Smalltown", b.name()); // pop 5k next (over pop-0 hill)
		used.add(b.id());
		GeoNamesPlace c = xx.largestInRect(9.9, 10.1, 9.9, 10.1, isUsed);
		assertEquals("Hillfort", c.name()); // the pop-0 feature remains
		used.add(c.id());
		assertNull(xx.largestInRect(9.9, 10.1, 9.9, 10.1, isUsed)); // exhausted
	}

	@Test
	void nearestUnusedReturnsClosestAndRespectsUsed() throws IOException {
		CountryGazetteer xx = load3();
		Set<Integer> used = new HashSet<>();
		// point nearest Smalltown (10.01,10.01)
		GeoNamesPlace n = xx.nearestUnused(10.02, 10.02, used::contains);
		assertEquals("Smalltown", n.name());
		used.add(n.id());
		// with Smalltown used, next nearest is Hillfort (10.005,10.005)
		GeoNamesPlace n2 = xx.nearestUnused(10.02, 10.02, used::contains);
		assertEquals("Hillfort", n2.name());
	}

	@Test
	void emptyCountryGazetteerAnswersNull() {
		CountryGazetteer empty = CountryGazetteer.of("ZZ", java.util.List.of());
		assertTrue(empty.isEmpty());
		assertNull(empty.nearestUnused(0, 0, i -> false));
		assertNull(empty.largestInRect(-1, 1, -1, 1, i -> false));
	}

	private static CountryGazetteer load3() throws IOException {
		String dump = String.join("\n",
				row(1, "Bigtown", 10.00, 10.00, 'P', "XX", 200_000),
				row(2, "Smalltown", 10.01, 10.01, 'P', "XX", 5_000),
				row(3, "Hillfort", 10.005, 10.005, 'T', "XX", 0));
		return GeoNamesGazetteer.load(new BufferedReader(new StringReader(dump)), Set.of("XX")).get("XX");
	}
}
