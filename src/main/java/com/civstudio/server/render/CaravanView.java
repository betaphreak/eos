package com.civstudio.server.render;

/**
 * A read-only projection of one {@link com.civstudio.agent.Caravan wandering band} for the
 * live feed — the hero of the Phase-A demo (six caravans marching over the world map). A
 * plain immutable record so it serializes straight to JSON and never exposes the live agent
 * graph. Assembled on the session thread by {@link Snapshots} between ticks.
 *
 * @param label     a display label (the band's leader)
 * @param leader    the band leader's full name
 * @param latitude  current latitude (decimal degrees, north positive)
 * @param longitude current longitude (decimal degrees, east positive)
 * @param provinceId the band's province node ({@code -1} if off the graph)
 * @param province  the band's current province name (or a placeholder off-graph)
 * @param onGraph   whether the band sits on the province graph (so it can march)
 * @param settled   whether the band has reached a site and awaits re-founding
 * @param bandSize  the number of people following the band
 * @param larder    the band's remaining food larder (its countdown to starvation)
 * @param hoard     the band's carried money (copper)
 */
public record CaravanView(String label, String leader, double latitude, double longitude,
		int provinceId, String province, boolean onGraph, boolean settled, int bandSize,
		double larder, double hoard) {
}
