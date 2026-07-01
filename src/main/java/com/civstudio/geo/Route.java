package com.civstudio.geo;

import java.util.List;

/**
 * A distance-accurate land route between two provinces: the ordered {@link
 * #provinces() province sequence} (inclusive of both endpoints) plus the per-hop km
 * ({@link #hopKm()}, one shorter than the sequence) and their {@link #totalKm()
 * total}. Produced by {@link LandRouter#route(int, int)} and consumed by the caravan
 * march, which spends its daily distance budget along it — a boundary hop costs {@code
 * hopKm[i]} (see {@code docs/caravan-march.md} §6). An empty route ({@link #isEmpty()})
 * means no land path exists.
 *
 * @param provinces the province ids from origin to goal, inclusive (empty if no route)
 * @param hopKm     the km of each hop, {@code hopKm[i]} from {@code provinces[i]} to
 *                  {@code provinces[i+1]} ({@code provinces.size() - 1} entries)
 * @param totalKm   the route's total length in km (the sum of {@code hopKm})
 */
public record Route(List<Integer> provinces, double[] hopKm, double totalKm) {

	/** The empty route — no land path between the endpoints. */
	public static final Route NONE = new Route(List.of(), new double[0], 0);

	/** Whether the route is empty (no path found). */
	public boolean isEmpty() {
		return provinces.isEmpty();
	}

	/** The number of province hops (0 for a same-province or empty route). */
	public int hops() {
		return hopKm.length;
	}
}
