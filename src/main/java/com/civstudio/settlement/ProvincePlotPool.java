package com.civstudio.settlement;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.civstudio.geo.Province;
import com.civstudio.geo.ProvincePlotField;
import com.civstudio.geo.ProvincePlotField.ProvincePlot;
import com.civstudio.geo.ProvinceRaster;
import com.civstudio.geo.TerrainRegistry;
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
 * {@code null}) until a settlement {@link #claim(Plot, Settlement) claims} it,
 * which transfers it to that settlement; {@link #release(Plot) releasing} returns
 * it to the free pool. This phase (2a) is the pool itself; wiring {@code
 * Settlement.claimPlot} to draw from it — with a per-settlement ladder and
 * best-plot selection — is the next slice.
 */
public final class ProvincePlotPool {

	private final Province province;
	private final List<Plot> plots;
	private int freeCount;

	private ProvincePlotPool(Province province, List<Plot> plots) {
		this.province = province;
		this.plots = plots;
		this.freeCount = plots.size();
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
	public int freeCount() {
		return freeCount;
	}

	/**
	 * Claim a free plot for a settlement, transferring its ownership.
	 *
	 * @param plot  a plot of this pool that is currently free
	 * @param owner the claiming settlement (non-null)
	 * @throws IllegalArgumentException if the plot is not free, not in this pool, or owner is null
	 */
	public void claim(Plot plot, Settlement owner) {
		if (owner == null)
			throw new IllegalArgumentException("owner must be non-null");
		if (plot == null || plot.owner() != null)
			throw new IllegalArgumentException("plot is not free to claim");
		plot.setOwner(owner);
		freeCount--;
	}

	/**
	 * Release a claimed plot back to the free pool (clearing its owner). A no-op for a
	 * plot that is already free.
	 *
	 * @param plot a plot of this pool
	 */
	public void release(Plot plot) {
		if (plot != null && plot.owner() != null) {
			plot.setOwner(null);
			freeCount++;
		}
	}
}
