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
	// Nathalaire: a LAND province whose only graph neighbour is the sea province
	// 1303 — a sea-locked island. Its every mainland "neighbour" touches it only
	// across that water, so a foot caravan has no land route to it.
	private static final int NATHALAIRE = 451;
	private static final int MAINLAND = 2; // a land province the old passable A* reached 451 from, over sea

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

	@Test
	void aLandRouteNeverTraversesWater() {
		WorldMap map = WorldMap.load();
		Route r = new LandRouter(map).route(WITHACEN, DHENIJANSAR);
		assertFalse(r.isEmpty(), "a land route exists between the two");
		// a foot caravan marches over dry land only — never a SEA/LAKE province,
		// even where a straight sea hop would be shorter (docs/land-routing.md)
		for (int id : r.provinces())
			assertTrue(map.province(id).isLand(),
					"province " + id + " on a land route is " + map.province(id).type()
							+ ", not land");
	}

	@Test
	void noLandRouteToASeaLockedIsland() {
		WorldMap map = WorldMap.load();
		// precondition: Nathalaire is land, but its sole neighbour is open ocean
		assertEquals(ProvinceType.LAND, map.province(NATHALAIRE).type());
		assertTrue(map.neighbors(NATHALAIRE).stream()
				.noneMatch(nb -> map.province(nb).isLand()), "island has no land neighbour");
		// so there is no land route to it — the old passable A* reached it only by
		// paving the sea province between the mainland and the island (the reported bug)
		assertTrue(new LandRouter(map).route(MAINLAND, NATHALAIRE).isEmpty(),
				"a foot caravan cannot march to a sea-locked island");
	}

	@Test
	void noLandRouteToOrFromOpenOcean() {
		WorldMap map = WorldMap.load();
		int sea = map.provinces().stream().filter(p -> p.type() == ProvinceType.SEA)
				.findFirst().orElseThrow().id();
		LandRouter router = new LandRouter(map);
		assertTrue(router.route(DHENIJANSAR, sea).isEmpty(), "cannot march to open ocean");
		assertTrue(router.route(sea, DHENIJANSAR).isEmpty(), "cannot march out of open ocean");
	}
}
