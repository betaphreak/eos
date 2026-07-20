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
	 * Births are <b>universal</b>: not only laborers but the colony's <b>nobles</b> and
	 * its <b>ruler</b> bear children, through the one shared mechanism. After a short
	 * fertile run the non-laborer households (the ruler, and the nobles raised by
	 * ennoblement) hold colony-born children, each recording both parents — the path by
	 * which a noble/ruler line continues through its own issue rather than a fresh-drawn
	 * successor.
	 */
	@Test
	void noblesAndRulerBearChildren() {
		SimulationHarness h = assertDoesNotThrow(BirthsTest::runFertilePoolColony);

		LocalDate today = h.getColony().getDate();
		int nonLaborerChildren = 0, withParents = 0;
		for (Agent a : h.getColony().getAgents()) {
			if (a instanceof Laborer)
				continue; // the laborer path is covered above; here, the rest
			if (a instanceof AbstractHousehold household && household.isAlive())
				for (Member m : household.getMembers())
					if (!m.isAdult(today)) {
						nonLaborerChildren++;
						if (m.getMother() != null && m.getFather() != null)
							withParents++;
					}
		}

		assertTrue(nonLaborerChildren > 0,
				"expected the ruler and/or nobles to have borne children");
		assertEquals(nonLaborerChildren, withParents,
				"every colony-born noble/ruler child should record its mother and father");
	}

	/**
	 * A small {@link com.civstudio.simulation.HomogeneousEconomy}-style pool colony
	 * (weddings on by default) over a one-year horizon, with <b>fertility enabled</b>
	 * at a high daily birth rate and a shallow food buffer so households that pair up
	 * bear children well within the horizon. Necessity-heavy so food is plentiful.
	 */
	private static SimulationHarness runFertilePoolColony() {
		SimulationConfig cfg = SimulationConfig.DEFAULT.toBuilder()
				.durationYears(3).numEFirms(0).numNFirms(40)
				// pin the founding skill to the survival regime this wedding/births test
				// needs (the higher default destabilizes a small colony — see
				// docs/food-balance.md); this test exercises births, not skill
				.meanSkillMale(5).meanSkillFemale(2).build();
		SimulationHarness h = SimulationHarness.create(cfg, 7654321);
		// a small labor force the all-necessity sector fully employs, on a wide
		// peasant reserve, so laborers stay alive and prosperous long enough to
		// marry and bear children within the horizon (the old tight config
		// collapsed to lone unmarried heads under the re-baselined economy)
		h.tuneEconomy(e -> e.toBuilder()
				.retinueSize(300).promotionRatio(0.15).build());
		h.createMarkets();
		Bank bank = h.getCopperBank();
		h.createFirms(bank, i -> bank,
				i -> h.getColony().getEconomy().eFirm().savings(),
				i -> h.getColony().getEconomy().nFirm().savings());
		h.createDefaultStrategicSector(bank);
		h.createDefaultRuler();
		h.createDefaultRetinue();
		h.foundLaborersFromRetinue(i -> bank, i -> 15);
		// enable births: a high per-day rate and a shallow food buffer so paired
		// households breed quickly within the short horizon
		h.getColony().setFertilityConfig(FertilityConfig.DEFAULT.toBuilder()
				.dailyBirthProb(0.2).foodBufferDays(3).build());
		h.run();
		return h;
	}
}
