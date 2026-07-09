package com.civstudio.geo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * Level 1 of the land-routing model (see {@code docs/land-routing.md}): a
 * <b>distance-accurate</b> route over the {@link WorldMap} province graph. Where
 * {@link WorldMap#path(int, int)} returns the fewest-<em>hops</em> path (unweighted
 * BFS), this returns the shortest-<em>km</em> path — an A* search over <b>land</b>
 * provinces ({@link Province#isLand()}; water is {@link Province#isPassable()
 * passable} for the future sea graph but a foot caravan must not cross it) with
 * real km edge weights ({@link WorldMap#edgeKm(int,
 * int)}, the committed {@code /map/edges.json} table) and the great-circle {@link
 * WorldMap#distanceKm(int, int)} as its admissible heuristic. The result is a {@link
 * Route} (province sequence + per-hop km) the caravan march spends its daily distance
 * budget along ({@code docs/caravan-march.md} §6).
 * <p>
 * The per-province plot <b>corridor</b> (Level 2 — the plots crossed within each
 * province) is a later cut; this level gives the province sequence and the boundary-hop
 * km, which is what the metric march needs first.
 */
public final class LandRouter {

	private final WorldMap map;

	/**
	 * A router over the given world map.
	 *
	 * @param map the province graph to route on
	 */
	public LandRouter(WorldMap map) {
		this.map = map;
	}

	/**
	 * The shortest-km land route from {@code from} to {@code to} over passable
	 * provinces. A same-province request returns a single-node route; an unreachable
	 * goal (or an impassable endpoint) returns {@link Route#NONE}.
	 *
	 * @param from the origin province id
	 * @param to   the goal province id
	 * @return the route, or {@link Route#NONE} if none exists
	 * @throws IllegalArgumentException if either id is not in the map
	 */
	public Route route(int from, int to) {
		map.province(from); // validate endpoints
		map.province(to);
		if (!map.province(from).isLand() || !map.province(to).isLand())
			return Route.NONE;
		if (from == to)
			return new Route(List.of(from), new double[0], 0);

		// A* over passable provinces: g = km so far, f = g + straight-line-to-goal
		Map<Integer, Double> g = new HashMap<>();
		Map<Integer, Integer> cameFrom = new HashMap<>();
		PriorityQueue<long[]> open = new PriorityQueue<>((x, y) ->
				Double.compare(Double.longBitsToDouble(x[1]), Double.longBitsToDouble(y[1])));
		g.put(from, 0.0);
		open.add(new long[] { from, Double.doubleToRawLongBits(map.distanceKm(from, to)) });
		while (!open.isEmpty()) {
			int cur = (int) open.poll()[0];
			if (cur == to)
				return build(cameFrom, from, to);
			double gc = g.get(cur);
			for (int nb : map.neighbors(cur)) {
				if (!map.province(nb).isLand())
					continue; // land-only: never route a foot caravan over water or wasteland
				// weight the search by forageability so bands prefer routes they can eat along, and
				// detour around un-forageable ground (ice, cold tundra, the sunless Dwarovar) where
				// the destination allows. The march still spends REAL km (build() uses edgeKm), so this
				// shifts path CHOICE only; a penalty ≥ 1 keeps the km heuristic admissible.
				double tentative = gc + map.edgeKm(cur, nb) * forageHarshness(map.province(nb));
				Double best = g.get(nb);
				if (best == null || tentative < best) {
					g.put(nb, tentative);
					cameFrom.put(nb, cur);
					double f = tentative + map.distanceKm(nb, to);
					open.add(new long[] { nb, Double.doubleToRawLongBits(f) });
				}
			}
		}
		return Route.NONE;
	}

	/**
	 * A {@code >= 1} multiplier on an edge's <b>search</b> cost by how un-forageable its
	 * destination province is, so the router prefers routes a marching band can eat along.
	 * The real march distance is unchanged (this shifts path choice only): glacier ice and
	 * the sunless Dwarovar forage almost nothing, and the latitude-cooled tundra (see {@link
	 * LatitudeClimate}) forages progressively less as it turns cold. Temperate land and the
	 * special forest terrains forage fine ({@code 1.0}).
	 */
	private static double forageHarshness(Province p) {
		return switch (p.type()) {
			case GLACIER -> 6.0;                          // barren ice
			case CAVERN, DWARVEN_HOLD, DWARVEN_HOLD_SURFACE, DWARVEN_ROAD -> 4.0; // sunless, little forage
			case LAND -> 1.0 + 2.5 * LatitudeClimate.coldFraction(
					LatitudeClimate.effectiveTemperature(p.latitude(), p.winter())); // 1 → 3.5 toward the poles
			default -> 1.0;                               // forests / temperate ground forage fine
		};
	}

	// reconstruct the province sequence and its per-hop km from the came-from chain
	private Route build(Map<Integer, Integer> cameFrom, int from, int to) {
		List<Integer> seq = new ArrayList<>();
		for (int cur = to; cur != from; cur = cameFrom.get(cur))
			seq.add(cur);
		seq.add(from);
		Collections.reverse(seq);
		double[] hopKm = new double[seq.size() - 1];
		double total = 0;
		for (int i = 0; i < hopKm.length; i++) {
			hopKm[i] = map.edgeKm(seq.get(i), seq.get(i + 1));
			total += hopKm[i];
		}
		return new Route(Collections.unmodifiableList(seq), hopKm, total);
	}
}
