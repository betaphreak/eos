package com.civstudio.settlement;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import com.civstudio.geo.Province;
import com.civstudio.geo.ProvincePlotField;
import com.civstudio.geo.ProvincePlotField.ProvincePlot;
import com.civstudio.geo.ProvinceRaster;
import com.civstudio.geo.TerrainRegistry;
import com.civstudio.tech.Sector;
import com.civstudio.util.Rng;

/**
 * The shared, claimable plot field of one province: the {@link Province}'s land
 * pixels realised as occupiable {@link Plot}s, owned by the province until a
 * settlement claims them. Generated once per province (lazily, cached on the
 * {@link GameSession}) from the Phase-1 {@link ProvincePlotField} terrain
 * generation, and shared by every settlement founded into the province — the
 * substrate for several settlements occupying one province (see {@code
 * docs/province-plots.md}).
 * <p>
 * Ownership is <b>hybrid</b>: a plot is free (province-owned, {@link Plot#owner()}
 * {@code null}) until a settlement claims it, which transfers it to that
 * settlement; {@link #release(Plot) releasing} returns it to the free pool. A
 * settlement claims via {@link #claimFoundingCenter} (its first plot, spaced from
 * any other settlement already in the province) then {@link #claimNearest} (each
 * subsequent plot, nearest its center). Because several settlements in one province
 * claim from the same pool — concurrently, under the lockstep
 * {@link com.civstudio.simulation.SessionRunner} — every mutating operation is
 * {@code synchronized}; a claim that finds no free plot returns {@code null} (the
 * caller treats it as "no room", never an error). See {@code docs/province-plots.md}.
 */
public final class ProvincePlotPool {

	private final Province province;
	private final List<Plot> plots;
	private int freeCount;
	private final int centroidX; // the founding anchor for the first settlement
	private final int centroidY;

	private ProvincePlotPool(Province province, List<Plot> plots) {
		this.province = province;
		this.plots = plots;
		this.freeCount = plots.size();
		long sx = 0, sy = 0;
		for (Plot p : plots) {
			sx += p.x();
			sy += p.y();
		}
		int n = Math.max(1, plots.size());
		this.centroidX = Math.round((float) sx / n);
		this.centroidY = Math.round((float) sy / n);
	}

	/**
	 * Build a province's pool: generate its plot field and realise each
	 * {@link ProvincePlot} as a free, claimable {@link Plot} (position + terrain +
	 * relief + feature + resource). Deterministic per {@code rng} (a per-province
	 * terrain stream).
	 *
	 * @param province the province to build the pool for
	 * @param registry the curated terrain/feature/bonus definitions
	 * @param raster   the raster reader (supplies the province mask)
	 * @param rng      the dedicated per-province terrain stream
	 * @return the province's claimable plot pool
	 */
	public static ProvincePlotPool generate(Province province, TerrainRegistry registry,
			ProvinceRaster raster, Rng rng) throws IOException {
		ProvincePlotField field = ProvincePlotField.generate(province, registry, raster, rng);
		List<Plot> plots = new ArrayList<>(field.size());
		for (ProvincePlot pp : field.plots())
			plots.add(new Plot(pp.x(), pp.y(), pp.terrain(), pp.plotType(), pp.feature(), pp.bonus()));
		return new ProvincePlotPool(province, plots);
	}

	/** The province this pool belongs to. */
	public Province province() {
		return province;
	}

	/** All plots of the province (free and claimed), an unmodifiable view. */
	public List<Plot> plots() {
		return Collections.unmodifiableList(plots);
	}

	/** The total number of plots (== {@code province.plots()}). */
	public int size() {
		return plots.size();
	}

	/** The number of unclaimed (province-owned) plots. */
	public synchronized int freeCount() {
		return freeCount;
	}

	/**
	 * Claim the free plot nearest {@code (cx, cy)} for a settlement, transferring its
	 * ownership — a settlement's per-plot claim, growing outward from its center.
	 *
	 * @param owner the claiming settlement (non-null)
	 * @param cx    the settlement's center x
	 * @param cy    the settlement's center y
	 * @return the claimed plot, or {@code null} if the pool is exhausted
	 */
	public synchronized Plot claimNearest(Settlement owner, int cx, int cy) {
		return take(owner, bestYieldNearest(cx, cy));
	}

