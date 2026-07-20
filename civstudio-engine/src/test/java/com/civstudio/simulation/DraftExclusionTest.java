package com.civstudio.simulation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

import com.civstudio.agent.Member;
import com.civstudio.agent.Retinue;
import com.civstudio.name.Gender;

/**
 * Phase 1 of the Explorer caravan (docs/explorer-caravan.md): a <b>drafted</b> {@link
 * Member} — one away on an expedition levy — stays accounted in its pool/household but
 * participates in <b>no market</b> and is <b>not fed</b> by the colony (its caravan feeds
 * it), because it is not physically at the city center plot where the markets live
 * (decision 20). This pins those exclusions on the peasant pool, and that undrafting
 * restores full participation. Nothing musters a caravan yet, so on the live path no member
 * is ever drafted and the run is unchanged — this drives the flag directly.
 */
class DraftExclusionTest {

	// a standard colony on a short horizon, its labor force founded (and the remaining
	// pool left to draft in the tests)
	private static SimulationHarness foundColony() {
		SimulationConfig cfg = SimulationConfig.DEFAULT.toBuilder().durationYears(1).build();
		SimulationHarness h = SimulationHarness.create(cfg, 7654321);
		h.foundStandardColony();
		return h;
	}

	@Test
	void draftedFlagRoundTripsOnAMember() {
		Retinue retinue = foundColony().getRetinue();
		Member m = retinue.getMembers().get(0);
		assertFalse(m.isDrafted(), "a member starts undrafted");
		m.setDrafted(true);
		assertTrue(m.isDrafted(), "setDrafted(true) marks the member away on a levy");
		m.setDrafted(false);
		assertFalse(m.isDrafted(), "returning undrafts the member");
	}

	@Test
	void aDraftedPeasantIsNeitherPromotableNorWeddable() {
		SimulationHarness h = foundColony();
		Retinue retinue = h.getRetinue();
		LocalDate today = h.getColony().getDate();

		// draft every pooled peasant — the whole reserve is now "away"
		for (Member m : retinue.getMembers())
			m.setDrafted(true);

		assertNull(retinue.promoteHighestSkilled(),
				"a fully-drafted pool offers no one to promote");
		assertNull(retinue.bestSpouseCandidate(Gender.MALE),
				"a drafted peasant is not a marriage candidate");
		assertNull(retinue.bestSpouseCandidate(Gender.FEMALE),
				"a drafted peasant is not a marriage candidate");
		assertTrue(retinue.promoteHighestSkilled(3).isEmpty(),
				"a fully-drafted pool promotes no cohort");

		// bring one working-age peasant home: it is promotable again
		Member returned = null;
		for (Member m : retinue.getMembers())
			if (m.isAdult(today)) {
				m.setDrafted(false);
				returned = m;
				break;
			}
		assertNotNull(returned, "the founded pool holds at least one adult");
		assertNotNull(retinue.promoteHighestSkilled(),
				"an undrafted adult is promotable again");
	}

	@Test
	void draftedPeasantsAreNotFedByThePool() {
		SimulationHarness h = foundColony();
		Retinue retinue = h.getRetinue();

		// draw the larder down to the buy-threshold so the pool would otherwise buy (and,
		// if it could not, starve) this step
		retinue.drawPromotionStock(retinue.getLarder() - retinue.size());
		// draft the whole reserve — its food is its caravan's job now, not the pool's
		for (Member m : retinue.getMembers())
			m.setDrafted(true);

		// step the colony once: the pool feeds inside Retinue.act()
		h.getColony().start();
		h.getColony().newDay();

		assertEquals(0.0, retinue.getLastConsumed(), 1e-9,
				"a fully-drafted pool buys no food — its people are fed by their caravan");
		assertEquals(0, retinue.getLastStarved(),
				"a drafted peasant is away with its caravan, so it cannot starve in the pool");
	}
}
