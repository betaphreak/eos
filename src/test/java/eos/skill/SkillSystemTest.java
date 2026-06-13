package eos.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** Unit tests for the person skill system (XP curve, learning, tracker). */
class SkillSystemTest {

	private static final double EPS = 1e-9;

	@Test
	void xpCurveHitsControlPointsAndClampsOutsideRange() {
		assertEquals(100, SkillRecord.xpRequiredForLevelUp(0), EPS);
		assertEquals(1000, SkillRecord.xpRequiredForLevelUp(9), EPS);
		assertEquals(3000, SkillRecord.xpRequiredForLevelUp(19), EPS);
		// clamp to the endpoints outside the curve's range
		assertEquals(100, SkillRecord.xpRequiredForLevelUp(-3), EPS);
		assertEquals(3000, SkillRecord.xpRequiredForLevelUp(20), EPS);
		assertEquals(3000, SkillRecord.xpRequiredForLevelUp(99), EPS);
	}

	@Test
	void linearInterpIsPiecewiseLinearBetweenPoints() {
		double[][] pts = { { 0, 100 }, { 9, 1000 }, { 19, 3000 } };
		// halfway from level 0 (100) to level 9 (1000) at level 4.5 -> 550
		assertEquals(550, SkillRecord.linearInterp(pts, 4.5), EPS);
		// level 4 -> 100 + 900*4/9 = 500
		assertEquals(500, SkillRecord.linearInterp(pts, 4), EPS);
		// within the second segment: level 14 -> 1000 + 2000*5/10 = 2000
		assertEquals(2000, SkillRecord.linearInterp(pts, 14), EPS);
	}

	@Test
	void constructorClampsLevelToRange() {
		assertEquals(SkillRecord.MAX_LEVEL, new SkillRecord(25, Passion.NONE).getLevel());
		assertEquals(SkillRecord.MIN_LEVEL, new SkillRecord(-5, Passion.NONE).getLevel());
	}

	@Test
	void passionLearnFactors() {
		assertEquals(0.35, Passion.NONE.learnRateFactor(), EPS);
		assertEquals(1.0, Passion.MINOR.learnRateFactor(), EPS);
		assertEquals(1.5, Passion.MAJOR.learnRateFactor(), EPS);
	}

	@Test
	void learnLevelsUpWhenThresholdMet() {
		// MINOR (factor 1.0): exactly the level-0 threshold of 100 advances to 1
		SkillRecord r = new SkillRecord(0, Passion.MINOR);
		r.learn(100);
		assertEquals(1, r.getLevel());
		assertEquals(0, r.getXpSinceLastLevel(), EPS);
	}

	@Test
	void noPassionLearnsSlowlyAndDoesNotLevelEarly() {
		// NONE (factor 0.35): 100 raw xp -> 35 effective, below the 100 threshold
		SkillRecord r = new SkillRecord(0, Passion.NONE);
		r.learn(100);
		assertEquals(0, r.getLevel());
		assertEquals(35, r.getXpSinceLastLevel(), EPS);
	}

	@Test
	void learnCanAdvanceSeveralLevelsAtOnce() {
		// thresholds: level 0 -> 100, level 1 -> 200, level 2 -> 300 (sum 600)
		SkillRecord r = new SkillRecord(0, Passion.MINOR);
		r.learn(600);
		assertEquals(3, r.getLevel());
		assertEquals(0, r.getXpSinceLastLevel(), EPS);
	}

	@Test
	void levelIsCappedAtMax() {
		SkillRecord r = new SkillRecord(SkillRecord.MAX_LEVEL, Passion.MAJOR);
		r.learn(1_000_000);
		assertEquals(SkillRecord.MAX_LEVEL, r.getLevel());
		// leftover xp is capped, not unbounded
		assertTrue(r.getXpSinceLastLevel()
				<= SkillRecord.xpRequiredForLevelUp(SkillRecord.MAX_LEVEL) + EPS);
	}

	@Test
	void trackerHoldsOneRecordPerSkill() {
		SkillTracker t = new SkillTracker();
		assertEquals(Skill.values().length, t.getRecords().size());
		for (Skill s : Skill.values())
			assertNotNull(t.getSkill(s), "missing record for " + s);
		assertEquals(0, t.overallLevel());
	}

	@Test
	void trackerOverallLevelIsRoundedMean() {
		Map<Skill, SkillRecord> records = new EnumMap<>(Skill.class);
		for (Skill s : Skill.values())
			records.put(s, new SkillRecord(10, Passion.NONE));
		assertEquals(10, new SkillTracker(records).overallLevel());
	}

	@Test
	void trackerLearnRoutesToRecord() {
		SkillTracker t = new SkillTracker();
		t.getSkill(Skill.MINING).setPassion(Passion.MINOR);
		t.learn(Skill.MINING, 100);
		assertEquals(1, t.getSkill(Skill.MINING).getLevel());
		// other skills untouched
		assertEquals(0, t.getSkill(Skill.COOKING).getLevel());
	}
}
