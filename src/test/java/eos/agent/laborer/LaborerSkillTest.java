package eos.agent.laborer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import eos.agent.Household;
import eos.name.Person;

/**
 * Verifies the skill &rarr; labor-productivity curve
 * ({@link Household#productivityOf(int)}): it passes through the three anchor
 * points (skill 0 &rarr; 0.01, skill 10 &rarr; 1, skill 20 &rarr; 4), follows
 * the quadratic {@code (skill/10)^2} with a 0.01 floor, and is non-decreasing.
 */
class LaborerSkillTest {

	private static final double EPS = 1e-9;

	@Test
	void curveHitsTheAnchorPoints() {
		assertEquals(0.01, Household.productivityOf(0), EPS, "skill 0");
		assertEquals(1.0, Household.productivityOf(10), EPS, "skill 10");
		assertEquals(4.0, Household.productivityOf(20), EPS, "skill 20");
	}

	@Test
	void curveIsQuadraticWithAFloor() {
		// (skill/10)^2 above the floor; skill 1 sits exactly on the 0.01 floor
		assertEquals(0.01, Household.productivityOf(1), EPS, "skill 1");
		assertEquals(0.04, Household.productivityOf(2), EPS, "skill 2");
		assertEquals(0.25, Household.productivityOf(5), EPS, "skill 5");
		assertEquals(2.25, Household.productivityOf(15), EPS, "skill 15");
	}

	@Test
	void productivityNeverDecreasesWithSkill() {
		double prev = -1;
		for (int s = 0; s <= 20; s++) {
			double p = Household.productivityOf(s);
			assertTrue(p >= prev, "productivity dipped at skill " + s);
			prev = p;
		}
	}

	@Test
	void notableMeansSkillAboveFifteen() {
		assertFalse(householdWithSkill(15).isNotable(), "skill 15 is not notable");
		assertTrue(householdWithSkill(16).isNotable(), "skill 16 is notable");
		assertTrue(householdWithSkill(20).isNotable(), "skill 20 is notable");
	}

	private static Household householdWithSkill(int skill) {
		return new Household() {
			public java.util.List<Person> getMembers() {
				return java.util.Collections.singletonList(null);
			}

			public int getSkill() {
				return skill;
			}

			public int getAgeYears() {
				return 0;
			}

			public double getIncome() {
				return 0;
			}

			public double getWealth() {
				return 0;
			}

			public eos.bank.Bank getBank() {
				return null;
			}
		};
	}
}
