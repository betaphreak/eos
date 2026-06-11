package eos.mortality;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import eos.util.Rng;

/**
 * Verifies the Coale-Demeny West Level 3 mortality schedule: per-day death
 * probabilities are valid and rise with age into old age, founding ages are
 * working-age and centered on the configured mean, and draws are reproducible
 * for a given seed.
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
	void initialAgesAreWorkingAgeAndCenteredOnMean() {
		Demography demo = new Demography(new Rng(123));
		int n = 10000;
		long sumYears = 0;
		for (int i = 0; i < n; i++) {
			int years = demo.sampleInitialAgeDays() / 365;
			assertTrue(years >= 15 && years < 100,
					"unexpected starting age: " + years);
			sumYears += years;
		}
		// founding ages are drawn ~N(35, 10) truncated below at 15, so the mean
		// sits just above 35 (the truncated left tail pulls it up slightly)
		double mean = (double) sumYears / n;
		assertTrue(mean > 33 && mean < 38, "unexpected mean starting age: " + mean);
	}

	@Test
	void sameSeedYieldsSameDraws() {
		Demography a = new Demography(new Rng(9));
		Demography b = new Demography(new Rng(9));
		for (int i = 0; i < 200; i++)
			assertEquals(a.sampleInitialAgeDays(), b.sampleInitialAgeDays());
	}
}
