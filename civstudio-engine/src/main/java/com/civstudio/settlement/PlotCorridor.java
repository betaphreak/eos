package com.civstudio.settlement;

import java.util.List;

/**
 * A caravan's <b>plot corridor</b> across one province: the sequence of {@link Plot}s it
 * crosses from the entry border to the exit border, and the total traversal cost — Level 2
 * of the land-routing model ({@code docs/land-routing.md}). Computed on demand from the
 * province's shared plot field ({@link ProvincePlotPool#corridor}) by an A* over the
 * plots' 4-neighbour raster adjacency, with {@link PlotType#PEAK peaks} impassable, so a
 * route bends around rough country and — once roads are laid — will hug the cheap road
 * plots.
 * <p>
 * The {@link #totalCost()} is dimensionless (a sum of per-plot move costs); the caravan
 * march multiplies it by {@code KM_PER_PLOT} to charge the day's distance budget (see
 * {@code docs/caravan-march.md} §6).
 *
 * @param path           the plots crossed, entry &rarr; exit (empty when none exists)
 * @param totalCost      the summed per-plot move cost of entering each plot after the first
 * @param riverCrossings the number of river plots on the path — each is a ford the caravan
 *                       march charges a full day for (see {@code docs/caravan-march.md} §6)
 */
public record PlotCorridor(List<Plot> path, double totalCost, int riverCrossings) {

	/** The empty corridor — no plot path between the entry and exit. */
	public static final PlotCorridor NONE = new PlotCorridor(List.of(), 0, 0);

	/** The number of plots crossed. */
	public int plotCount() {
		return path.size();
	}

	/** Whether the corridor is empty (no path found). */
	public boolean isEmpty() {
		return path.isEmpty();
	}
}
