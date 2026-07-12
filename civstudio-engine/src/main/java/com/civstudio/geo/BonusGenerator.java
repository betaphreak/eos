package com.civstudio.geo;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.civstudio.util.Rng;

/**
 * Places Civ4 {@link Bonus} resources (wheat, iron, banana, …) onto a province's
 * plots. The Caveman2Cosmos planet generator does <b>not</b> place bonuses itself
 * — its {@code addBonuses} delegates to the engine
 * ({@code CvMapGeneratorUtil.placeC2CBonuses}) — which places each bonus by the
 * <b>placement constraints and rarity data</b> baked into its definition: the valid
 * host terrains/features, the flat/hill/peak relief flags, the latitude band, and
 * (ported here) the {@code iPlacementOrder}, target density ({@code iTilesPer} +
 * {@code iConstAppearance}/{@code Rands}), {@code iMinAreaSize} and cluster
 * ({@code iGroupRange}/{@code iGroupRand}) fields.
 * <p>
 * The engine runs this map-wide; eos generates and caches plot fields <b>one province
 * at a time</b> (lazily, seed-independently), so {@link #place} runs the algorithm
 * <b>per province</b> — the fields' natural scope. Each bonus, in placement order,
 * gets a target count of {@code appearance% · eligibleTiles / tilesPer} scattered over
 * that province's eligible plots, kept apart by a spacing derived from its group range
 * and optionally clustered by {@code iGroupRand}. The one fidelity gap versus the
 * engine is that counts and spacing are province-local rather than global (there is no
 * cross-province coordination in the lazy model). Deterministic off the terrain {@code
 * rng}. See {@code docs/province-plots.md} and {@code docs/c2c-generator-port.md} §8.
 */
public final class BonusGenerator {

	/** Same-bonus minimum spacing (Chebyshev) when a bonus declares no cluster range. */
	private static final int DEFAULT_SPACING = 2;

	/**
	 * Global scale on every bonus's target count — the one knob for overall resource
	 * density. The engine's raw appearance figures pack the map densely; scaling the
	 * per-province target thins that to a lived-in-but-not-blanketed look. Lower = sparser.
	 */
	private static final double DENSITY_SCALE = 0.055;

	private BonusGenerator() {
	}

	/**
	 * Place resources across a province's plots, returning a {@code width*height} grid of
	 * the placed bonus per cell ({@code null} = none). Bonuses are laid down in ascending
	 * {@link Bonus#placementOrder()}: each maps its eligible, still-empty plots, derives a
	 * target count from its appearance rolls and {@link Bonus#tilesPer()} density, then
	 * scatters that many, skipping any within its spacing of a same-bonus already placed
	 * and clustering extra copies by {@link Bonus#groupRand()}. A bonus whose
	 * {@link Bonus#minAreaSize()} exceeds the province's plot count is skipped.
	 *
	 * @param w        the grid width
	 * @param h        the grid height
	 * @param cells    the plottable cells as {@code {x, y}} (land cells, or shelf water cells)
	 * @param terrain  the per-cell ground grid ({@code w*h}, row-major; entries at {@code cells})
	 * @param relief   the per-cell relief grid, or {@code null} for all-flat (water)
	 * @param feature  the per-cell feature grid ({@code w*h}; {@code null} where bare)
	 * @param latitude the province latitude (the bonus latitude band is absolute)
	 * @param bonuses  the curated bonus set ({@code registry.bonuses()})
	 * @param rng      the dedicated terrain stream (not the economic one)
	 * @param water    whether these are coastal-shelf water cells (skips the land relief gate)
	 * @return a {@code w*h} grid of placed bonuses ({@code null} = no resource)
	 */
	public static Bonus[] place(int w, int h, List<int[]> cells, Terrain[] terrain,
			PlotType[] relief, Feature[] feature, double latitude, List<Bonus> bonuses,
			Rng rng, boolean water) {
		Bonus[] result = new Bonus[w * h];
		int provinceSize = cells.size();
		if (provinceSize == 0)
			return result;
		// place in ascending iPlacementOrder so constrained resources claim plots first
		List<Bonus> ordered = new ArrayList<>(bonuses);
		ordered.sort(Comparator.comparingInt(Bonus::placementOrder));

		for (Bonus b : ordered) {
			// skip un-placed resources, provinces too small, and anything revealed only in the
			// industrial era or later — the map stays pre-industrial (no oil/coal/uranium/…)
			if (!b.mapPlaced() || provinceSize < b.minAreaSize() || b.techEra() >= Bonus.ERA_INDUSTRIAL)
				continue;
			List<int[]> eligible = new ArrayList<>();
			for (int[] c : cells) {
				int idx = c[1] * w + c[0];
				if (result[idx] == null && eligible(b, terrain[idx],
						relief == null ? PlotType.FLAT : relief[idx], feature[idx], latitude, water))
					eligible.add(c);
			}
			if (eligible.isEmpty())
				continue;
			// appearance percent = const + four rolled rands (always four draws, so the
			// stream stays deterministic); target = appearance% · eligible / tilesPer
			int pct = b.constAppearance();
			for (int k = 0; k < 4; k++)
				pct += rng.uniform(b.randApps()[k]);
			// sea resources are placed at half the land density (the shelves read too busy otherwise)
			double density = DENSITY_SCALE * (water ? 0.5 : 1.0);
			// STOCHASTIC rounding, not round(): with the procedural terrain a bonus's eligible plots are
			// spread across more terrain types, so a small province's per-bonus expectation is often < 1
			// and round() would floor every bonus to 0 — a province with no resources. Rounding the
			// fractional part probabilistically preserves the expected density while letting small
			// provinces still draw the occasional resource. One rng draw (deterministic order).
			double expected = density * pct / 100.0 * (eligible.size() / (double) b.tilesPer());
			int target = (int) expected;
			if (rng.uniform() < expected - target)
				target++;
			if (target <= 0)
				continue;

			int spacing = b.groupRange() > 0 ? b.groupRange() + 1 : DEFAULT_SPACING;
			int placed = 0;
			while (placed < target && !eligible.isEmpty()) {
				int[] seed = eligible.remove(rng.uniform(eligible.size()));
				int sIdx = seed[1] * w + seed[0];
				if (result[sIdx] != null || sameBonusWithin(result, w, h, seed[0], seed[1], spacing, b))
					continue; // taken by a cluster, or too close to a same-bonus group
				result[sIdx] = b;
				placed++;
				placed += cluster(result, w, h, seed[0], seed[1], b, terrain, relief, feature,
						latitude, water, rng);
			}
		}
		return result;
	}

