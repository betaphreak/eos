package com.civstudio.geo;

/**
 * A Civ4/C2C <b>route</b> tier — a road that can be laid on a {@link
 * com.civstudio.settlement.Plot plot}, from the humble {@code ROUTE_TRAIL} up to the
 * far-future {@code ROUTE_JUMPLANE}. Exported from {@code CIV4RouteInfos.xml} by {@link
 * com.civstudio.geo.export.RouteExporter} into {@code /routes.json}, loaded by {@link
 * TerrainRegistry}. See {@code docs/explorer-caravan.md} §Phase 3 (trails &amp; the
 * explored map) and {@code docs/caravan-march.md} §6 (the route move discount).
 * <p>
 * Named {@code RouteType} (not {@code Route}) because {@link Route} already denotes the
 * province-graph pathfinding route; this is the C2C road-<em>tier</em> reference datum a plot
 * carries. Two roles in the movement model:
 * <ul>
 * <li><b>{@code ROUTE_TRAIL}</b> is the "explored / passable" mark an {@link
 * com.civstudio.agent.ExplorerCaravan} leaves on every plot it crosses — the only band that
 * may enter <em>route-less</em> (unimproved) ground. Every other caravan requires a plot to
 * carry at least a trail (it must have been pioneered first).</li>
 * <li>A route <b>overrides</b> the plot's terrain move cost with its own (a road over a hill
 * still pays the road's low cost, as in Civ4): the per-plot cost becomes {@link #costFactor()}
 * — {@link #movement}&nbsp;/&nbsp;100 — so a trail (100) costs one flat plot while a paved road
 * (40) is far cheaper, and corridors hug the better roads.</li>
 * </ul>
 * The tiers within the Renaissance tech cap are {@code TRAIL → PATH → ROAD → PAVED_ROAD}
 * (and {@code RAILROAD}); the later ones (highway, maglev, vactrain, jumplane…) are imported
 * but sit dormant beyond the horizon.
 *
 * @param type              the Civ4 type key (e.g. {@code ROUTE_TRAIL})
 * @param value             the tier rank ({@code <iValue>}) — {@code TRAIL=1 < PATH=2 <
 *                          ROAD=3 < …}; the "at least a trail" ordering and upgrade ladder
 * @param movement          the move cost to enter a plot carrying this route ({@code
 *                          <iMovement>}, hundredths — <b>lower is faster</b>; {@code TRAIL=100}
 *                          equals one flat plot). Drives {@link #costFactor()}
 * @param flatMovement      the tech-boosted flat-ground move cost ({@code <iFlatMovement>});
 *                          stored, dormant until the flat-route movement bonus is modelled
 * @param advancedStartCost the build-cost hook ({@code <iAdvancedStartCost>})
 * @param bonusType         the resource a plot must have to build this route ({@code
 *                          <BonusType>}, e.g. {@code BONUS_STONE} for a paved road), or {@code
 *                          null} for {@code NONE}
 * @param seaTunnel         whether the route may cross water ({@code <bSeaTunnel>} — the tunnel)
 * @param yields            the {@code [food, production, commerce]} a plot's route adds
 *                          ({@code <Yields>}; the later routes carry commerce), length 3
 */
public record RouteType(
		String type,
		int value,
		int movement,
		int flatMovement,
		int advancedStartCost,
		String bonusType,
		boolean seaTunnel,
		int[] yields) {

	/** The C2C trail — the "explored/passable" baseline route (see the class doc). */
	public static final String TRAIL = "ROUTE_TRAIL";

	/** Normalize {@code yields} to a defensive length-3 copy (missing → 0). */
	public RouteType {
		yields = Terrain.pad3(yields);
	}

	/**
	 * The per-plot move-cost <b>factor</b> this route imposes, overriding the terrain cost:
	 * {@link #movement}&nbsp;/&nbsp;100. A trail is {@code 1.0} (one flat plot — explored, no
	 * speed gain, but it neutralizes a hill/forest penalty since it overrides terrain); a road
	 * {@code 0.6}, a paved road {@code 0.4}, and so on down the ladder.
	 *
	 * @return the move-cost multiplier a route plot costs (lower = faster)
	 */
	public double costFactor() {
		return movement / 100.0;
	}

	/** Whether this is the {@link #TRAIL explored-baseline} trail. */
	public boolean isTrail() {
		return TRAIL.equals(type);
	}

	/** A yield component by index (0 = food, 1 = production, 2 = commerce). */
	public int yield(int i) {
		return yields[i];
	}

	@Override
	public String toString() {
		return type + "(v" + value + ", mv" + movement + ")";
	}
}
