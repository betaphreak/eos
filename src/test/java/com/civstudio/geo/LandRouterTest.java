package com.civstudio.geo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Level-1 land routing ({@link LandRouter}): a distance-accurate, km-weighted route
 * over the province graph, and the committed {@code /map/edges.json} weights it reads
 * ({@link WorldMap#edgeKm}, {@link WorldMap#distanceKm}). See {@code
 * docs/land-routing.md}.
 */
class LandRouterTest {

	// two directly-adjacent LAND provinces, and a far-away coastal one
	private static final int WITHACEN = 515;
	private static final int HOPESPEAK = 519;
	private static final int DHENIJANSAR = 4411;

	@Test
	void committedEdgeWeightsAreLoadedAndPositive() {
		WorldMap map = WorldMap.load();
		assertTrue(map.neighbors(WITHACEN).contains(HOPESPEAK), "fixture adjacency");
		double edge = map.edgeKm(WITHACEN, HOPESPEAK);
		assertTrue(edge > 0, "an adjacent edge has positive km");
		// this cut's committed weight is the centroid great-circle distance
		assertEquals(map.distanceKm(WITHACEN, HOPESPEAK), edge, 0.01,
				"the committed edge weight matches the centroid distance");
	}

	@Test
	void sameProvinceRouteIsASingleNode() {
		LandRouter router = new LandRouter(WorldMap.load());
		Route r = router.route(DHENIJANSAR, DHENIJANSAR);
		assertFalse(r.isEmpty());
		assertEquals(List.of(DHENIJANSAR), r.provinces());
		assertEquals(0, r.hops());
		assertEquals(0.0, r.totalKm(), 0.0);
	}

	@Test
	void adjacentRouteIsTwoNodesOneHop() {
		WorldMap map = WorldMap.load();
		Route r = new LandRouter(map).route(WITHACEN, HOPESPEAK);
		assertEquals(List.of(WITHACEN, HOPESPEAK), r.provinces());
		assertEquals(1, r.hops());
		assertEquals(map.edgeKm(WITHACEN, HOPESPEAK), r.totalKm(), 1e-9);
	}

	@Test
	void longRouteIsContiguousAndNoShorterThanStraightLine() {
		WorldMap map = WorldMap.load();
		LandRouter router = new LandRouter(map);
		Route r = router.route(WITHACEN, DHENIJANSAR);
		assertFalse(r.isEmpty(), "a land route exists between the two");
		// every consecutive pair is a real neighbour edge, and totalKm sums the hops
		double sum = 0;
		for (int i = 0; i + 1 < r.provinces().size(); i++) {
			int a = r.provinces().get(i), b = r.provinces().get(i + 1);
			assertTrue(map.neighbors(a).contains(b), "hop " + a + "->" + b + " is an edge");
			sum += r.hopKm()[i];
		}
		assertEquals(sum, r.totalKm(), 1e-6);
		// the great-circle distance is a lower bound on any real path
		assertTrue(r.totalKm() >= map.distanceKm(WITHACEN, DHENIJANSAR) - 1e-6,
				"the route is no shorter than the straight-line distance");
	}
}
