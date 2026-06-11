package eos.mortality;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import eos.util.Rng;

/**
 * Verifies the Coale-Demeny West Level 3 mortality schedule: per-day death
 * probabilities are valid and rise with age into old age, sampled starting ages
 * are working-age, and draws are reproducible for a given seed.
 */
class LifeTableTest {

	private final LifeTable table = LifeTable.WEST_LEVEL_3;

	@Test
	void dailyDeathProbabilitiesAreValidAndRiseWithOldAge() {
		for (int age = 0; age <= 110; age++) {
			double p = table.dailyDeathProb(age);
			assertTrue(p >= 0 && p < 1, "prob out of range at age " + age + ": " + p);
		}
		// old age is deadlier than prime adulthood
		assertTrue(table.dailyDeathProb(70) > table.dailyDeathProb(30),
				"70 should be deadlier than 30");
		assertTrue(table.dailyDeathProb(30) > table.dailyDeathProb(10),
				"30 should be deadlier than 10");
	}

	@Test
	void initialAgesAreWorkingAge() {
		Demography demo = new Demography(new Rng(123));
		for (int i = 0; i < 1000; i++) {
			int years = demo.sampleInitialAgeDays() / 365;
			assertTrue(years >= 15 && years < 100,
					"unexpected starting age: " + years);
		}
	}

	@Test
	void sameSeedYieldsSameDraws() {
		Demography a = new Demography(new Rng(9));
		Demography b = new Demography(new Rng(9));
		for (int i = 0; i < 200; i++)
			assertEquals(a.sampleInitialAgeDays(), b.sampleInitialAgeDays());
	}
}
