package com.civstudio.geo.names;

import java.io.BufferedReader;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.zip.GZIPOutputStream;

import com.civstudio.data.GeoNamesFiles;
import com.civstudio.geo.Province;
import com.civstudio.geo.Region;
import com.civstudio.geo.RegionEarthMap;
import com.civstudio.geo.WorldMap;
import com.civstudio.settlement.GameSession;

import tools.jackson.databind.ObjectMapper;

/**
 * Dev tool: distils the 372&nbsp;MB GeoNames {@code allCountries} dump into a small committed
 * <b>subset</b>, {@code generated/geonames/subset.json.gz}, that the plot-naming bake reads instead
 * of the full dump ({@link GeoNamesSubset}). This is what lets <b>production (and CI, and any
 * machine) bake names</b> — the full dump is a dev-only 372&nbsp;MB / 13M-row dependency ([[plot-place-naming]]),
 * and parsing it costs real memory/IO on every bake.
 *
 * <h2>What it keeps</h2>
 * For each of the 146 mapped Earth countries ({@link RegionEarthMap}, a bijection region→country) it
 * keeps the <b>top-K places by population</b>, where {@code K = ceil(maxProvincePlots(region) ×
 * MARGIN)} (floored at {@link #MIN_K}). That bound is correct because plot names are unique
 * <em>per province</em> with cross-province reuse (see {@link PlaceNamer}), so a country's gazetteer
 * only needs enough places to name its <b>largest single province</b>, not the whole region. The
 * same class/population filter as the live bake is applied — {@link GeoNamesGazetteer#parse} is
 * reused verbatim, so the subset is a strict sub-selection of what the full dump would yield.
 *
 * <p><b>Names change once.</b> Bounding drops the low-population tail, so {@link PlaceNamer}'s
 * spatial queries pick different (still real, still deterministic) places than the full dump would.
 * Names are a cosmetic hover label and the plot cache re-bakes on a {@code MAP_VERSION} bump, so this
 * is a one-time re-bake, not a regression. Only tiny countries whose largest province already
 * out-counts their whole place pool (Kuwait, Trinidad) recycle names — and they do so with the full
 * dump too.
 *
 * <pre>
 * mvn -pl civstudio-engine compile exec:exec -Dsim.main=com.civstudio.geo.names.GeoNamesSubsetExporter
 * </pre>
 * Requires the full dump present ({@link GeoNamesFiles}); its output is committed.
 */
public final class GeoNamesSubsetExporter {

	private GeoNamesSubsetExporter() {
	}

	/** Headroom over a region's largest-province plot count, for the spatial spread of the pick. */
	private static final double MARGIN = 1.5;
	/** Floor on the per-country keep count, so a tiny region still gets a usable pool. */
	private static final int MIN_K = 256;

	private static final String OUTPUT = "civstudio-engine/src/main/resources/generated/geonames/subset.json.gz";

	public static void main(String[] args) throws Exception {
		if (!GeoNamesFiles.isAvailable()) {
			System.out.println("GeoNames dump not present in " + GeoNamesFiles.cacheDir().toAbsolutePath()
					+ " — cannot build the subset. See com.civstudio.data.GeoNamesFiles.");
			return;
		}
		RegionEarthMap earth = RegionEarthMap.load();
		WorldMap map = new GameSession(1).getWorldMap();

		// per-country keep budget K = ceil(maxProvincePlots(region) × MARGIN), floored at MIN_K.
		// Each country maps to exactly one region (bijection), so K per country is that region's.
		Map<String, Integer> budget = new LinkedHashMap<>();
		for (Region region : map.regions()) {
			String iso = earth.countryOf(region.rawKey()).orElse(null);
			if (iso == null)
				continue;
			int maxProv = 0;
			for (Province p : map.provincesInRegion(region.rawKey()))
				maxProv = Math.max(maxProv, p.plots());
			budget.put(iso, Math.max(MIN_K, (int) Math.ceil(maxProv * MARGIN)));
		}

		// stream the dump once, keeping only the top-K-by-population per mapped country. A per-country
		// min-heap (lowest keep-priority at the head) evicts the weakest once it overflows K.
		Map<String, PriorityQueue<GeoNamesPlace>> heaps = new LinkedHashMap<>();
		for (String cc : budget.keySet())
			heaps.put(cc, new PriorityQueue<>(evictFirst()));
		long scanned = 0;
		try (BufferedReader in = GeoNamesFiles.open()) {
			for (String line; (line = in.readLine()) != null;) {
				scanned++;
				GeoNamesGazetteer.ParsedRow row = GeoNamesGazetteer.parse(line);
				if (row == null)
					continue;
				PriorityQueue<GeoNamesPlace> heap = heaps.get(row.country());
				if (heap == null)
					continue;
				heap.add(row.place());
				if (heap.size() > budget.get(row.country()))
					heap.poll(); // drop the current weakest
			}
		}

		// serialise: country → its places sorted by id (deterministic, stable diffs), each as a
		// compact [id, name, lat, lon, pop, fclass] tuple.
		Map<String, List<Object[]>> out = new LinkedHashMap<>();
		long total = 0;
		for (Map.Entry<String, PriorityQueue<GeoNamesPlace>> e : heaps.entrySet()) {
			List<GeoNamesPlace> kept = new ArrayList<>(e.getValue());
			kept.sort(Comparator.comparingInt(GeoNamesPlace::id));
			List<Object[]> rows = new ArrayList<>(kept.size());
			for (GeoNamesPlace p : kept)
				rows.add(new Object[] { p.id(), p.name(), p.lat(), p.lon(), p.population(),
						String.valueOf(p.featureClass()) });
			out.put(e.getKey(), rows);
			total += rows.size();
		}

		Path outPath = Path.of(OUTPUT);
		Files.createDirectories(outPath.getParent());
		ObjectMapper mapper = new ObjectMapper();
		try (OutputStream os = new GZIPOutputStream(Files.newOutputStream(outPath))) {
			mapper.writeValue(os, out);
		}
		long gz = Files.size(outPath);
		System.out.printf("scanned %,d dump rows → kept %,d places across %d countries → %s (%,d bytes gz)%n",
				scanned, total, out.size(), outPath.toAbsolutePath(), gz);
	}

	// min-heap order: the head is the FIRST to evict — lowest keep-priority. Keep-priority is
	// (population, then class rank P>A>T>L>H, then lower id), so the head is (lowest pop, lowest rank,
	// highest id). Deterministic, so the kept set is a pure function of the dump.
	private static Comparator<GeoNamesPlace> evictFirst() {
		return Comparator.comparingInt(GeoNamesPlace::population)
				.thenComparingInt(p -> classRank(p.featureClass()))
				.thenComparing(Comparator.comparingInt(GeoNamesPlace::id).reversed());
	}

	private static int classRank(char c) {
		return switch (c) {
			case 'P' -> 4;
			case 'A' -> 3;
			case 'T' -> 2;
			case 'L' -> 1;
			default -> 0; // H and anything else
		};
	}
}
