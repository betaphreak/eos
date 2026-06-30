package com.civstudio.geo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.civstudio.geo.ProvincePlotField.ProvincePlot;
import com.civstudio.util.Rng;

/**
 * Phase-1 checks on the per-province plot field (see {@code
 * docs/province-plots.md}): the field is the province's real silhouette (one plot
 * per land pixel), is generated deterministically per seed, carries valid
 * relief/terrain on every plot, and the C2C-ported relief actually clusters.
 */
class ProvincePlotFieldTest {

	/** Dhenijansar (province 4411) — the default colony's coastal LAND province. */
	private static Province dhenijansar() {
		return WorldMap.load().findByName("Dhenijansar").orElseThrow();
	}

	@Test
	void fieldIsTheProvinceSilhouetteWithValidPlots() throws Exception {
		Province dh = dhenijansar();
		ProvincePlotField field = ProvincePlotField.generate(
				dh, TerrainRegistry.load(), ProvinceRaster.load(), new Rng(42));

		// one plot per province land pixel
		assertEquals(dh.plots(), field.size(), "plot count == province.plots");

		// every plot has terrain + relief, and a unique raster position
		Set<Long> seen = new HashSet<>();
		for (ProvincePlot p : field.plots()) {
			assertNotNull(p.terrain(), "terrain");
			assertNotNull(p.plotType(), "relief");
			assertTrue(seen.add(((long) p.x() << 20) | (p.y() & 0xFFFFF)), "distinct position");
		}
	}

	@Test
	void generationIsDeterministicPerSeed() throws Exception {
		Province dh = dhenijansar();
		TerrainRegistry reg = TerrainRegistry.load();
		ProvinceRaster raster = ProvinceRaster.load();

		ProvincePlotField a = ProvincePlotField.generate(dh, reg, raster, new Rng(7));
		ProvincePlotField b = ProvincePlotField.generate(dh, reg, raster, new Rng(7));

		assertEquals(a.size(), b.size());
		for (int i = 0; i < a.size(); i++) {
			ProvincePlot pa = a.plots().get(i), pb = b.plots().get(i);
			assertEquals(pa.x(), pb.x());
			assertEquals(pa.y(), pb.y());
			assertSame(pa.plotType(), pb.plotType(), "same relief at " + i);
			assertEquals(pa.terrain().type(), pb.terrain().type(), "same terrain at " + i);
		}
	}

	@Test
	void reliefClustersRatherThanScattering() throws Exception {
		Province dh = dhenijansar();
		ProvincePlotField field = ProvincePlotField.generate(
				dh, TerrainRegistry.load(), ProvinceRaster.load(), new Rng(1));

		// index plots by position to test neighbour adjacency
		Set<Long> hills = new HashSet<>();
		int hillCount = 0;
		for (ProvincePlot p : field.plots())
			if (p.plotType() == PlotType.HILL) {
				hills.add(key(p.x(), p.y()));
				hillCount++;
			}

		// with hills present, at least one hill should touch another (a chain) —
		// the C2C grow stage produces adjacency that independent draws would not
		int adjacentHills = 0;
		for (long k : hills) {
			int x = (int) (k >> 20), y = (int) (k & 0xFFFFF);
			for (int dx = -1; dx <= 1; dx++)
				for (int dy = -1; dy <= 1; dy++)
					if ((dx != 0 || dy != 0) && hills.contains(key(x + dx, y + dy))) {
						adjacentHills++;
					}
		}
		// only assert clustering when the province actually grew hills
		if (hillCount >= 4)
			assertTrue(adjacentHills > 0, "hills should cluster (got " + hillCount + " hills, none adjacent)");
	}

	private static long key(int x, int y) {
		return ((long) x << 20) | (y & 0xFFFFF);
	}
}
