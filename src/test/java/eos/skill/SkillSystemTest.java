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
	void passionSymbolGlyphs() {
		assertEquals("", Passion.NONE.symbol());
		assertEquals("~", Passion.MINOR.symbol());
		assertEquals("!", Passion.MAJOR.symbol());
	}

	@Test
	void recordToStringShowsLevelAndPassionGlyph() {
		assertEquals("14!", new SkillRecord(14, Passion.MAJOR).toString());
		assertEquals("8~", new SkillRecord(8, Passion.MINOR).toString());
		assertEquals("3", new SkillRecord(3, Passion.NONE).toString());
	}

	@Test
	void peakLevelIsTheHighestSingleSkill() {
		Map<Skill, SkillRecord> recs = new EnumMap<>(Skill.class);
		recs.put(Skill.SOCIAL, new SkillRecord(18, Passion.NONE));
		recs.put(Skill.PLANTS, new SkillRecord(7, Passion.NONE));
		SkillTracker t = SkillTracker.of(recs);
		// peak is the single best skill (18), well above the all-round mean
		assertEquals(18, t.peakLevel());
		assertTrue(t.peakLevel() > t.overallLevel());
		// an all-default tracker peaks at the minimum level
		assertEquals(SkillRecord.MIN_LEVEL, SkillTracker.empty().peakLevel());
	}

	@Test
	void skillIndicesAreConsecutiveZeroToEleven() {
		Skill[] all = Skill.values();
		assertEquals(12, all.length);
		for (int i = 0; i < all.length; i++)
			assertEquals(i, all[i].index(), all[i] + " index");
	}

	@Test
	void peakSkillIsTheHighestSkill() {
		Map<Skill, SkillRecord> recs = new EnumMap<>(Skill.class);
		recs.put(Skill.MEDICINE, new SkillRecord(17, Passion.NONE));
		recs.put(Skill.SOCIAL, new SkillRecord(9, Passion.NONE));
		SkillTracker t = SkillTracker.of(recs);
		assertEquals(Skill.MEDICINE, t.peakSkill());
		// peakSkill identifies WHICH skill; peakLevel gives how good it is
		assertEquals(17, t.peakLevel());
	}

	@Test
	void peakSkillBreaksTiesTowardTheLowestIndex() {
		Map<Skill, SkillRecord> recs = new EnumMap<>(Skill.class);
		recs.put(Skill.INTELLECTUAL, new SkillRecord(12, Passion.NONE)); // index 2
		recs.put(Skill.CRAFTING, new SkillRecord(12, Passion.NONE)); // index 11
		assertEquals(Skill.INTELLECTUAL, SkillTracker.of(recs).peakSkill());
	}

	@Test
	void trackerToStringListsOverallAndAllTwelveSkills() {
		SkillTracker t = SkillTracker.empty();
		String s = t.toString();
		// the overall scalar leads, then every one of the twelve skills appears
		assertTrue(s.startsWith("overall=" + t.overallLevel()), s);
		for (Skill skill : Skill.values())
			assertTrue(s.contains(skill + "="), "missing " + skill + " in " + s);
		// a known record renders with its glyph inside the dump
		Map<Skill, SkillRecord> recs = new EnumMap<>(Skill.class);
		recs.put(Skill.PLANTS, new SkillRecord(14, Passion.MAJOR));
		assertTrue(SkillTracker.of(recs).toString().contains("PLANTS=14!"));
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
	void decayErodesMasteryButNotBasicCompetence() {
		// a skill at or below the floor (10) never decays
		SkillRecord basic = new SkillRecord(10, Passion.NONE);
		for (int day = 0; day < 1000; day++)
			basic.decay();
		assertEquals(10, basic.getLevel(), "skills at the floor do not fade");

		// a high skill with no passion erodes over time and drops below its
		// starting level (but never below the floor)
		SkillRecord mastery = new SkillRecord(18, Passion.NONE);
		for (int day = 0; day < 5000; day++)
			mastery.decay();
		assertTrue(mastery.getLevel() < 18, "unpracticed mastery should fade");
		assertTrue(mastery.getLevel() >= 10, "decay never drops below the floor");
	}

	@Test
	void majorPassionNeverForgets() {
		SkillRecord r = new SkillRecord(20, Passion.MAJOR);
		for (int day = 0; day < 5000; day++)
			r.decay();
		assertEquals(20, r.getLevel(), "a major-passion skill does not decay");
	}

	@Test
	void trackerTickDecaysRecords() {
		SkillTracker t = SkillTracker.empty();
		// push one skill well above the floor, then let it sit idle through ticks
		t.getSkill(Skill.MINING).setPassion(Passion.NONE);
		t.learn(Skill.MINING, 100_000); // drive to max
		int before = t.getSkill(Skill.MINING).getLevel();
		// enough idle days to erode the full top-level XP bucket and drop a level
		for (int day = 0; day < 10_000; day++)
			t.tick();
		assertTrue(t.getSkill(Skill.MINING).getLevel() < before,
				"tick() should decay an idle high skill");
	}

	@Test
	void trackerHoldsOneRecordPerSkill() {
		SkillTracker t = SkillTracker.empty();
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
		assertEquals(10, SkillTracker.of(records).overallLevel());
	}

	@Test
	void trackerLearnRoutesToRecord() {
		SkillTracker t = SkillTracker.empty();
		t.getSkill(Skill.MINING).setPassion(Passion.MINOR);
		t.learn(Skill.MINING, 100);
		assertEquals(1, t.getSkill(Skill.MINING).getLevel());
		// other skills untouched
		assertEquals(0, t.getSkill(Skill.COOKING).getLevel());
	}
}
