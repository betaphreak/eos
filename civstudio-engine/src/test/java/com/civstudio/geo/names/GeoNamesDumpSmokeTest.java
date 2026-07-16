package com.civstudio.geo.names;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.BufferedReader;
import java.io.IOException;

import org.junit.jupiter.api.Test;

import com.civstudio.data.GeoNamesFiles;

/**
 * Real-data smoke test over the actual GeoNames {@code allCountries} dump. It is
 * <em>gated</em> on the dump being present ({@link GeoNamesFiles#isAvailable()}),
 * so it runs on a dev box that has dropped the archive into {@code .geonames-cache}
 * and self-skips in CI (where the multi-GB, gitignored dump is absent).
 * <p>
 * Scans a bounded prefix of the dump — enough to confirm the archive opens, rows
 * parse, the population/class filters bite, and UTF-8 names survive.
 */
class GeoNamesDumpSmokeTest {

	private static final int SCAN_LIMIT = 500_000;

	@Test
	void parsesRealRowsWithFiltersAndUtf8() throws IOException {
		assumeTrue(GeoNamesFiles.isAvailable(),
				"GeoNames dump not present in .geonames-cache — skipping real-data smoke test");

		int scanned = 0, kept = 0, populatedTowns = 0, nonAscii = 0, overCap = 0;
		String sampleUtf8 = null;
		try (BufferedReader in = GeoNamesFiles.open()) {
			for (String line; scanned < SCAN_LIMIT && (line = in.readLine()) != null;) {
				scanned++;
				GeoNamesGazetteer.ParsedRow row = GeoNamesGazetteer.parse(line);
				if (row == null)
					continue;
				kept++;
				GeoNamesPlace p = row.place();
				if (p.population() > GeoNamesGazetteer.MAX_POPULATION)
					overCap++; // must stay 0 — the parser drops these
				if (p.population() > 0 && p.population() <= GeoNamesGazetteer.MAX_POPULATION)
					populatedTowns++;
				if (!p.name().chars().allMatch(c -> c < 128)) {
					nonAscii++;
					if (sampleUtf8 == null)
						sampleUtf8 = p.name();
				}
			}
		}
		System.out.printf(
				"GeoNames smoke: scanned=%d kept=%d towns(1..250k)=%d nonAscii=%d overCap=%d sampleUtf8=%s%n",
				scanned, kept, populatedTowns, nonAscii, overCap, sampleUtf8);

		assertTrue(kept > 100, "expected many kept places in the first " + SCAN_LIMIT + " rows");
		assertTrue(populatedTowns > 0, "expected some real towns under the population cap");
		assertTrue(nonAscii > 0, "expected UTF-8 (diacritic/non-Latin) names to survive parsing");
		assertTrue(overCap == 0, "the >250k population filter must drop big cities");
	}
}
