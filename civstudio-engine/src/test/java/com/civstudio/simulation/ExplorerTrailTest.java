package com.civstudio.simulation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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

		// the home province (Dhenijansar) is an all-urban city, so its core plots come pre-paved
		// (ROUTE_PAVED_ROAD) — the explorer trails the RURAL ground it reaches beyond the city.
		// Drive it out (summer — longest days), tracking every province it enters.
		Set<Integer> visited = new HashSet<>();
		Rng rng = session.getBandRng();
		LocalDate day = LocalDate.of(1445, 6, 21);
		for (int t = 0; t < 80 && !band.hasArrived(); t++) {
			band.tick(day, rng);
			visited.add(band.getProvinceId());
			day = day.plusDays(1);
		}
		assertTrue(visited.size() > 1, "the explorer marched beyond its home province");

		// somewhere on the rural ground it crossed, it pioneered a ROUTE_TRAIL (distinct from the
		// city's pre-paved roads — so we look for the trail tier specifically)
		boolean trailed = false;
		for (int pid : visited) {
			ProvincePlotPool pp = session.provincePlotPool(session.getWorldMap().province(pid));
			for (Plot p : pp.plots())
				if (p.routeType() != null && RouteType.TRAIL.equals(p.routeType().type())) {
					trailed = true;
					break;
				}
			if (trailed)
				break;
		}
		assertTrue(trailed, "the explorer pioneered ROUTE_TRAIL on the rural ground it crossed");
	}
}
