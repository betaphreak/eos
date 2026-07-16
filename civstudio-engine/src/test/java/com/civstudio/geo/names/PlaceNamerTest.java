package com.civstudio.geo.names;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * Verifies the {@link PlaceNamer}: position-preserving picks, per-province
 * uniqueness, and graceful overflow when the country's pool is exhausted.
 */
class PlaceNamerTest {

	// a country spanning lat [10,20] × lon [10,20] with places at the corners + center
	private static CountryGazetteer corners() {
		return CountryGazetteer.of("XX", List.of(
				new GeoNamesPlace(1, "Northwest", 20, 10, 100, 'P'),
				new GeoNamesPlace(2, "Northeast", 20, 20, 100, 'P'),
				new GeoNamesPlace(3, "Southwest", 10, 10, 100, 'P'),
				new GeoNamesPlace(4, "Southeast", 10, 20, 100, 'P'),
				new GeoNamesPlace(5, "Center", 15, 15, 100, 'P')));
	}

	@Test
	void positionMapsCornerToCorner() {
		PixelBox region = new PixelBox(0, 0, 9, 9); // 10×10 pixels
		PlaceNamer namer = new PlaceNamer(region, corners());
		namer.beginProvince();
		// pixel (0,0) = NW of the region → NW of the country
		assertEquals("Northwest", namer.name(0, 0));
		assertEquals("Northeast", namer.name(9, 0));
		assertEquals("Southwest", namer.name(0, 9));
		assertEquals("Southeast", namer.name(9, 9));
		assertEquals("Center", namer.name(4, 4));
	}

	@Test
	void namesAreUniqueWithinProvinceEvenWhenExhausted() {
		PixelBox region = new PixelBox(0, 0, 9, 9);
		PlaceNamer namer = new PlaceNamer(region, corners()); // only 5 real places
		namer.beginProvince();
		Set<String> names = new HashSet<>();
		// name all 100 pixels — far more than the 5 places, forcing suffix overflow
		for (int y = 0; y < 10; y++)
			for (int x = 0; x < 10; x++)
				assertTrue(names.add(namer.name(x, y)),
						"every plot name must be unique within the province");
		assertEquals(100, names.size());
		// the five real names are all present as bases
		assertTrue(names.contains("Northwest") && names.contains("Center"));
		// overflow reuses a real base with a numeric suffix
		assertTrue(names.stream().anyMatch(n -> n.matches(".+ \\d+")));
	}

	@Test
	void provincesResetIndependently() {
		PixelBox region = new PixelBox(0, 0, 9, 9);
		PlaceNamer namer = new PlaceNamer(region, corners());
		namer.beginProvince();
		String a = namer.name(0, 0);
		namer.beginProvince(); // new province — used-set cleared
		String b = namer.name(0, 0);
		// same position, same region → same real place is available again
		assertEquals(a, b);
		assertEquals("Northwest", b);
	}

	@Test
	void isDeterministic() {
		PixelBox region = new PixelBox(0, 0, 4, 4);
		String first = runAll(new PlaceNamer(region, corners()));
		String second = runAll(new PlaceNamer(region, corners()));
		assertEquals(first, second);
		assertNotEquals("", first);
	}

	private static String runAll(PlaceNamer namer) {
		StringBuilder sb = new StringBuilder();
		namer.beginProvince();
		for (int y = 0; y < 5; y++)
			for (int x = 0; x < 5; x++)
				sb.append(namer.name(x, y)).append('|');
		return sb.toString();
	}
}
