package com.civstudio.geo;

import java.util.ArrayList;
import java.util.List;

import com.civstudio.util.Rng;

/**
 * Grows wild vegetation (forest / jungle) across a province's land cells, porting
 * the <b>water-seeded, spatially-coherent</b> feature stage of the Caveman2Cosmos
 * planet generator (its {@code addFeatures} seed-and-spread loop) from {@code
 * data/C2C_Planet_Generator_0_68.py} to Java. Where the relief stage clusters
 * peaks/hills, this clusters plants: vegetation <b>seeds along water</b> (the
 * province's river cells and its coast — land cells touching the sea outside the
 * province) and <b>spreads inland</b>, its chance decaying with distance from
 * water and rising with the province's humidity, so forests/jungle hug the
 * watercourses and coast rather than dusting the map uniformly. See {@code
 * docs/province-plots.md}.
 * <p>
 * The vegetation kind is the province's: jungle in a {@linkplain
 * ClimateProfile#isHot() hot} climate, forest otherwise. Flood plains (the other
 * river feature) are placed by {@link ProvincePlotField} where it also has the
 * relief and terrain to gate them (flat, dry, riverside). The terrain-rewriting
 * the C2C stage does as features spread (jungle turning desert to grass, etc.) is
 * left for a later slice — this slice only places the feature overlay.
 */
public final class FeatureGenerator {

	/** Vegetation never spreads more than this many steps from open water. */
	private static final int MAX_SPREAD = 4;

	private FeatureGenerator() {
	}

	/**
	 * Generate the vegetation overlay for a mask: one {@link Feature} per cell —
	 * {@code FEATURE_FOREST}/{@code FEATURE_JUNGLE} on vegetated land, {@code null}
	 * elsewhere — drawn off the terrain {@code rng}.
	 * <p>
	 * The vegetation <b>kind</b> is the province's climate (jungle if hot, else
	 * forest); the vegetation <b>amount</b> is the {@code treeCover} fraction read
	 * from the real {@code trees.bmp} overlay (see {@link MapTerrainCodec#isWoody}):
	 * the seed-and-spread chance scales with it, so a province the map paints as
	 * heavily wooded grows dense vegetation along its water while a bare one grows
	 * almost none. {@code trees.bmp} is too coarse to place individual forest pixels
	 * on a small province, so it drives this density rather than a per-pixel feature.
	 *
	 * @param mask      the province silhouette (land + river flags)
	 * @param climate   the province's temperature/humidity profile (the veg kind)
	 * @param treeCover the real wooded fraction in {@code [0, 1]} from {@code
	 *                  trees.bmp} (the veg amount); a negative value (no overlay)
	 *                  falls back to the climate humidity
	 * @param registry  the curated feature definitions
	 * @param rng       the dedicated terrain stream (not the economic one)
	 * @return a {@code width*height} feature grid (row-major; {@code null} = bare)
	 */
	public static Feature[] generate(ProvinceMask mask, ClimateProfile climate,
			double treeCover, TerrainRegistry registry, Rng rng) {
		int w = mask.width(), h = mask.height();
		Feature[] feat = new Feature[w * h];
		Feature vegetation = registry.feature(climate.isHot() ? "FEATURE_JUNGLE" : "FEATURE_FOREST");
		if (vegetation == null)
			return feat; // curated set lacks it — leave bare

		// seed along water: river cells, and coastal land (touching ocean)
		List<int[]> seeds = new ArrayList<>(); // x, y, distance-from-water
		for (int ly = 0; ly < h; ly++)
			for (int lx = 0; lx < w; lx++)
				if (mask.isLand(lx, ly) && (mask.isRiver(lx, ly) || isCoastal(mask, lx, ly)))
					seeds.add(new int[] { lx, ly, 1 });

		// the real wooded fraction drives the spread density; with no overlay fall
		// back to the climate humidity (the prior behaviour)
		double density = treeCover >= 0 ? treeCover : (0.2 + 0.8 * climate.humidity());
		while (!seeds.isEmpty()) {
			int[] s = seeds.remove(rng.uniform(seeds.size()));
			int x = s[0], y = s[1], dist = s[2];
			int idx = y * w + x;
			if (feat[idx] != null)
				continue;
			// vegetate with a chance that decays with distance from water and scales
			// with how wooded the real map paints this province
			double pVegetate = density / dist;
			if (rng.uniform() >= pVegetate)
				continue;
			feat[idx] = vegetation;
			if (dist >= MAX_SPREAD)
				continue;
			for (int[] d : DIRS4) {
				int nx = x + d[0], ny = y + d[1];
				if (mask.isLand(nx, ny) && feat[ny * w + nx] == null)
					seeds.add(new int[] { nx, ny, dist + 1 });
			}
		}
		return feat;
	}

	// orthogonal neighbours — vegetation spreads along contiguous land
	private static final int[][] DIRS4 = { { -1, 0 }, { 1, 0 }, { 0, -1 }, { 0, 1 } };

	/** A land cell is coastal if an orthogonal neighbour is outside the province (ocean). */
	private static boolean isCoastal(ProvinceMask mask, int x, int y) {
		for (int[] d : DIRS4)
			if (!mask.isLand(x + d[0], y + d[1]))
				return true;
		return false;
	}
}