	/**
	 * Claim a settlement's <b>founding center</b>: the free plot best spaced from any
	 * plots already claimed by <em>other</em> settlements in the province (so several
	 * settlements spread out), or — for the first settlement — the plot nearest the
	 * province centroid.
	 *
	 * @param owner the founding settlement (non-null)
	 * @return the claimed center plot, or {@code null} if the pool is exhausted
	 */
	public synchronized Plot claimFoundingCenter(Settlement owner) {
		return take(owner, foundingCenter(owner));
	}

	/**
	 * Claim a specific free plot for a settlement (used directly in tests; settlements
	 * claim via {@link #claimNearest}/{@link #claimFoundingCenter}).
	 *
	 * @param plot  a plot of this pool that is currently free
	 * @param owner the claiming settlement (non-null)
	 * @throws IllegalArgumentException if the plot is not free or owner is null
	 */
	public synchronized void claim(Plot plot, Settlement owner) {
		if (owner == null)
			throw new IllegalArgumentException("owner must be non-null");
		if (plot == null || plot.owner() != null)
			throw new IllegalArgumentException("plot is not free to claim");
		take(owner, plot);
	}

	/**
	 * Release a claimed plot back to the free pool (clearing its owner). A no-op for a
	 * plot that is already free.
	 *
	 * @param plot a plot of this pool
	 */
	public synchronized void release(Plot plot) {
		if (plot != null && plot.owner() != null) {
			plot.setOwner(null);
			freeCount++;
		}
	}

	// transfer a (free) plot to owner; null-safe so the claim helpers can pass a
	// failed search straight through as a null result
	private Plot take(Settlement owner, Plot plot) {
		if (plot == null)
			return null;
		plot.setOwner(owner);
		freeCount--;
		return plot;
	}

	// how many of the nearest free plots a settlement weighs by food yield when it
	// claims — it takes the best-food plot among its nearest few, favouring good land
	// in its vicinity without abandoning the nearest-first growth (which keeps the
	// travel ladder and the food-balance calibration stable). A small, proximity-
	// dominant placeholder pending calibration (1 == pure nearest-first).
	private static final int YIELD_CHOICE_NEIGHBOURS = 4;

	// the best-food free plot among the YIELD_CHOICE_NEIGHBOURS nearest to (cx, cy):
	// proximity picks the candidate band, yield picks within it (Phase 4b/4c). Returns
	// null if the pool is exhausted.
	private Plot bestYieldNearest(int cx, int cy) {
		int k = YIELD_CHOICE_NEIGHBOURS;
		long[] nearDist = new long[k];
		Plot[] nearPlot = new Plot[k];
		Arrays.fill(nearDist, Long.MAX_VALUE);
		for (Plot p : plots) {
			if (p.owner() != null)
				continue;
			long d = dist2(p, cx, cy);
			// keep the k nearest: replace the current farthest if this is closer
			int farthest = 0;
			for (int i = 1; i < k; i++)
				if (nearDist[i] > nearDist[farthest])
					farthest = i;
			if (d < nearDist[farthest]) {
				nearDist[farthest] = d;
				nearPlot[farthest] = p;
			}
		}
		Plot best = null;
		double bestYield = -1;
		for (Plot p : nearPlot) {
			if (p == null)
				continue;
			double y = p.yieldFactor(Sector.NECESSITY);
			if (y > bestYield) {
				bestYield = y;
				best = p;
			}
		}
		return best;
	}

	// the free plot maximizing the minimum distance to any other settlement's claimed
	// plots (min-distance auto-spacing); the centroid-nearest free plot if this is the
	// first settlement to claim in the province
	private Plot foundingCenter(Settlement owner) {
		boolean anyOther = false;
		for (Plot p : plots)
			if (p.owner() != null && p.owner() != owner) {
				anyOther = true;
				break;
			}
		if (!anyOther)
			return bestYieldNearest(centroidX, centroidY);

		Plot best = null;
		long bestSpacing = -1;
		for (Plot p : plots) {
			if (p.owner() != null)
				continue;
			long minDist = Long.MAX_VALUE;
			for (Plot q : plots)
				if (q.owner() != null && q.owner() != owner)
					minDist = Math.min(minDist, dist2(p, q.x(), q.y()));
			if (minDist > bestSpacing) {
				bestSpacing = minDist;
				best = p;
			}
		}
		return best;
	}

	private static long dist2(Plot p, int x, int y) {
		long dx = p.x() - x, dy = p.y() - y;
		return dx * dx + dy * dy;
	}
}
