package com.civstudio.geo.names;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.civstudio.data.GeoNamesFiles;

/**
 * Parses the GeoNames {@code allCountries} dump into per-country
 * {@link CountryGazetteer}s — the real-place-name source for plot naming.
 * <p>
 * Two filters shape what is kept, applied per row in {@link #parse(String)}:
 * <ul>
 * <li>feature class ∈ {@link #FEATURE_CLASSES} ({@code P} populated, {@code A}
 * admin, {@code T} mountain, {@code L} area, {@code H} water) — dropping roads,
 * spots and undersea/other noise;</li>
 * <li>population ≤ {@link #MAX_POPULATION} — so famous big cities never land on a
 * plot; a plot gets the biggest <em>town</em> in range, not the metropolis.</li>
 * </ul>
 * Only the countries requested by {@link #load(BufferedReader, Set)} are
 * retained, so memory scales with the mapped set, not the whole 12M-row dump.
 * This is a bake-time utility (see {@link #loadFromCache(Set)}); the names it
 * yields are baked into the plot cache.
 */
public final class GeoNamesGazetteer {

	private GeoNamesGazetteer() {
	}

	/** Places with more than this population are dropped (no famous big cities). */
	public static final int MAX_POPULATION = 250_000;

	/** Kept feature classes: populated, admin, mountain, area, water. */
	public static final Set<Character> FEATURE_CLASSES = Set.of('P', 'A', 'T', 'L', 'H');

	// allCountries.txt columns (tab-separated, 19 total)
	private static final int COL_ID = 0, COL_NAME = 1, COL_ASCII = 2, COL_LAT = 4, COL_LON = 5,
			COL_FCLASS = 6, COL_CC = 8, COL_POP = 14;

	/** A parsed, kept row: the place plus the ISO country it belongs to. */
	public record ParsedRow(String country, GeoNamesPlace place) {
	}

	/**
	 * Parse one {@code allCountries} line, applying the class/population filters.
	 *
	 * @param line a tab-separated dump row
	 * @return the parsed row, or {@code null} if malformed or filtered out
	 */
	public static ParsedRow parse(String line) {
		if (line == null || line.isEmpty())
			return null;
		String id = null, name = null, ascii = null, lat = null, lon = null, cc = null, pop = null;
		char fclass = ' ';
		int field = 0, start = 0, len = line.length();
		for (int i = 0; i <= len; i++) {
			if (i == len || line.charAt(i) == '\t') {
				switch (field) { // only substring the columns we consume
					case COL_ID -> id = line.substring(start, i);
					case COL_NAME -> name = line.substring(start, i);
					case COL_ASCII -> ascii = line.substring(start, i);
					case COL_LAT -> lat = line.substring(start, i);
					case COL_LON -> lon = line.substring(start, i);
					case COL_FCLASS -> fclass = (i > start) ? line.charAt(start) : ' ';
					case COL_CC -> cc = line.substring(start, i);
					case COL_POP -> pop = line.substring(start, i);
					default -> {
						// unconsumed column
					}
				}
				start = i + 1;
				if (++field > COL_POP)
					break;
			}
		}
		if (pop == null || cc == null || cc.isEmpty())
			return null; // short/malformed row
		if (!FEATURE_CLASSES.contains(fclass))
			return null;
		long population = parseLong(pop);
		if (population > MAX_POPULATION)
			return null;
		double dlat = parseDouble(lat), dlon = parseDouble(lon);
		if (Double.isNaN(dlat) || Double.isNaN(dlon))
			return null;
		String label = (name != null && !name.isEmpty()) ? name : ascii;
		if (label == null || label.isEmpty())
			return null;
		int placeId = (int) parseLong(id);
		return new ParsedRow(cc,
				new GeoNamesPlace(placeId, label, dlat, dlon, (int) Math.min(population, Integer.MAX_VALUE), fclass));
	}

	/**
	 * Build gazetteers for exactly the given countries from a streaming reader over
	 * the dump. Countries with no kept rows still get an empty gazetteer, so the
	 * result key set equals {@code countries}.
	 *
	 * @param in        a reader over {@code allCountries.txt}
	 * @param countries the ISO codes to retain
	 * @return per-country gazetteers, keyed by ISO code
	 * @throws IOException on a read failure
	 */
	public static Map<String, CountryGazetteer> load(BufferedReader in, Set<String> countries)
			throws IOException {
		Map<String, List<GeoNamesPlace>> byCountry = new HashMap<>();
		for (String cc : countries)
			byCountry.put(cc, new ArrayList<>());
		for (String line; (line = in.readLine()) != null;) {
			ParsedRow row = parse(line);
			if (row == null)
				continue;
			List<GeoNamesPlace> bucket = byCountry.get(row.country());
			if (bucket != null)
				bucket.add(row.place());
		}
		Map<String, CountryGazetteer> out = new HashMap<>(byCountry.size() * 2);
		for (Map.Entry<String, List<GeoNamesPlace>> e : byCountry.entrySet())
			out.put(e.getKey(), CountryGazetteer.of(e.getKey(), e.getValue()));
		return out;
	}

	/**
	 * Convenience: {@link #load(BufferedReader, Set)} over the on-disk dump
	 * resolved by {@link GeoNamesFiles}.
	 *
	 * @param countries the ISO codes to retain
	 * @return per-country gazetteers
	 * @throws IOException if the dump is missing or unreadable
	 */
	public static Map<String, CountryGazetteer> loadFromCache(Set<String> countries) throws IOException {
		try (BufferedReader in = GeoNamesFiles.open()) {
			return load(in, countries);
		}
	}

	private static long parseLong(String s) {
		if (s == null || s.isEmpty())
			return 0L;
		try {
			return Long.parseLong(s.trim());
		} catch (NumberFormatException e) {
			return 0L;
		}
	}

	private static double parseDouble(String s) {
		if (s == null || s.isEmpty())
			return Double.NaN;
		try {
			return Double.parseDouble(s.trim());
		} catch (NumberFormatException e) {
			return Double.NaN;
		}
	}
}
