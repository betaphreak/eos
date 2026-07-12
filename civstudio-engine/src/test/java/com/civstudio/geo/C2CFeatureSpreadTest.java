package com.civstudio.geo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.civstudio.geo.ProvincePlotField.ProvincePlot;
import com.civstudio.util.Rng;

/**
 * Locks the behaviour of the C2C {@code addFeatures} seed-and-spread port (slices
 * 1–3, {@code docs/c2c-generator-port.md}): vegetation grows as spatially-coherent
 * clusters (the water/peak seeds spread cell-to-cell, not a uniform dusting), and
 * <b>no jungle ever lands on cold terrain</b> — the ported jungle→forest substitution
 * (and the curated validity gate) keeps tundra/permafrost/snow jungle-free.
 */
class C2CFeatureSpreadTest {

	private static long key(int x, int y) {
		return ((long) x << 20) | (y & 0xFFFFF);
	}

	private static boolean isTree(ProvincePlot p) {
		if (p.feature() == null)
			return false;
		String t = p.feature().type();
		return "FEATURE_FOREST".equals(t) || "FEATURE_JUNGLE".equals(t);
	}

	@Test
	void vegetationSpreadsAsCoherentClusters() throws Exception {
		Province dh = WorldMap.load().findByName("Bim Lau").orElseThrow();   // jungle-rich tropical → clear clusters
		ProvincePlotField field = ProvincePlotField.generate(
				dh, TerrainRegistry.load(), ProvinceRaster.load(), new Rng(3));

		Set<Long> veg = new HashSet<>();
		for (ProvincePlot p : field.plots())
			if (isTree(p))
				veg.add(key(p.x(), p.y()));

		// the spread produces adjacency independent draws would not: a wooded province's
		// forest/jungle cells touch one another rather than scattering
		int adjacent = 0;
		for (long k : veg) {
			int x = (int) (k >> 20), y = (int) (k & 0xFFFFF);
			for (int dx = -1; dx <= 1; dx++)
				for (int dy = -1; dy <= 1; dy++)
					if ((dx != 0 || dy != 0) && veg.contains(key(x + dx, y + dy)))
						adjacent++;
		}
		assertTrue(veg.size() >= 4, "Bim Lau should grow a wooded cluster, got " + veg.size());
		assertTrue(adjacent > 0, "vegetation should cluster (got " + veg.size() + " tree cells, none adjacent)");
	}

	@Test
	void jungleNeverLandsOnColdTerrain() throws Exception {
		// Vytakmua — the largest land province, at 82°N (C2C temperature ≈ -9): its cold
		// ground exercises the jungle→forest substitution and the cold-category gate.
		// Generating a ~5000-plot province also exercises the spread at scale.
		Province cold = WorldMap.load().findByName("Vytakmua").orElseThrow();
		TerrainRegistry reg = TerrainRegistry.load();
		ProvinceRaster raster = ProvinceRaster.load();

		int jungleOnCold = 0, jungle = 0, featured = 0;
		for (int seed = 0; seed < 4; seed++) {
			ProvincePlotField field = ProvincePlotField.generate(cold, reg, raster, new Rng(seed));
			for (ProvincePlot p : field.plots()) {
				if (p.feature() == null)
					continue;
				featured++;
				if ("FEATURE_JUNGLE".equals(p.feature().type())) {
					jungle++;
					if (PyTerrain.of(p.terrain()).isCold())
						jungleOnCold++;
				}
			}
		}
		assertTrue(featured > 0, "the cold province should still grow some features");
		assertEquals(0, jungleOnCold, "jungle must never sit on cold terrain (tundra/permafrost/snow)");
	}

	/**
	 * The generic appearance-probability scatter (slice 4) is the only path that places
	 * the curated-but-formerly-dead features — {@code FOREST_ANCIENT}, {@code BAMBOO},
	 * {@code VERY_TALL_GRASS} — which no seed-and-spread or terrain-implied rule reaches.
	 * Across a small province/seed sweep at least one of them must now appear.
	 */
	@Test
	void appearanceScatterPlacesFormerlyDeadFeatures() throws Exception {
		TerrainRegistry reg = TerrainRegistry.load();
		ProvinceRaster raster = ProvinceRaster.load();
		Set<String> dead = Set.of("FEATURE_FOREST_ANCIENT", "FEATURE_BAMBOO", "FEATURE_VERY_TALL_GRASS");

		Set<String> found = new HashSet<>();
		for (String name : new String[] { "Kaashesh", "Vytakmua" }) {
			Province p = WorldMap.load().findByName(name).orElseThrow();
			for (int seed = 0; seed < 6; seed++)
				for (ProvincePlot pl : ProvincePlotField.generate(p, reg, raster, new Rng(seed)).plots())
					if (pl.feature() != null && dead.contains(pl.feature().type()))
						found.add(pl.feature().type());
		}
		assertTrue(!found.isEmpty(),
				"the appearance scatter should place at least one formerly-dead feature, got " + found);
	}

	/**
	 * The C2C oasis scoring pass (slice 5) scatters oases across an arid province's
	 * inland desert — every one a valid flat desert-category host, and a scored (not
	 * uniform) placement, so a desert province grows a plausible cluster of them.
	 */
	@Test
	void oasesScatterAcrossInlandDesert() throws Exception {
		Province arid = WorldMap.load().findByName("Tsunapileed").orElseThrow();
		ProvincePlotField field = ProvincePlotField.generate(
				arid, TerrainRegistry.load(), ProvinceRaster.load(), new Rng(0));

		int oases = 0;
		for (ProvincePlot p : field.plots())
			if (p.feature() != null && "FEATURE_OASIS".equals(p.feature().type())) {
				oases++;
				assertSame(PlotType.FLAT, p.plotType(), "oasis only on flat ground");
				assertTrue(p.feature().validTerrains().contains(p.terrain().type()),
						"oasis on an invalid host terrain " + p.terrain().type());
			}
		assertTrue(oases > 0, "an arid province should grow inland oases, got " + oases);
	}
}
