package com.civstudio.geo;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.civstudio.util.Rng;

/**
 * Generates a colony's plot terrain procedurally from its founding province's
 * climate. Maps the province's {@link Climate}/{@link WinterSeverity}/{@link
 * Monsoon} to a weighted pool of land {@link Terrain}s and draws one terrain per
 * plot off a dedicated terrain {@link Rng} (salted separately from the economic
 * stream, so generation never perturbs it). See {@code docs/plots.md}.
 * <p>
 * As of Phase 3 it produces the base terrain <b>and an optional wild feature</b>
 * (forest/jungle/…), honouring each feature's valid host terrains and skipping the
 * river-gated ones (no per-plot river signal yet — see {@code docs/plots.md}); plot
 * type (hills/peaks) and resource bonuses come in a later phase. A
 * <b>province-less</b> colony bypasses generation entirely and uses the {@link
 * #BASELINE_TERRAIN baseline terrain} (uniform grassland, no feature) — the weights
 * below are tuned so the model's baseline plot lands at yield-factor ≈ 1.0.
 */
public final class TerrainGenerator {

	/**
	 * The terrain a province-less colony's plots use uniformly — the model's
	 * reference ground (grassland, food 2). Phase 2's yield reference is calibrated
	 * against it, so a province-less colony nets to factor 1.0.
	 */
	public static final String BASELINE_TERRAIN = "TERRAIN_GRASSLAND";

	/**
	 * Probability a generated plot carries a wild feature (when its terrain has any
	 * valid feature). A placeholder pending the food-balance calibration, like the
	 * terrain weights — features only affect a farmed plot's <em>clear cost</em> (the
	 * feature is removed when the farm is raised), and give the future forage firm its
	 * wild targets.
	 */
	private static final double FEATURE_PROBABILITY = 0.35;

	// the weighted pool this generator draws from: parallel terrain/cumulative-weight
	// arrays for a single uniform draw per plot
	private final Terrain[] pool;
	private final double[] cumulative;
	private final double totalWeight;

	// the wild features each terrain may host (its valid, non-river features), keyed
	// by terrain type, for the per-plot feature roll
	private final Map<String, List<Feature>> featuresByTerrain = new LinkedHashMap<>();

	/**
	 * Build a generator for a province of the given climate. The terrain pool and the
	 * per-terrain feature lists are fixed at construction (so every plot draws from
	 * the same distribution).
	 *
	 * @param registry the curated terrain/feature/improvement definitions
	 * @param climate  the province's climate band (drives the base pool)
	 * @param winter   the winter severity (shifts the pool colder)
	 * @param monsoon  the monsoon intensity (adds wetland weight)
	 */
	public TerrainGenerator(TerrainRegistry registry, Climate climate,
			WinterSeverity winter, Monsoon monsoon) {
		Map<String, Double> weights = baseWeights(climate);
		applyWinter(weights, winter);
		applyMonsoon(weights, monsoon);

		List<Terrain> terrains = new ArrayList<>();
		List<Double> cum = new ArrayList<>();
		double running = 0;
		for (Map.Entry<String, Double> e : weights.entrySet()) {
			Terrain t = registry.terrain(e.getKey());
			if (t == null)
				throw new IllegalStateException(
						"terrain pool references unknown terrain " + e.getKey());
			running += e.getValue();
			terrains.add(t);
			cum.add(running);
		}
		this.pool = terrains.toArray(new Terrain[0]);
		this.cumulative = new double[cum.size()];
		for (int i = 0; i < cum.size(); i++)
			this.cumulative[i] = cum.get(i);
		this.totalWeight = running;

		indexFeatures(registry);
	}

	// build, per terrain type, the list of wild features valid on it — those whose
	// validTerrains include the terrain and that are not river-gated (no per-plot
	// river signal yet, so FLOOD_PLAINS et al. are skipped). Deterministic order.
	private void indexFeatures(TerrainRegistry registry) {
		for (Terrain t : pool)
			featuresByTerrain.putIfAbsent(t.type(), new ArrayList<>());
		for (Feature f : registry.features()) {
			if (f.requiresRiver())
				continue;
			for (String terrainType : f.validTerrains()) {
				List<Feature> list = featuresByTerrain.get(terrainType);
				if (list != null)
					list.add(f);
			}
		}
	}

