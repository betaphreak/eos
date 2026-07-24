package com.civstudio.server.render;

import java.util.List;

/**
 * A read-only projection of one of the colony's <b>plots</b> for the live feed — where it
 * sits, what stands on it, and what is rising on it. The web district view (see {@code
 * docs/district-buildout.md} Phase D5 and {@code docs/city-screen-plan.md}) joins each
 * building id against {@code /api/buildings} for its category and sprite.
 * <p>
 * <b>Every</b> plot of the colony projects, not only the built ones: the city screen lays out
 * the whole settlement, and a colony's plot count is bounded by its province cap (tens, not
 * thousands). Index 0 is the city center.
 * <p>
 * The {@code x}/{@code y} raster coordinates are the whole point of the record: they are in the
 * same source-pixel space as the web's plot grid, so a client can draw a building <b>on the plot
 * it actually stands on</b>. Before they shipped, the feed carried only the plot's index in
 * {@link com.civstudio.settlement.Settlement#getDistrictPlots()} — which a browser cannot resolve
 * — and every building in the colony drew on the city center.
 *
 * @param index     the plot's position in the colony's plot map (0 = city center)
 * @param x         the plot's raster x (the web's plot-grid space)
 * @param y         the plot's raster y
 * @param buildings the buildings standing on the plot
 * @param underway  the constructions currently rising on it (household self-builds, elite
 *                  commissions, and — on the center — the crown's active queue item)
 * @param fiefLord  the surname of the noble (or ruler) that holds this plot as a fief
 *                  (docs/estate-system.md P3), or {@code null} when it is Crown demesne — the
 *                  households resident here are that lord's vassals
 * @param households the number of peasant households resident on this plot — its size as a
 *                  {@link com.civstudio.settlement.Hamlet hamlet} (city-of-hamlets V1). {@code 0}
 *                  for the city center and for empty worked ground; a non-center plot with
 *                  households is a hamlet led by its {@code fiefLord} (or the Crown)
 */
public record DistrictView(int index, int x, int y, List<PlacedBuilding> buildings,
		List<Underway> underway, String fiefLord, int households) {

	/**
	 * One finished building on the plot, as a bare eos-native id (the verbatim C2C
	 * {@code BUILDING_*}) plus <b>whose</b> it is.
	 *
	 * @param id        the building's catalog id
	 * @param owner     who raised it: {@code RULER}, {@code NOBLE}, {@code HOUSEHOLD}, or
	 *                  {@code NONE} for an unowned building (orphaned by its owner's death, or
	 *                  inherited ground from an earlier colony — see {@link
	 *                  com.civstudio.settlement.Building})
	 * @param ownerName the owning household's surname, so a house can be named for the
	 *                  family that raised it; {@code null} for an unowned building or one
	 *                  whose owner has no dynasty
	 */
	public record PlacedBuilding(String id, String owner, String ownerName) {
	}

	/**
	 * One construction in flight on the plot — the state that makes a colony's building
	 * visible <em>while</em> it happens rather than only when it completes.
	 *
	 * @param id        the catalog id being raised
	 * @param cost      the work it needs in total (hammers, or builder build-units for a
	 *                  commission)
	 * @param progress  the work paid in so far — {@code progress/cost} is the bar
	 * @param owner     who is raising it: {@code RULER}, {@code NOBLE}, {@code HOUSEHOLD}
	 * @param ownerName the raising household's surname (so a rising house can be named for
	 *                  its family), or {@code null} where there is no dynasty behind it
	 */
	public record Underway(String id, double cost, double progress, String owner,
			String ownerName) {
	}
}
