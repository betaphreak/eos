package com.civstudio.geo;

import java.util.List;

/**
 * The committed <b>border portals</b> of one province: for each neighbour, a single
 * anchor pixel on the shared border (the border's midpoint on this province's side) — the
 * entry/exit point a caravan's per-province plot corridor is routed to/from (Level 2 of
 * {@code docs/land-routing.md}). Static geography (seed-independent), so it is precomputed
 * once by {@link com.civstudio.geo.export.PortalExporter} to {@code /map/portals.json} and
 * loaded by {@link WorldMap}.
 *
 * @param id      the province's id
 * @param portals its per-neighbour border anchors
 */
public record ProvincePortals(int id, List<Portal> portals) {

	/**
	 * One neighbour border anchor: the raster pixel on this province's side of the border
	 * with neighbour {@code to} (the border midpoint), where a corridor enters or leaves.
	 *
	 * @param to the neighbouring province id
	 * @param x  the anchor's raster x (on this province's side)
	 * @param y  the anchor's raster y
	 */
	public record Portal(int to, int x, int y) {
	}
}
