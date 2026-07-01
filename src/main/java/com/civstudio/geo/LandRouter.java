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
 * BFS), this returns the shortest-<em>km</em> path — an A* search over
 * <b>passable</b> provinces with real km edge weights ({@link WorldMap#edgeKm(int,
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
		if (!map.province(from).isPassable() || !map.province(to).isPassable())
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
				if (!map.province(nb).isPassable())
					continue; // cannot route through impassable wasteland
				double tentative = gc + map.edgeKm(cur, nb);
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
