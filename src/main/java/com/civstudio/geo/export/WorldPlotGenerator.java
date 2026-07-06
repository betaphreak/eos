package com.civstudio.geo.export;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import com.civstudio.geo.Province;
import com.civstudio.geo.ProvincePlotField;
import com.civstudio.geo.ProvincePlotField.ProvincePlot;
import com.civstudio.geo.ProvinceRaster;
import com.civstudio.geo.TerrainRegistry;
import com.civstudio.geo.WorldMap;
import com.civstudio.settlement.Plot;
import com.civstudio.settlement.ProvincePlotStore;
import com.civstudio.util.Rng;
import com.civstudio.util.RngSeed;

/**
 * Dev tool: generate and persist the plot field of <b>every settleable land
 * province</b> to {@code map/provinces/<id>.json.gz}, so the web WorldMap can lazy-load
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
		File dir = new File("src/main/resources/map/provinces");
		dir.mkdirs();

		List<Province> land = map.provinces().stream().filter(Province::isSettleable).toList();
		int total = land.size(), gen = 0, skip = 0, fail = 0;
		long t0 = System.currentTimeMillis();
		System.out.println("generating plot fields for " + total + " settleable land provinces...");
		for (Province p : land) {
			if (new File(dir, p.id() + ".json.gz").exists()) {
				skip++;
				continue;
			}
			try {
				Rng rng = rngSeed.forProvinceCanonical(RngSeed.Stream.TERRAIN, p.id());
				ProvincePlotField field = ProvincePlotField.generate(p, registry, raster, rng);
				List<Plot> plots = new ArrayList<>(field.size());
				for (ProvincePlot pp : field.plots())
					plots.add(new Plot(pp.x(), pp.y(), pp.riverCode(), pp.terrain(), pp.plotType(),
							pp.feature(), pp.bonus(), pp.elevation()));
				ProvincePlotStore.save(p.id(), plots);
				gen++;
				if (gen % 200 == 0)
					System.out.printf("  generated %d (skipped %d) of %d, %ds elapsed%n",
							gen, skip, total, (System.currentTimeMillis() - t0) / 1000);
			} catch (Exception e) {
				fail++;
				if (fail <= 20)
					System.out.println("  skip province " + p.id() + ": " + e.getMessage());
			}
		}
		System.out.printf("done: %d generated, %d already present, %d failed, of %d land provinces in %ds%n",
				gen, skip, fail, total, (System.currentTimeMillis() - t0) / 1000);
	}
}
