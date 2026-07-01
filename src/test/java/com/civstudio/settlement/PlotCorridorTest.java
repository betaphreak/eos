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
		// each step costs at least 1, so the total is at least the number of steps
		assertTrue(c.totalCost() >= c.plotCount() - 1 - 1e-9,
				"total move cost is at least one per step");
		// the search is cached per (entry-plot, exit-plot)
		assertSame(c, pool.corridor(entry[0], entry[1], exit[0], exit[1]),
				"a repeated corridor request returns the cached result");
	}
}