	// scatter clustered copies of a just-placed bonus: every plottable, eligible, empty
	// cell within the bonus's group range takes another copy with probability groupRand%
	// (iGroupRand is a per-plot percent, not a count). Returns how many extra were placed.
	private static int cluster(Bonus[] result, int w, int h, int sx, int sy, Bonus b,
			Terrain[] terrain, PlotType[] relief, Feature[] feature, double latitude,
			boolean water, Rng rng) {
		int range = b.groupRange();
		if (range <= 0 || b.groupRand() <= 0)
			return 0;
		int extra = 0;
		for (int dy = -range; dy <= range; dy++)
			for (int dx = -range; dx <= range; dx++) {
				if (dx == 0 && dy == 0)
					continue;
				int nx = sx + dx, ny = sy + dy;
				if (nx < 0 || nx >= w || ny < 0 || ny >= h)
					continue;
				int nIdx = ny * w + nx;
				if (result[nIdx] != null || terrain[nIdx] == null)
					continue; // occupied, or not a plottable cell of this province
				if (!eligible(b, terrain[nIdx], relief == null ? PlotType.FLAT : relief[nIdx],
						feature[nIdx], latitude, water))
					continue;
				if (rng.uniform() * 100.0 < b.groupRand()) {
					result[nIdx] = b;
					extra++;
				}
			}
		return extra;
	}

	// whether a same-bonus placement already sits within `spacing` Chebyshev cells —
	// keeps distinct groups of one resource apart
	private static boolean sameBonusWithin(Bonus[] result, int w, int h, int x, int y, int spacing, Bonus b) {
		for (int dy = -spacing; dy <= spacing; dy++)
			for (int dx = -spacing; dx <= spacing; dx++) {
				int nx = x + dx, ny = y + dy;
				if (nx < 0 || nx >= w || ny < 0 || ny >= h)
					continue;
				if (result[ny * w + nx] == b)
					return true;
			}
		return false;
	}

	/** Land eligibility (relief-gated) — {@link #eligible(Bonus, Terrain, PlotType, Feature, double, boolean)} with {@code water = false}. */
	static boolean eligible(Bonus b, Terrain terrain, PlotType relief, Feature feature, double latitude) {
		return eligible(b, terrain, relief, feature, latitude, false);
	}

	/**
	 * Whether a bonus's placement constraints admit this plot — the engine's test:
	 * the latitude band, the relief flag, and either the bare-terrain list (no
	 * feature) or the feature + feature-terrain lists (featured plot). On a {@code water}
	 * plot the relief flag is skipped (sea bonuses declare no relief; the water terrain gates).
	 */
	static boolean eligible(Bonus b, Terrain terrain, PlotType relief, Feature feature, double latitude, boolean water) {
		double absLat = Math.abs(latitude);
		if (absLat < b.minLatitude() || absLat > b.maxLatitude())
			return false;
		boolean reliefOk = water || switch (relief) {
			case FLAT -> b.flatlands();
			case HILL -> b.hills();
			case PEAK -> b.peaks();
		};
		if (!reliefOk)
			return false;
		if (feature != null)
			return b.validFeatures().contains(feature.type())
					&& b.validFeatureTerrains().contains(terrain.type());
		return b.validTerrains().contains(terrain.type());
	}
}
