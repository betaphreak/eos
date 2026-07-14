package com.civstudio.simulation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.civstudio.agent.ExplorerCaravan;
import com.civstudio.agent.MarchingCaravan;
import com.civstudio.agent.Member;
import com.civstudio.agent.Retinue;
import com.civstudio.geo.RouteType;
import com.civstudio.settlement.GameSession;
import com.civstudio.settlement.Plot;
import com.civstudio.settlement.ProvincePlotPool;
import com.civstudio.settlement.Settlement;
import com.civstudio.util.Rng;

/**
 * Phase 3 of the Explorer caravan (docs/explorer-caravan.md §Phase 3 — trails &amp; the explored
 * map): the Explorer is the pioneer, stamping {@code ROUTE_TRAIL} on the plots it crosses so the
 * ground it explores becomes routable for the caravans that follow. Trails are per-session mutable
 * plot state (excluded from the canonical plot cache); the cost/routing effect is a later cut, so
 * this only verifies the stamping.
 */
class ExplorerTrailTest {

	private static final int DHENIJANSAR = 4411;

	@Test
	void anExplorerStampsTrailsOnThePlotsItCrosses() {
		SimulationConfig cfg = SimulationConfig.DEFAULT.toBuilder().durationYears(1).build();
		SimulationHarness h = SimulationHarness.create(cfg, 7654321, DHENIJANSAR);
		h.foundStandardColony(i -> cfg.eFirm().savings(), i -> cfg.nFirm().savings(), i -> 15);
		Settlement colony = h.getColony();
		GameSession session = colony.getSession();
		Retinue pool = h.getRetinue();
		int homeProvince = colony.getProvince().id();
		LocalDate today = colony.getDate();

		// draft a handful of adult peasants and muster the levy
		List<Member> draftees = new ArrayList<>();
		for (Member m : pool.getMembers()) {
			if (m.isAdult(today)) {
				draftees.add(m);
				if (draftees.size() == 5)
					break;
			}
		}
		assertEquals(5, draftees.size(), "the founded pool has adults to draft");
		double larder = draftees.size() * MarchingCaravan.WANDERING_RATION.perDay() * 70;
		ExplorerCaravan band = ExplorerCaravan.muster(colony, draftees, larder);
		band.setTripLimits(1e9, 20, 1);

		// before it moves, no plot of the home province carries a route
		ProvincePlotPool homePool = session.provincePlotPool(session.getWorldMap().province(homeProvince));
		assertEquals(0, homePool.plots().stream().filter(p -> p.routeType() != null).count(),
				"unpioneered ground carries no route");

		// drive the band out (summer — longest days) — crossing a province takes several days,
		// so it trails the home province's corridor before it leaves
		Rng rng = session.getBandRng();
		LocalDate day = LocalDate.of(1445, 6, 21);
		for (int t = 0; t < 40 && !band.hasArrived(); t++) {
			band.tick(day, rng);
			day = day.plusDays(1);
		}

		// the home province's plots now carry ROUTE_TRAIL where the explorer walked
		List<Plot> trailed = homePool.plots().stream().filter(p -> p.routeType() != null).toList();
		assertTrue(trailed.size() > 0, "the explorer pioneered trails on the ground it crossed");
		for (Plot p : trailed)
			assertEquals(RouteType.TRAIL, p.routeType().type(),
					"the pioneer lays ROUTE_TRAIL specifically");
	}
}
