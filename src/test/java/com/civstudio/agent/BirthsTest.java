package com.civstudio.agent;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

import com.civstudio.agent.laborer.FertilityConfig;
import com.civstudio.agent.laborer.Laborer;
import com.civstudio.bank.Bank;
import com.civstudio.name.Gender;
import com.civstudio.name.Person;
import com.civstudio.race.Race;
import com.civstudio.simulation.SimulationConfig;
import com.civstudio.simulation.SimulationHarness;

/**
 * Phase-1 coverage for the births / children mechanism (see {@code docs/births.md}):
 * <ul>
 * <li>{@link Member#isAdult(LocalDate)} switches a member from child to adult
 * exactly at its race's working-age floor;</li>
 * <li>a married laborer household, with births enabled and food on hand, bears
 * children — sub-working-age members that grow the population beyond the household
 * count.</li>
 * </ul>
 * Coming-of-age <i>into the workforce</i> end-to-end (a born child reaching working
 * age and earning) takes ~15 in-game years, longer than a pre-collapse horizon, so
 * it is validated by the boundary unit here and left to the calibrated long-horizon
 * runs of Phase 3.
 */
class BirthsTest {

	/** A human matures at the working-age floor of 15 — the child/adult boundary. */
	@Test
	void memberIsAdultAtTheWorkingAgeFloor() {
		LocalDate today = LocalDate.of(1450, 6, 1);
		Person p = new Person("Test", "Child", Gender.MALE, null, Race.HUMAN);
		int floorDays = Race.HUMAN.minInitAgeYears() * 365; // 15 * 365 = 5475

		Member oneDayShort = new Member(p, today.minusDays(floorDays - 1));
		Member exactlyOfAge = new Member(p, today.minusDays(floorDays));

		assertFalse(oneDayShort.isAdult(today), "below the floor a member is a child");
		assertTrue(exactlyOfAge.isAdult(today), "at the floor a member is an adult");
	}

	/**
	 * With births enabled, married households bear children: after a short run the
	 * colony holds sub-working-age members (born in-game), and the living laborer
	 * population (members) exceeds the number of laborer households — population
	 * renewal from within the household, the whole point of the feature.
	 */
	@Test
	void marriedHouseholdsBearChildren() {
		SimulationHarness h =
				assertDoesNotThrow(BirthsTest::runFertilePoolColony);

		LocalDate today = h.getColony().getDate();
		int households = 0, members = 0, children = 0, childrenWithParents = 0;
		for (Agent a : h.getColony().getAgents())
			if (a instanceof Laborer l) {
				households++;
				for (Member m : l.getMembers()) {
					members++;
					if (!m.isAdult(today)) {
						children++;
						// a colony-born child records its parentage
						if (m.getMother() != null && m.getFather() != null)
							childrenWithParents++;
					}
				}
			}

		assertTrue(children > 0,
				"expected married households to have borne children (sub-working-age members)");
		assertTrue(members > households,
				"the laborer population should exceed the household count (spouses + children)");
		// every child was born in-colony, so it knows both its parents
		assertEquals(children, childrenWithParents,
				"every colony-born child should record its mother and father");
	}

	/**
	 * A small {@link com.civstudio.simulation.HomogeneousEconomy}-style pool colony
	 * (weddings on by default) over a one-year horizon, with <b>fertility enabled</b>
	 * at a high daily birth rate and a shallow food buffer so households that pair up
	 * bear children well within the horizon. Necessity-heavy so food is plentiful.
	 */
	private static SimulationHarness runFertilePoolColony() {
		SimulationConfig cfg = SimulationConfig.DEFAULT.toBuilder()
				.durationYears(1).numEFirms(2).numNFirms(10)
				// pin the founding skill to the survival regime this wedding/births test
				// needs (the higher default destabilizes a small colony — see
				// docs/food-balance.md); this test exercises births, not skill
				.meanSkillMale(5).meanSkillFemale(2)
				.retinueSize(120).promotionRatio(0.4).build();
		SimulationHarness h = SimulationHarness.create(cfg, 7654321);
		h.createMarkets();
		Bank bank = h.getCopperBank();
		h.createFirms(bank, i -> bank,
				i -> cfg.eFirm().savings(), i -> cfg.nFirm().savings());
		h.createDefaultStrategicSector(bank);
		h.createDefaultRuler();
		h.createDefaultRetinue();
		h.foundLaborersFromRetinue(i -> bank, i -> 15);
		// enable births: a high per-day rate and a shallow food buffer so paired
		// households breed quickly within the short horizon
		h.getColony().setFertilityConfig(FertilityConfig.DEFAULT.toBuilder()
				.dailyBirthProb(0.1).foodBufferDays(3).build());
		h.run();
		return h;
	}
}
