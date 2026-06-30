package com.civstudio.geo;

import java.util.ArrayList;
import java.util.List;

import com.civstudio.util.Rng;

/**
 * Places Civ4 {@link Bonus} resources (wheat, iron, banana, …) onto a province's
 * plots. The Caveman2Cosmos planet generator does <b>not</b> place bonuses itself
 * — its {@code addBonuses} delegates to the engine
 * ({@code CvMapGeneratorUtil.placeC2CBonuses}) — and the engine places each bonus
 * purely by the <b>placement constraints</b> baked into its definition. Those
 * constraints already ride on our {@link Bonus} record (imported from {@code
 * CIV4BonusInfos.xml} but dormant until now): the valid host terrains/features,
 * the flat/hill/peak relief flags, and the latitude band. This generator wakes
 * that data, choosing a resource for a plot only among the bonuses whose
 * constraints the plot satisfies. See {@code docs/province-plots.md}.
 * <p>
 * Rarity here is a single flat per-plot {@link #PLACEMENT_CHANCE} (a placeholder —
 * the engine's appearance probabilities and group spacing are not in the exported
 * data), so resources land sparsely and, among the eligible, uniformly. The
 * generation stays deterministic off the terrain {@code rng}.
 */
public final class BonusGenerator {

	/** Per-plot chance to even attempt a resource (then gated again by eligibility). */
	private static final double PLACEMENT_CHANCE = 0.08;

	private BonusGenerator() {
	}

	/**
	 * Choose a resource for one plot, or {@code null}. Consumes one terrain-{@code
	 * rng} draw to decide whether to attempt, and a second to pick among the
	 * eligible bonuses when it does — so placement is deterministic per seed.
	 *
	 * @param terrain  the plot's ground
	 * @param relief   the plot's relief (flat/hill/peak)
	 * @param feature  the plot's wild feature, or {@code null}
	 * @param latitude the province latitude (the bonus latitude band is absolute)
	 * @param bonuses  the curated bonus set ({@code registry.bonuses()})
	 * @param rng      the dedicated terrain stream (not the economic one)
	 * @return the placed bonus, or {@code null}
	 */
	public static Bonus pick(Terrain terrain, PlotType relief, Feature feature,
			double latitude, List<Bonus> bonuses, Rng rng) {
		if (rng.uniform() >= PLACEMENT_CHANCE)
			return null;
		List<Bonus> eligible = new ArrayList<>();
		for (Bonus b : bonuses)
			if (eligible(b, terrain, relief, feature, latitude))
				eligible.add(b);
		if (eligible.isEmpty())
			return null;
		return eligible.get(rng.uniform(eligible.size()));
	}

	/**
	 * Whether a bonus's placement constraints admit this plot — the engine's test:
	 * the latitude band, the relief flag, and either the bare-terrain list (no
	 * feature) or the feature + feature-terrain lists (featured plot).
	 */
	static boolean eligible(Bonus b, Terrain terrain, PlotType relief, Feature feature, double latitude) {
		double absLat = Math.abs(latitude);
		if (absLat < b.minLatitude() || absLat > b.maxLatitude())
			return false;
		boolean reliefOk = switch (relief) {
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
