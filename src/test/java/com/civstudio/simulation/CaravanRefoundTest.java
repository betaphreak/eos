package com.civstudio.simulation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.civstudio.agent.MigrantCaravan;
import com.civstudio.io.SimLog;
import com.civstudio.settlement.GameSession;
import com.civstudio.settlement.Settlement;

/**
 * The {@code CARAVAN → HOLDING} <b>re-founding</b> ({@code docs/caravan.md}, Phase 4):
 * a wandering band settles a fresh colony, closing the rise-fall-rise cycle. A colony
 * is founded, dissolved into a band, and the band re-founds a new colony in the same
 * session — led by the same dynasty, seeded with the band's people and hoard, and a
 * going concern in its own right.
 */
class CaravanRefoundTest {

	@Test
	void aBandReFoundsAViableColonyLedByItsFormerSovereign() {
		// short horizon: neither phase should collapse within it
		SimulationConfig cfg =
				SimulationConfig.DEFAULT.toBuilder().durationYears(1).build();

		// Phase 1: found a colony, run briefly, and dissolve it into a wandering band
		GameSession session = new GameSession(13579);
		Settlement origin = session.newSettlement(cfg.settlementName(),
				cfg.startDate(), cfg.meanInitAgeYears(), cfg.targetNStock(),
				cfg.meanSkillMale(), cfg.meanSkillFemale(), cfg.latitude(),
				cfg.longitude());
		SimLog.init(origin);
		SimulationHarness h0 = new SimulationHarness(cfg, origin);
		h0.foundStandardColony(i -> cfg.eFirm().savings(),
				i -> cfg.nFirm().savings(), i -> 15);
		h0.run();

		MigrantCaravan band = MigrantCaravan.dissolve(origin);
		session.addCaravan(band);
		assertTrue(band.getFollowing().size() > 0, "the band carries a following");
		assertTrue(band.getHoard() > 0, "the band carries a hoard");

		// Phase 2: the band re-founds a fresh colony at its position
		Settlement reborn = session.newSettlement(band, "New Hope", cfg.startDate(),
				cfg.meanInitAgeYears(), cfg.targetNStock(), cfg.meanSkillMale(),
				cfg.meanSkillFemale());
		SimLog.init(reborn);
		SimulationHarness h1 = new SimulationHarness(cfg, reborn);
		h1.reFoundStandardColony(band, i -> cfg.eFirm().savings(),
				i -> cfg.nFirm().savings(), i -> 15);

		// the same dynasty that led the band out rules the new colony, at the band's
		// site, with the three-currency banking rebuilt
		assertSame(band.getLeader(), reborn.getRuler().getHead(),
				"the band's leader rules the re-founded colony");
		assertEquals(origin.getLatitude(), reborn.getLatitude(),
				"the re-founded colony sits where the band settled");
		assertEquals(3, h1.getBanks().size(),
				"the three-currency hierarchy (copper, silver, gold) is rebuilt");

		// it promoted a labor force out of the band and runs as a going concern
		assertTrue(h1.getLaborers().length > 0,
				"a labor force was promoted from the band");
		h1.run();
		assertTrue(h1.currentLaborerCount() > 0,
				"the re-founded colony is a going concern (laborers remain after a year)");
	}
}
