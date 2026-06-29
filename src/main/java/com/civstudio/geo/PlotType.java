package com.civstudio.geo;

/**
 * The Civ4 <b>plot type</b> — the relief of a {@link com.civstudio.settlement.Plot
 * plot}, orthogonal to its {@link Terrain} (any terrain can be flat or hilly: a
 * grassland hill, a desert hill). A small fixed taxonomy, so an {@code enum} rather
 * than loaded data. See {@code docs/plots.md}.
 * <ul>
 * <li>{@link #FLAT} — ordinary workable ground.</li>
 * <li>{@link #HILL} — workable, adds a production bonus to the plot's yield and makes
 * a {@code MINE} valid ({@code bHillsMakesValid}). The production bonus is dormant
 * until a production firm sits on a plot (only food is live this cut).</li>
 * <li>{@link #PEAK} — <b>unworkable</b>: it takes no occupant and yields nothing
 * usable, yet still occupies a rung on the travel-time ladder (and counts toward
 * {@code province.plots}), so peaks among the near plots push workable land farther
 * out — a real terrain penalty for rough country.</li>
 * </ul>
 */
public enum PlotType {

	FLAT(0, true),
	HILL(1, true),
	PEAK(0, false);

	private final int productionBonus;
	private final boolean workable;

	PlotType(int productionBonus, boolean workable) {
		this.productionBonus = productionBonus;
		this.workable = workable;
	}

	/** Whether a firm can occupy and work a plot of this type (false for a peak). */
	public boolean isWorkable() {
		return workable;
	}

	/** The production (hammers) this relief adds to a plot's yield (a hill gives +1). */
	public int productionBonus() {
		return productionBonus;
	}

	/** Whether this relief makes a {@code MINE} a valid improvement (hills do). */
	public boolean makesMineValid() {
		return this == HILL;
	}
}
