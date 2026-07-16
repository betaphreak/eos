package com.civstudio.geo.export;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.civstudio.data.GeoNamesFiles;
import com.civstudio.geo.Province;
import com.civstudio.geo.ProvincePlotField;
import com.civstudio.geo.ProvincePlotField.ProvincePlot;
import com.civstudio.geo.ProvinceRaster;
import com.civstudio.geo.RegionEarthMap;
import com.civstudio.geo.TerrainRegistry;
import com.civstudio.geo.WorldMap;
import com.civstudio.geo.names.CountryGazetteer;
import com.civstudio.geo.names.GeoNamesGazetteer;
import com.civstudio.geo.names.PlaceNamingPass;
import com.civstudio.settlement.Plot;
import com.civstudio.settlement.ProvincePlotStore;
import com.civstudio.util.Rng;
import com.civstudio.util.RngSeed;

/**
 * Dev tool: generate and persist the plot field of <b>every settleable land
 * province</b> into the shared plot cache ({@code .plot-cache/v<GEN_VERSION>/<id>.json.gz} by
 * default — the same cache the sim and the server's {@code PlotService} share), pre-warming
 * per-plot terrain for the whole world (not just the caravan-visited crop). The fields
 * are canonical/seed-independent (the {@linkplain RngSeed#forProvinceCanonical terrain
 * stream is seed-independent}), so this equals what any run would generate on demand;
 * it just pre-warms the whole map at once. Skips provinces already persisted. Run:
 *
 * <pre>
 * mvn -q compile exec:exec -Dsim.main=com.civstudio.geo.export.WorldPlotGenerator
 * </pre>
 *
 * The generated caches are large (hundreds of MB) and regenerable, so they are
 * gitignored — this tool is how a clone rebuilds them for the world map.
 */
public final class WorldPlotGenerator {

	private WorldPlotGenerator() {
	}

	public static void main(String[] args) throws Exception {
		WorldMap map = WorldMap.load();
		TerrainRegistry registry = TerrainRegistry.load();
		ProvinceRaster raster = ProvinceRaster.load();
		RngSeed rngSeed = new RngSeed(1); // canonical stream is seed-independent
		// the GEN_VERSION-versioned dir the store reads from — a generation bump warms a fresh dir
		File dir = ProvincePlotStore.writeDir();
		dir.mkdirs();

		// every non-RNW province: LAND + IMPASSABLE wasteland grow a land field, SEA/LAKE grow a
		// coastal-shelf water field (ProvincePlotField branches on type). RNW/Unused are already
		// dropped upstream (not in the WorldMap). A deep-ocean province with no shelf yields an
		// empty field and is not written (the web keeps drawing it as the open-sea ripple).
		java.util.Collection<Province> all = map.provinces();
		int total = all.size(), gen = 0, skip = 0, empty = 0, fail = 0;
		long t0 = System.currentTimeMillis();
		System.out.println("generating plot fields for " + total + " provinces (land + coastal shelf)...");
		for (Province p : all) {
			if (new File(dir, p.id() + ".json.gz").exists()) {
				skip++;
				continue;
			}
			try {
				Rng rng = rngSeed.forProvinceCanonical(RngSeed.Stream.TERRAIN, p.id());
				ProvincePlotField field = ProvincePlotField.generate(p, registry, raster, rng);
				if (field.size() == 0) { // deep-water province with no coastal shelf — nothing to store
					empty++;
					continue;
				}
				List<Plot> plots = new ArrayList<>(field.size());
				for (ProvincePlot pp : field.plots()) {
					Plot plot = new Plot(pp.geo(), pp.terrain(), pp.plotType(), pp.feature(), pp.bonus());
					plot.setUrban(pp.urban());   // built-up overlay on natural terrain (retired TERRAIN_URBAN)
					plots.add(plot);
				}
				ProvincePlotStore.save(p.id(), plots);
				gen++;
				if (gen % 200 == 0)
					System.out.printf("  generated %d (skipped %d, empty %d) of %d, %ds elapsed%n",
							gen, skip, empty, total, (System.currentTimeMillis() - t0) / 1000);
			} catch (Exception e) {
				fail++;
				if (fail <= 20)
					System.out.println("  skip province " + p.id() + ": " + e.getMessage());
			}
		}
		System.out.printf("done: %d generated, %d already present, %d empty (no shelf), %d failed, of %d provinces in %ds%n",
				gen, skip, empty, fail, total, (System.currentTimeMillis() - t0) / 1000);

		nameWorld(map, registry);
	}

	/**
	 * Stamp real Earth place names onto the warmed plot cache (see {@link PlaceNamingPass}). Additive
	 * over whatever is present, so it runs after generation with no cache regeneration. Skipped — with
	 * a note — when the GeoNames dump is absent, so a clone without it still gets a working (nameless)
	 * plot cache.
	 */
	private static void nameWorld(WorldMap map, TerrainRegistry registry) throws Exception {
		if (!GeoNamesFiles.isAvailable()) {
			System.out.println("GeoNames dump not present in " + GeoNamesFiles.cacheDir().toAbsolutePath()
					+ " — skipping plot naming (plots stay nameless). See com.civstudio.data.GeoNamesFiles.");
			return;
		}
		long t0 = System.currentTimeMillis();
		RegionEarthMap earth = RegionEarthMap.load();
		Set<String> countries = new HashSet<>(earth.countries());
		System.out.println("naming plots: loading GeoNames gazetteers for " + countries.size()
				+ " countries (one pass over the dump)...");
		Map<String, CountryGazetteer> gazetteers = GeoNamesGazetteer.loadFromCache(countries);
		long places = gazetteers.values().stream().mapToLong(CountryGazetteer::size).sum();
		System.out.printf("  loaded %,d places across %d countries in %ds; naming by region...%n",
				places, gazetteers.size(), (System.currentTimeMillis() - t0) / 1000);
		int named = PlaceNamingPass.nameWorld(map, registry, earth, gazetteers);
		System.out.printf("naming done: %d provinces named in %ds%n",
				named, (System.currentTimeMillis() - t0) / 1000);
	}
}
