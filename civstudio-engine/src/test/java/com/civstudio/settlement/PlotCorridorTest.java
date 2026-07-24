package com.civstudio.settlement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.civstudio.geo.Province;
import com.civstudio.geo.RouteType;
import com.civstudio.geo.WorldMap;

/**
 * Level-2 land routing: the committed border {@link WorldMap#portal portals} and the
 * per-province {@link ProvincePlotPool#corridor plot corridor} a caravan crosses (see
 * {@code docs/land-routing.md}). Founded on Dhenijansar (4411), a small coastal province.
 */
class PlotCorridorTest {

	private static final int DHENIJANSAR = 4411;

	@Test
	void bordersHaveCommittedPortals() {
		WorldMap map = WorldMap.load();
		Province p = map.province(DHENIJANSAR);
		int found = 0;
		for (int nb : p.neighbors())
			if (map.portal(DHENIJANSAR, nb) != null)
				found++;
		assertTrue(found > 0, "the province has committed border portals to its neighbours");
	}

	@Test
	void corridorCrossesTheProvinceContiguouslyAndCaches() {
		GameSession session = new GameSession(3);
		WorldMap map = session.getWorldMap();
		Province dhen = map.province(DHENIJANSAR);
		ProvincePlotPool pool = session.provincePlotPool(dhen);
		assertTrue(pool.size() > 1, "the province has a plot field");

		// two border anchors as far apart as the province's portals allow, so the corridor
		// actually crosses the province rather than snapping to one plot
		List<int[]> portals = new ArrayList<>();
		for (int nb : dhen.neighbors()) {
			int[] pt = map.portal(DHENIJANSAR, nb);
			if (pt != null)
				portals.add(pt);
		}
		assertTrue(portals.size() >= 2, "the province has at least two border anchors");
		int[] entry = portals.get(0);
		int[] exit = entry;
		long best = -1;
		for (int[] pt : portals) {
			long d = (long) (pt[0] - entry[0]) * (pt[0] - entry[0])
					+ (long) (pt[1] - entry[1]) * (pt[1] - entry[1]);
			if (d > best) {
				best = d;
				exit = pt;
			}
		}

		PlotCorridor c = pool.corridor(entry[0], entry[1], exit[0], exit[1]);
		assertFalse(c.isEmpty(), "a corridor exists across the province");
		assertTrue(c.plotCount() >= 1);
		// consecutive corridor plots are 4-adjacent on the raster
		for (int i = 1; i < c.path().size(); i++) {
			Plot a = c.path().get(i - 1), b = c.path().get(i);
			assertEquals(1, Math.abs(a.x() - b.x()) + Math.abs(a.y() - b.y()),
					"consecutive corridor plots are 4-neighbours");
			assertTrue(b.isWorkable(), "a corridor never crosses an unworkable peak");
		}
		// every step costs a positive amount, but a downhill (Tobler) step can cost below 1,
		// so the total is no longer bounded by one-per-step — only that a corridor crossing
		// more than one plot has a strictly positive move cost (docs/caravan-march.md §6)
		if (c.plotCount() > 1)
			assertTrue(c.totalCost() > 0, "a multi-plot corridor has a positive move cost");
		// riverCrossings counts the river plots on the path (the fords the march charges a
		// full day each — docs/caravan-march.md §6)
		int riversOnPath = 0;
		for (Plot p : c.path())
			if (p.river())
				riversOnPath++;
		assertEquals(riversOnPath, c.riverCrossings(),
				"riverCrossings equals the number of river plots on the corridor");
		// the search is cached per (entry-plot, exit-plot)
		assertSame(c, pool.corridor(entry[0], entry[1], exit[0], exit[1]),
				"a repeated corridor request returns the cached result");
	}

	@Test
	void urbanPlotsComeTrailed() {
		GameSession session = new GameSession(3);
		// Dhenijansar is an all-urban city province — every plot is city-core ground
		ProvincePlotPool pool = session.provincePlotPool(session.getWorldMap().province(DHENIJANSAR));
		long urban = pool.plots().stream()
				.filter(Plot::urban).count();
		assertTrue(urban > 0, "the city province has urban core plots");
		for (Plot p : pool.plots())
			if (p.urban()) {
				assertNotNull(p.routeType(), "an urban plot comes with a route by default");
				assertEquals(RouteType.TRAIL, p.routeType().type(),
						"urban plots start on a trail — routable from founding, but unpaved (a city earns its paving)");
			}
	}

	@Test
	void aBetterRouteOnTheCorridorLowersItsMoveCostAfterInvalidation() {
		GameSession session = new GameSession(3);
		WorldMap map = session.getWorldMap();
		Province dhen = map.province(DHENIJANSAR);
		ProvincePlotPool pool = session.provincePlotPool(dhen);

		// two border anchors as far apart as the province's portals allow (as above)
		List<int[]> portals = new ArrayList<>();
		for (int nb : dhen.neighbors()) {
			int[] pt = map.portal(DHENIJANSAR, nb);
			if (pt != null)
				portals.add(pt);
		}
		int[] entry = portals.get(0);
		int[] exit = entry;
		long best = -1;
		for (int[] pt : portals) {
			long d = (long) (pt[0] - entry[0]) * (pt[0] - entry[0])
					+ (long) (pt[1] - entry[1]) * (pt[1] - entry[1]);
			if (d > best) {
				best = d;
				exit = pt;
			}
		}

		PlotCorridor before = pool.corridor(entry[0], entry[1], exit[0], exit[1]);
		assertTrue(before.plotCount() > 1, "a multi-plot corridor to upgrade");
		double costBefore = before.totalCost();

		// Dhenijansar is an all-urban city, so its corridor is already TRAILED (a slow baseline route);
		// lay a strictly-better HIGHWAY (0.22) on every plot to exercise the route override
		RouteType highway = session.getTerrainRegistry().route("ROUTE_HIGHWAY");
		assertNotNull(highway);
		for (Plot p : before.path())
			p.layRoute(highway);

		// the cache still returns the pre-upgrade cost until it is invalidated
		assertEquals(costBefore, pool.corridor(entry[0], entry[1], exit[0], exit[1]).totalCost(), 1e-9,
				"a cached corridor keeps its stale cost until invalidated");

		pool.invalidateCorridorCache();
		PlotCorridor after = pool.corridor(entry[0], entry[1], exit[0], exit[1]);
		assertTrue(after.totalCost() < costBefore,
				"a better-roaded corridor is cheaper to cross "
						+ "(the route caps the flat cost; the slope term still applies)");
	}
}
