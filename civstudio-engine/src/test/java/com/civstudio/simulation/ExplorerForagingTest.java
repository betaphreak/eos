package com.civstudio.simulation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.civstudio.agent.ExplorerCaravan;
import com.civstudio.agent.MarchingCaravan;
import com.civstudio.agent.Member;
import com.civstudio.agent.Retinue;
import com.civstudio.settlement.GameSession;
import com.civstudio.settlement.Settlement;
import com.civstudio.util.Rng;

/**
 * Phase 2 of the Explorer caravan (docs/explorer-caravan.md): a colony <b>musters</b> a foraging
 * levy from its people, the band marches out and comes back, and on return it <b>deposits its
 * food into the colony's granary</b> and <b>undrafts</b> its levy — the food-import round trip
 * (muster → out → home → deposit → undraft). The paid cash-out and the marriage re-entry
 * (decisions 14, 19) are a later cut.
 */
class ExplorerForagingTest {

	private static final int DHENIJANSAR = 4411;

	@Test
	void anExplorerMustersDraftsMarchesHomeDepositsFoodAndUndraftsItsLevy() {
		SimulationConfig cfg = SimulationConfig.DEFAULT.toBuilder().durationYears(1).build();
		SimulationHarness h = SimulationHarness.create(cfg, 7654321, DHENIJANSAR);
		h.foundStandardColony();
		Settlement colony = h.getColony();
		GameSession session = colony.getSession();
		Retinue pool = h.getRetinue();
		int homeProvince = colony.getProvince().id();
		LocalDate today = colony.getDate();

		// draft a handful of adult peasants out of the pool
		List<Member> draftees = new ArrayList<>();
		for (Member m : pool.getMembers()) {
			if (m.isAdult(today)) {
				draftees.add(m);
				if (draftees.size() == 5)
					break;
			}
		}
		assertEquals(5, draftees.size(), "the founded pool has adults to draft");

		// provision the levy for a round trip (the caravan feeds it from this larder), then
		// muster. The larder outlasts ~40 days out-and-back, so the band returns with a surplus
		// to deposit even without foraging.
		double larder = draftees.size() * MarchingCaravan.WANDERING_RATION.perDay() * 70;
		ExplorerCaravan band = ExplorerCaravan.muster(colony, draftees, larder);
		band.setCampingEnabled(false);   // skip plot-field generation — a fast round trip
		band.setTripLimits(1e9, 20, 1);  // turn home after 20 days out (not on haul / low larder)

		// mustering flags every draftee (they leave the colony's markets and table)
		for (Member m : draftees)
			assertTrue(m.isDrafted(), "a mustered draftee is drafted");
		assertEquals(homeProvince, band.getProvinceId(), "the band starts at its home province");

		double importedBefore = colony.getGranary().getTotalImported();

		// drive the band's daily tick (the home colony drives this in a later phase). March in
		// summer (longest days → fastest march) so it clears its home province and back within
		// the window regardless of the colony's founding date.
		Rng rng = session.getBandRng();
		LocalDate day = LocalDate.of(1445, 6, 21);
		boolean leftHome = false;
		for (int t = 0; t < 200 && !band.hasArrived(); t++) {
			band.tick(day, rng);
			if (band.getProvinceId() != homeProvince)
				leftHome = true;
			day = day.plusDays(1);
		}

		assertTrue(band.hasArrived(), "the expedition returns and completes within the window");
		assertTrue(leftHome, "the band actually marched out of its home province");
		assertEquals(homeProvince, band.getProvinceId(), "it returns to the home province");
		// its people are undrafted — back to work, market and marriage
		for (Member m : draftees)
			assertFalse(m.isDrafted(), "a returned draftee is undrafted");
		// it deposited its surplus food into the colony's strategic store
		assertTrue(colony.getGranary().getTotalImported() > importedBefore,
				"the expedition brought food home into the granary");
	}
}
