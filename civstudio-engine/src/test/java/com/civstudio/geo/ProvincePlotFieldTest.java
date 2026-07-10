package com.civstudio.geo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
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
	void plotsCarryHeightmapElevation() throws Exception {
		Province dh = dhenijansar();
		ProvinceRaster raster = ProvinceRaster.load();
		ProvincePlotField field = ProvincePlotField.generate(
				dh, TerrainRegistry.load(), raster, new Rng(11));

		// every plot's elevation is exactly the heightmap.bmp value at its raster pixel
		// (the mask exposes it), and a real province carries non-trivial relief
		ProvinceMask mask = raster.mask(dh.id());
		int min = 255, max = 0;
		for (ProvincePlot p : field.plots()) {
			int lx = p.x() - mask.originX(), ly = p.y() - mask.originY();
			assertEquals(mask.elevation(lx, ly), p.elevation(),
					"plot elevation == heightmap at its pixel");
			min = Math.min(min, p.elevation());
			max = Math.max(max, p.elevation());
		}
		assertTrue(max > 0, "elevation populated from the heightmap (max=" + max + ")");
		assertTrue(max > min, "the province has elevation variation (" + min + ".." + max + ")");
	}

	@Test
	void riverAdjacencyMaskMatchesRiverNeighbours() throws Exception {
		// the river code's thousands digit is a 4-bit mask (1=E,2=W,4=S,8=N) of which orthogonal
		// neighbours are also river cells — the seam fix that lets the web ribbon link across
		// provinces. Verify it agrees with the actual neighbours the mask can see (in-bounds);
		// edge cells may set bits pointing into an adjacent province, which one mask cannot check.
		ProvinceMask mask = ProvinceRaster.load().mask(dhenijansar().id());
		int[][] dirs = { { 1, 0, 1 }, { -1, 0, 2 }, { 0, 1, 4 }, { 0, -1, 8 } };   // dx, dy, bit
		int riverCells = 0;
		for (int ly = 0; ly < mask.height(); ly++)
			for (int lx = 0; lx < mask.width(); lx++) {
				int code = mask.riverCode(lx, ly);
				if (code == 0) continue;
				riverCells++;
				int adj = (code / 1000) % 16;
				for (int[] d : dirs) {
					int nx = lx + d[0], ny = ly + d[1];
					if (nx < 0 || nx >= mask.width() || ny < 0 || ny >= mask.height()) continue;
					boolean bitSet = (adj & d[2]) != 0;
					boolean nbRiver = mask.riverCode(nx, ny) != 0;
					assertEquals(nbRiver, bitSet,
							"adjacency bit " + d[2] + " at (" + lx + "," + ly + ") must match a river neighbour");
				}
			}
		assertTrue(riverCells > 0, "Dhenijansar carries river cells to exercise the mask");
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
			assertEquals(featureType(pa), featureType(pb), "same feature at " + i);
			assertEquals(bonusType(pa), bonusType(pb), "same bonus at " + i);
		}
	}

	@Test
	void resourcesArePlacedOnlyWhereTheirConstraintsAllow() throws Exception {
		Province dh = dhenijansar();
		TerrainRegistry reg = TerrainRegistry.load();
		ProvinceRaster raster = ProvinceRaster.load();

		// every placed bonus must satisfy its own placement constraints on its plot
		int placed = 0;
		for (int seed = 0; seed < 12; seed++) {
			ProvincePlotField field = ProvincePlotField.generate(dh, reg, raster, new Rng(seed));
			for (ProvincePlot p : field.plots())
				if (p.bonus() != null) {
					placed++;
					assertTrue(
							BonusGenerator.eligible(p.bonus(), p.terrain(), p.plotType(),
									p.feature(), dh.latitude()),
							p.bonus().type() + " placed on an ineligible plot");
				}
		}
		// a tropical coastal province has many eligible (jungle/lush) resources — some land
		assertTrue(placed > 0, "expected some resources placed across the seed sweep");
	}

	private static String bonusType(ProvincePlot p) {
		return p.bonus() == null ? "none" : p.bonus().type();
	}

	/**
	 * The feature stage places <b>diverse, valid</b> features drawn from the real map:
	 * the tree class and the ground each imply a feature (forest/jungle/savanna/cactus/
	 * swamp/…), every one validity-gated against its plot's terrain & relief, plus
	 * river flood plains. Dhenijansar — real plains/grassland with a desert fringe,
	 * wooded and wet — grows more than one feature type, where the old single-climate
	 * kind gave only one.
	 */
	@Test
	void featureStagePlacesDiverseValidFeaturesAndFloodPlains() throws Exception {
		Province dh = dhenijansar();
		ProvincePlotField field = ProvincePlotField.generate(
				dh, TerrainRegistry.load(), ProvinceRaster.load(), new Rng(3));

		Set<String> kinds = new HashSet<>();
		int featured = 0, flood = 0;
		for (ProvincePlot p : field.plots()) {
			if (p.feature() == null)
				continue;
			featured++;
			kinds.add(p.feature().type());
			// every placed feature must be a valid host of its plot's terrain & relief
			assertTrue(p.feature().validTerrains().contains(p.terrain().type()),
					p.feature().type() + " placed on invalid terrain " + p.terrain().type());
			if (p.feature().requiresFlatlands())
				assertEquals(PlotType.FLAT, p.plotType(),
						p.feature().type() + " requires flat ground");
			if ("FEATURE_FLOOD_PLAINS".equals(p.feature().type())) {
				flood++;
				assertTrue(p.river() && p.plotType() == PlotType.FLAT,
						"flood plains only on flat river plots");
			}
		}
		assertTrue(featured > 0, "expected some features placed");
		assertTrue(kinds.size() >= 2, "expected diverse features from the real map, got " + kinds);
		assertTrue(flood > 0, "expected flood plains on its flat river plots");
	}

	private static String featureType(ProvincePlot p) {
		return p.feature() == null ? "none" : p.feature().type();
	}

	/**
	 * The plots are grounded on the <b>real</b> {@code terrain.bmp}, not the
	 * province's climate-weighted pool. Dhenijansar reads from the map as plains +
	 * grassland with a little desert; its tropical {@link Climate} pool would instead
	 * draw lush/muddy/marsh and could never produce desert — so the real composition
	 * is the discriminator that the map (not the climate RNG) is driving terrain.
	 */
	@Test
	void terrainComesFromTheRealMapNotTheClimatePool() throws Exception {
		Province dh = dhenijansar();
		ProvincePlotField field = ProvincePlotField.generate(
				dh, TerrainRegistry.load(), ProvinceRaster.load(), new Rng(5));

		Map<String, Integer> counts = new HashMap<>();
		for (ProvincePlot p : field.plots())
			counts.merge(p.terrain().type(), 1, Integer::sum);

		int total = field.size();
		int plainsAndGrass = counts.getOrDefault("TERRAIN_PLAINS", 0)
				+ counts.getOrDefault("TERRAIN_GRASSLAND", 0);
		// the map paints this province overwhelmingly plains/grassland
		assertTrue(plainsAndGrass >= total * 0.8,
				"expected mostly plains/grassland from the map, got " + counts);
		// it carries the map's desert fringe — impossible from the tropical pool
		assertTrue(counts.getOrDefault("TERRAIN_DESERT", 0) > 0,
				"expected the map's desert pixels, got " + counts);
		// and none of the tropical climate pool's signature wet terrains
		assertEquals(0, counts.getOrDefault("TERRAIN_LUSH", 0)
				+ counts.getOrDefault("TERRAIN_MUDDY", 0),
				"climate-pool terrains should not appear when the map grounds every plot");
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
