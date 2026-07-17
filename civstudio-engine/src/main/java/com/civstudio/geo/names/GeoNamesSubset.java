package com.civstudio.geo.names;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Loads the committed GeoNames <b>subset</b> ({@code /generated/geonames/subset.json.gz}) into
 * per-country {@link CountryGazetteer}s — the same shape {@link GeoNamesGazetteer#loadFromCache}
 * produces from the full dump, but from a ~4&nbsp;MB committed file instead of the 372&nbsp;MB
 * {@code allCountries} dump.
 * <p>
 * This is what lets <b>any</b> machine bake plot names — production, CI, a fresh clone — since the
 * subset ships in the engine jar. The full dump is now needed only to <em>rebuild</em> the subset
 * ({@link GeoNamesSubsetExporter}). See {@code docs/plot-place-naming.md}.
 * <p>
 * The file is a JSON object {@code {"<CC>": [[id, name, lat, lon, pop, "<class>"], …], …}} — one
 * compact tuple per place, countries and places in a deterministic order.
 */
public final class GeoNamesSubset {

	private GeoNamesSubset() {
	}

	/**
	 * Classpath resource of the committed subset. The source file lives at
	 * {@code src/main/resources/generated/geonames/subset.json.gz}, but the {@code generated/} tree is
	 * mounted at the classpath <em>root</em> by the engine pom (like {@code /map/provinces.json}), so
	 * on the classpath it is {@code /geonames/subset.json.gz}.
	 */
	public static final String RESOURCE = "/geonames/subset.json.gz";

	private static final ObjectMapper MAPPER = new ObjectMapper();

	/** Whether the committed subset is on the classpath (it is, after a bake of the exporter). */
	public static boolean isAvailable() {
		return GeoNamesSubset.class.getResource(RESOURCE) != null;
	}

	/**
	 * Build gazetteers for the given countries from the committed subset. A country absent from the
	 * file (or with no kept places) yields an empty gazetteer, so the key set equals {@code countries}
	 * — matching {@link GeoNamesGazetteer#load}.
	 *
	 * @param countries the ISO codes to retain
	 * @return per-country gazetteers, keyed by ISO code
	 * @throws IOException if the resource is missing or unreadable
	 */
	public static Map<String, CountryGazetteer> load(Set<String> countries) throws IOException {
		Map<String, List<GeoNamesPlace>> byCountry = new HashMap<>();
		for (String cc : countries)
			byCountry.put(cc, new ArrayList<>());
		try (InputStream raw = GeoNamesSubset.class.getResourceAsStream(RESOURCE)) {
			if (raw == null)
				throw new IOException("GeoNames subset not on the classpath: " + RESOURCE);
			try (InputStream gz = new GZIPInputStream(raw)) {
				JsonNode root = MAPPER.readTree(gz);
				for (String cc : countries) {
					JsonNode arr = root.get(cc);
					if (arr == null)
						continue;
					List<GeoNamesPlace> bucket = byCountry.get(cc);
					for (JsonNode t : arr)
						bucket.add(new GeoNamesPlace(t.get(0).asInt(), t.get(1).asString(),
								t.get(2).asDouble(), t.get(3).asDouble(), t.get(4).asInt(),
								t.get(5).asString().charAt(0)));
				}
			}
		}
		Map<String, CountryGazetteer> out = new HashMap<>(byCountry.size() * 2);
		for (Map.Entry<String, List<GeoNamesPlace>> e : byCountry.entrySet())
			out.put(e.getKey(), CountryGazetteer.of(e.getKey(), e.getValue()));
		return out;
	}
}
