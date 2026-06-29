package com.civstudio.agent.firm;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

import com.civstudio.agent.Agent;
import com.civstudio.agent.Member;
import com.civstudio.agent.laborer.FertilityConfig;
import com.civstudio.agent.laborer.Laborer;
import com.civstudio.bank.Bank;
import com.civstudio.simulation.SimulationConfig;
import com.civstudio.simulation.SimulationHarness;

/**
 * Phase-4 coverage for the {@link ChildrenFirm} — the colony's civic school (see
 * {@code docs/births.md}): enrollment is capped at the school's capacity — when
 * more children than places exist, the school fills exactly its capacity (the
 * oldest, by design) and no more. Runs a small births-enabled pool colony over a
 * short horizon.
 */
class ChildrenFirmTest {

	/**
	 * When more children exist than the school has places, it fills exactly its
	 * capacity and trains no more — the cap (oldest-first by design) holds.
	 */
	@Test
	void schoolEnrollmentIsCappedAtCapacity() {
		int capacity = 2;
		SimulationHarness h = assertDoesNotThrow(() -> fertileSchoolColony(
				ChildrenFirmConfig.builder().capacity(capacity).xpPerTick(1).build()));

		LocalDate today = h.getColony().getDate();
		int children = 0;
		for (Agent a : h.getColony().getAgents())
			if (a instanceof Laborer l && l.isAlive())
				for (Member m : l.getMembers())
					if (!m.isAdult(today))
						children++;

		assertTrue(children > capacity,
				"the colony should hold more children than the school's places");
		assertEquals(capacity, h.getChildrenFirm().getLastEnrolled(),
				"the school should enroll exactly its capacity when oversubscribed");
	}

	/**
	 * A small births-enabled pool colony (weddings on by default) with a civic school
	 * configured by {@code schoolConfig}, over a one-year horizon. Necessity-heavy so
	 * food is plentiful and married households breed.
	 */
	private static SimulationHarness fertileSchoolColony(
			ChildrenFirmConfig schoolConfig) {
		SimulationConfig cfg = SimulationConfig.DEFAULT.toBuilder()
				.durationYears(1).numEFirms(2).numNFirms(10)
				// pin the founding skill to the survival regime this school/births test
				// needs (the higher default destabilizes a small colony — see
				// docs/food-balance.md); this test exercises schooling, not skill
				.meanSkillMale(5).meanSkillFemale(2)
				.retinueSize(120).promotionRatio(0.4).build();
		SimulationHarness h = SimulationHarness.create(cfg, 7654321);
		h.setChildrenFirmConfig(schoolConfig);
		h.createMarkets();
		Bank bank = h.getCopperBank();
		h.createFirms(bank, i -> bank,
				i -> cfg.eFirm().savings(), i -> cfg.nFirm().savings());
		h.createDefaultStrategicSector(bank);
		h.createDefaultRuler();
		h.createDefaultRetinue();
		h.foundLaborersFromRetinue(i -> bank, i -> 15);
		h.createDefaultChildrenFirm();
		// enable births so the school has pupils
		h.getColony().setFertilityConfig(FertilityConfig.DEFAULT.toBuilder()
				.dailyBirthProb(0.1).foodBufferDays(3).build());
		h.run();
		return h;
	}
}
