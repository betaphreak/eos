package com.civstudio.geo;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import com.fasterxml.jackson.annotation.JsonProperty;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Maps each Anbennar {@link Region region} to exactly one Earth country, the
 * source of the real place names stamped onto that region's plots (see the
 * plot place-naming feature: {@code GeoNamesGazetteer} / {@code PlaceNamer}).
 * Loaded once from the committed, hand-editable {@code /geo/region-earth-map.json}
 * resource — pure reference data, independent of seed and run.
 * <p>
 * The mapping is a bijection by design: every value is a <em>unique</em>
 * ISO&nbsp;3166-1 alpha-2 country code (so two regions never draw from the same
 * gazetteer), enforced at {@link #load()}. Only <em>land</em> regions are mapped;
 * ocean/sea regions have no land plots and so no names. One code is non-standard:
 * {@code "XK"} (Kosovo) is user-assigned rather than official ISO, but GeoNames
 * uses it, so it is accepted as-is.
 * <p>
 * Region keys in {@link #EXCLUDED_REGIONS} are intentionally left unmapped and
 * must be skipped by callers (dev-only, no player-visible land plots).
 *
 * @see #validateCoverage(Collection)
 */
public final class RegionEarthMap {

	private static final String RESOURCE = "/geo/region-earth-map.json";

	/**
	 * Region keys deliberately left unmapped — dev-only artifacts with no
	 * player-visible land plots. {@link #validateCoverage(Collection)} ignores
	 * these rather than failing on them.
	 */
	public static final Set<String> EXCLUDED_REGIONS = Set.of("debug_region");

	// ISO 3166-1 alpha-2 codes (plus the user-assigned XK for Kosovo, same shape)
	private static final Pattern CODE = Pattern.compile("[A-Z]{2}");

	private static final ObjectMapper MAPPER = JsonMapper.builder()
			.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
			.build();

	// region raw_key -> country code, in file order (deterministic iteration)
	private final Map<String, String> regionToCountry;

	private RegionEarthMap(Map<String, String> regionToCountry) {
		this.regionToCountry = regionToCountry;
	}

	/**
	 * Load and validate the committed mapping.
	 *
	 * @return the loaded mapping
	 * @throws IllegalStateException
	 *             if the resource is missing/empty, a value is malformed, or a
	 *             country code is used by more than one region (the bijection
	 *             invariant)
	 */
	public static RegionEarthMap load() {
		RegionMapFile file;
		try (InputStream in = com.civstudio.data.WorldSources.current().open(RESOURCE)) {
			if (in == null)
				throw new IllegalStateException(
						"Region→Earth map resource not found: " + RESOURCE);
			file = MAPPER.readValue(in, RegionMapFile.class);
		} catch (IOException e) {
			throw new UncheckedIOException(
					"Failed to load region→Earth map: " + RESOURCE, e);
		}
		if (file == null || file.regions() == null || file.regions().isEmpty())
			throw new IllegalStateException(
					"Region→Earth map has no regions: " + RESOURCE);

		Map<String, String> regions = new LinkedHashMap<>();
		// country code -> first region that claimed it, to report duplicates
		Map<String, String> owners = new LinkedHashMap<>();
		for (Map.Entry<String, String> e : file.regions().entrySet()) {
			String region = e.getKey();
			String code = e.getValue();
			if (code == null || !CODE.matcher(code).matches())
				throw new IllegalStateException("Region→Earth map: region '"
						+ region + "' has a malformed country code: " + code);
			String prior = owners.putIfAbsent(code, region);
			if (prior != null)
				throw new IllegalStateException("Region→Earth map: country '"
						+ code + "' is used by both '" + prior + "' and '"
						+ region + "' (each country must be unique)");
			regions.put(region, code);
		}
		return new RegionEarthMap(Map.copyOf(regions));
	}

	/**
	 * The Earth country code mapped to this region, if any.
	 *
	 * @param regionKey a region {@code raw_key}
	 * @return the mapped country code, or empty if the region is unmapped
	 */
	public Optional<String> countryOf(String regionKey) {
		return Optional.ofNullable(regionToCountry.get(regionKey));
	}

	/** All mapped region {@code raw_key}s, in file order (unmodifiable). */
	public Set<String> mappedRegions() {
		return regionToCountry.keySet();
	}

	/** All mapped country codes, in file order (unmodifiable). */
	public Collection<String> countries() {
		return regionToCountry.values();
	}

	/** Number of mapped regions. */
	public int size() {
		return regionToCountry.size();
	}

	/**
	 * Fail-fast coverage check: assert every given land-region key is mapped
	 * (ignoring {@link #EXCLUDED_REGIONS}). Call this at bake time with the live
	 * set of land regions so a newly-added or renamed region can't silently go
	 * unnamed.
	 *
	 * @param landRegionKeys the {@code raw_key}s of every region that carries land
	 *                       plots
	 * @throws IllegalStateException if any of them is unmapped
	 */
	public void validateCoverage(Collection<String> landRegionKeys) {
		List<String> missing = landRegionKeys.stream()
				.filter(k -> k != null)
				.filter(k -> !EXCLUDED_REGIONS.contains(k))
				.filter(k -> !regionToCountry.containsKey(k))
				.distinct()
				.sorted()
				.toList();
		if (!missing.isEmpty())
			throw new IllegalStateException(
					"Region→Earth map is missing " + missing.size()
							+ " land region(s): " + missing);
	}

	/** The JSON envelope: only the {@code regions} object is consumed. */
	private record RegionMapFile(@JsonProperty("regions") Map<String, String> regions) {
	}
}
