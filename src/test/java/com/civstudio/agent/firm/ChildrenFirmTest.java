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
 * {@code docs/births.md}):
 * <ul>
 * <li>enrolled children grow their skills well above the unschooled newborn
 * baseline;</li>
 * <li>enrollment is capped at the school's capacity — when more children than
 * places exist, the school fills exactly its capacity (the oldest, by design)
 * and no more.</li>
 * </ul>
 * Both run a small births-enabled pool colony over a short horizon.
 */
class ChildrenFirmTest {

	/**
	 * With the school running at a high per-step learning rate, the children it
	 * trains reach skill levels far above the colony's mean starting skill — so
	 * schooling visibly works.
	 */
	@Test
	void schoolTrainsEnrolledChildrenAboveBaseline() {
		SimulationHarness h = assertDoesNotThrow(() -> fertileSchoolColony(
				// ample places, a high learning rate so the signal is unambiguous
				// (overallLevel is the mean of 12 skills while training hits one random
				// skill per tick, so the rate must be high to lift the mean clearly)
				ChildrenFirmConfig.builder().capacity(20).xpPerTick(500).build()));

		LocalDate today = h.getColony().getDate();
		int children = 0, maxChildSkill = 0;
		for (Agent a : h.getColony().getAgents())
			if (a instanceof Laborer l && l.isAlive())
				for (Member m : l.getMembers())
					if (!m.isAdult(today)) {
						children++;
						maxChildSkill =
								Math.max(maxChildSkill, m.skills().overallLevel());
					}

		assertTrue(children > 0, "expected children to have been born");
		assertTrue(h.getChildrenFirm().getLastEnrolled() > 0,
				"expected the school to be training children");
		// the colony's male mean skill is 5; an unschooled newborn's overall level sits
		// tightly around it (mean of 12 draws, sd well under 1), so a child above 10
		// can only have been trained by the school
		assertTrue(maxChildSkill > 10,
				"a schooled child should be skilled well above the newborn baseline, was "
						+ maxChildSkill);
	}

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
