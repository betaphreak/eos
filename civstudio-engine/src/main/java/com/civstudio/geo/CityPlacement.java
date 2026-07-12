package com.civstudio.geo;

import java.util.ArrayList;
import java.util.List;

/**
 * Sites a province's <b>urban core</b> — the one-or-few plots that become its city — by a
 * Civ4-style {@code foundValue} (cf. {@code CvPlayerAI::AI_foundValue}) at the level this
 * model supports: the city goes where a Civ4 AI would found it, <b>as close as possible to
 * as many bonuses as fit within a city work radius</b>, on good, foundable ground.
 * <p>
 * The score of a candidate land cell is the value it can reach within {@link
 * #WORK_RADIUS} raster pixels (a plot is one pixel): each nearby {@link Bonus} adds a large,
 * distance-decayed weight (resources dominate — "close to many bonuses"), the reachable
 * terrain/feature yields add a smaller amount, a river adds a little, and the candidate's own
 * relief nudges it (a {@link PlotType#PEAK} is unfoundable, a {@link PlotType#HILL} slightly
 * preferred). The highest-scoring cell is the city centre; a {@code city}-flagged province
 * (Anbennar {@code city_terrain}) grows a denser core of the nearest few cells around it.
 * <p>
 * Deterministic — a pure scan of the finished terrain/relief/feature/bonus grids, consuming
 * no RNG — so the persisted per-province field stays seed-independent. See {@code
 * docs/urban-plots.md}.
 */
public final class CityPlacement {

	/** The city work radius, in raster pixels (≈ Civ4's 2-tile "fat cross"). */
	private static final int WORK_RADIUS = 3;

	/** Weight of one reachable bonus (resources dominate the site choice). */
	private static final double BONUS_WEIGHT = 20.0;
	/** Weight of a reachable cell's summed F/P/C yield. */
	private static final double YIELD_WEIGHT = 1.0;
	/** Weight of a reachable river cell (fresh water / trade). */
	private static final double RIVER_WEIGHT = 3.0;
	/** Small bonus for founding the centre itself on a hill (Civ4 city-on-hill). */
	private static final double HILL_BONUS = 2.0;

	/** The most urban plots a city's core may take. */
	private static final int CITY_CORE_MAX = 4;
	/** Development per extra city-core plot (a dev-30 capital → ~2 plots). */
	private static final int DEV_PER_CORE_PLOT = 15;

	private CityPlacement() {
	}

	/**
	 * How many urban plots {@code province}'s core takes: {@code 1} for an ordinary province,
	 * or a small dev-scaled cluster for a {@code city_terrain} province (capped at {@link
	 * #CITY_CORE_MAX} and at a fraction of the province's plots so a tiny province is not
	 * wholly urbanised).
	 */
	public static int coreSize(Province province, int landCount) {
		if (!province.city())
			return 1;
		int byDev = Math.max(2, province.development() / DEV_PER_CORE_PLOT);
		return Math.max(1, Math.min(Math.min(CITY_CORE_MAX, byDev), landCount / 8));
	}

	/**
	 * The core cell indices (into the {@code w×h} grids), <b>primary first</b>: the
	 * max-{@code foundValue} foundable cell, then — for {@code coreSize > 1} — the nearest
	 * foundable cells around it (a contiguous core). Grids are indexed {@code ly*w + lx};
	 * off-mask cells carry {@code null} ground and are skipped.
	 *
	 * @param w         grid width
	 * @param h         grid height
	 * @param landCells the land cells as {@code [lx, ly]} (the candidate set)
	 * @param ground    per-cell terrain (null off-mask)
	 * @param relief    per-cell relief
	 * @param feature   per-cell wild feature (nullable)
	 * @param bonus     per-cell resource (nullable)
	 * @param coreSize  how many core plots to return (see {@link #coreSize})
	 * @return the chosen cell indices, primary first (empty only if there is no foundable land)
	 */
	public static List<Integer> coreCells(int w, int h, List<int[]> landCells,
			Terrain[] ground, PlotType[] relief, Feature[] feature, Bonus[] bonus, int coreSize) {
		int primary = -1;
		double best = Double.NEGATIVE_INFINITY;
		for (int[] c : landCells) {
			int lx = c[0], ly = c[1];
			int idx = ly * w + lx;
			if (relief[idx] == PlotType.PEAK) // a city is not founded on a peak
				continue;
			double score = foundValue(lx, ly, w, h, ground, relief, feature, bonus);
			if (score > best) {
				best = score;
				primary = idx;
			}
		}
		List<Integer> core = new ArrayList<>();
		if (primary < 0)
			return core; // no foundable land (e.g. an all-peak province)
		core.add(primary);
		if (coreSize <= 1)
			return core;
		// grow a contiguous core: the nearest foundable cells to the primary
		int px = primary % w, py = primary / w;
		List<int[]> byDist = new ArrayList<>(landCells);
		byDist.sort((a, b) -> Long.compare(
				dist2(a[0], a[1], px, py), dist2(b[0], b[1], px, py)));
		for (int[] c : byDist) {
			if (core.size() >= coreSize)
				break;
			int idx = c[1] * w + c[0];
			if (idx == primary || relief[idx] == PlotType.PEAK)
				continue;
			core.add(idx);
		}
		return core;
	}

	// the Civ4-style foundValue of a candidate centre (lx, ly): the value reachable within
	// WORK_RADIUS — bonuses (distance-decayed) dominant, then yields and rivers — plus a small
	// centre-relief nudge. A pure read of the finished grids.
	private static double foundValue(int cx, int cy, int w, int h,
			Terrain[] ground, PlotType[] relief, Feature[] feature, Bonus[] bonus) {
		double score = 0;
		for (int dy = -WORK_RADIUS; dy <= WORK_RADIUS; dy++) {
			int ny = cy + dy;
			if (ny < 0 || ny >= h)
				continue;
			for (int dx = -WORK_RADIUS; dx <= WORK_RADIUS; dx++) {
				int nx = cx + dx;
				if (nx < 0 || nx >= w)
					continue;
				int nidx = ny * w + nx;
				if (ground[nidx] == null) // off-mask / water
					continue;
				long d2 = (long) dx * dx + (long) dy * dy;
				if (d2 > (long) WORK_RADIUS * WORK_RADIUS)
					continue;
				double decay = 1.0 / (1.0 + d2); // nearer cells count more
				if (bonus[nidx] != null)
					score += BONUS_WEIGHT * decay + yieldSum(bonus[nidx].yieldChanges());
				score += YIELD_WEIGHT * decay
						* (yieldSum(ground[nidx].yields())
								+ (feature[nidx] == null ? 0 : yieldSum(feature[nidx].yieldChanges())));
			}
		}
		if (relief[cy * w + cx] == PlotType.HILL)
			score += HILL_BONUS;
		return score;
	}

	private static double yieldSum(int[] yields) {
		if (yields == null)
			return 0;
		double s = 0;
		for (int y : yields)
			s += y;
		return s;
	}

	private static long dist2(int ax, int ay, int bx, int by) {
		long dx = ax - bx, dy = ay - by;
		return dx * dx + dy * dy;
	}
}