	/**
	 * Draw the next plot's terrain off the terrain RNG.
	 *
	 * @param rng the dedicated terrain generator (not the economic stream)
	 * @return the drawn terrain
	 */
	public Terrain next(Rng rng) {
		double x = rng.uniform() * totalWeight;
		for (int i = 0; i < cumulative.length; i++)
			if (x < cumulative[i])
				return pool[i];
		return pool[pool.length - 1]; // floating-point guard
	}

	/**
	 * Draw the wild feature (if any) overlaying a plot of the given terrain, off the
	 * terrain RNG. Consumes exactly one draw per call (whether or not a feature
	 * results), so the terrain stream stays predictable. Returns {@code null} when the
	 * roll misses {@link #FEATURE_PROBABILITY} or the terrain hosts no valid feature.
	 *
	 * @param terrain the plot's terrain (its valid feature pool)
	 * @param rng     the dedicated terrain generator (not the economic stream)
	 * @return the drawn feature, or {@code null} if the plot is bare
	 */
	public Feature nextFeature(Terrain terrain, Rng rng) {
		double r = rng.uniform();
		List<Feature> valid = featuresByTerrain.get(terrain.type());
		if (valid == null || valid.isEmpty() || r >= FEATURE_PROBABILITY)
			return null;
		int i = (int) (r / FEATURE_PROBABILITY * valid.size());
		if (i >= valid.size())
			i = valid.size() - 1; // floating-point guard
		return valid.get(i);
	}

	// the base climate pool (placeholder weights pending the Phase-2 food-balance
	// calibration — see docs/plots.md "Open questions"). LinkedHashMap so the pool
	// order, hence the draw, is deterministic.
	private static Map<String, Double> baseWeights(Climate climate) {
		Map<String, Double> w = new LinkedHashMap<>();
		switch (climate) {
			case TROPICAL -> {
				w.put("TERRAIN_LUSH", 4.0);
				w.put("TERRAIN_MUDDY", 2.0);
				w.put("TERRAIN_MARSH", 2.0);
				w.put("TERRAIN_PLAINS", 1.0);
				w.put("TERRAIN_GRASSLAND", 1.0);
			}
			case ARID -> {
				w.put("TERRAIN_DESERT", 3.0);
				w.put("TERRAIN_SCRUB", 3.0);
				w.put("TERRAIN_DUNES", 2.0);
				w.put("TERRAIN_BADLAND", 1.0);
				w.put("TERRAIN_BARREN", 1.0);
			}
			case ARCTIC -> {
				w.put("TERRAIN_TUNDRA", 3.0);
				w.put("TERRAIN_TAIGA", 3.0);
				w.put("TERRAIN_PERMAFROST", 2.0);
				w.put("TERRAIN_ROCKY", 1.0);
			}
			case TEMPERATE -> {
				w.put("TERRAIN_GRASSLAND", 4.0);
				w.put("TERRAIN_PLAINS", 3.0);
				w.put("TERRAIN_LUSH", 1.0);
				w.put("TERRAIN_MUDDY", 1.0);
				w.put("TERRAIN_MARSH", 1.0);
				w.put("TERRAIN_SCRUB", 1.0);
			}
		}
		return w;
	}

	// harder winters shift the pool toward the cold terrains
	private static void applyWinter(Map<String, Double> w, WinterSeverity winter) {
		double shift = switch (winter) {
			case NONE -> 0;
			case MILD -> 1;
			case NORMAL -> 2;
			case SEVERE -> 3;
		};
		if (shift == 0)
			return;
		bump(w, "TERRAIN_TAIGA", shift);
		bump(w, "TERRAIN_TUNDRA", shift);
		if (winter == WinterSeverity.SEVERE)
			bump(w, "TERRAIN_PERMAFROST", shift);
	}

	// monsoon wets the land — more marsh and muddy ground
	private static void applyMonsoon(Map<String, Double> w, Monsoon monsoon) {
		double shift = switch (monsoon) {
			case NONE -> 0;
			case MILD -> 1;
			case NORMAL -> 2;
			case SEVERE -> 3;
		};
		if (shift == 0)
			return;
		bump(w, "TERRAIN_MARSH", shift);
		bump(w, "TERRAIN_MUDDY", shift);
	}

	private static void bump(Map<String, Double> w, String type, double by) {
		w.merge(type, by, Double::sum);
	}
}
