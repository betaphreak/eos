package com.civstudio.market;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.civstudio.skill.Passion;
import com.civstudio.skill.Skill;
import com.civstudio.skill.SkillRecord;
import com.civstudio.skill.SkillTracker;

/**
 * Unit tests for the labor market's skill→labor rule ({@link
 * LaborMarket#relevantLevel}): the worker's effective level for a firm is its
 * proficiency in that firm's own work, so a necessity firm (SURVIVAL) reads the
 * worker's SURVIVAL level, a multi-skill firm the mean of its skills, and a
 * skill-less firm the worker's overall level.
 */
class LaborMarketTest {

	private static SkillTracker trackerWith(Map<Skill, Integer> levels) {
		Map<Skill, SkillRecord> records = new EnumMap<>(Skill.class);
		for (Skill s : Skill.values())
			records.put(s, new SkillRecord(levels.getOrDefault(s, 5), Passion.NONE));
		return SkillTracker.of(records);
	}

	@Test
	void readsTheFirmsSingleSkill() {
		Map<Skill, Integer> levels = new EnumMap<>(Skill.class);
		levels.put(Skill.SURVIVAL, 14);
		SkillTracker t = trackerWith(levels);
		// a necessity firm (subsistence agriculture) reads the worker's SURVIVAL level
		assertEquals(14, LaborMarket.relevantLevel(t, Set.of(Skill.SURVIVAL)));
	}

	@Test
	void averagesAMultiSkillFirm() {
		Map<Skill, Integer> levels = new EnumMap<>(Skill.class);
		levels.put(Skill.COMMERCE, 12);
		levels.put(Skill.PRODUCTION, 8);
		levels.put(Skill.SOCIAL, 10);
		SkillTracker t = trackerWith(levels);
		// a multi-skill firm reads the rounded mean of its three skills: (12+8+10)/3
		assertEquals(10, LaborMarket.relevantLevel(t,
				Set.of(Skill.COMMERCE, Skill.PRODUCTION, Skill.SOCIAL)));
	}

	@Test
	void fallsBackToOverallWhenFirmTrainsNoSkill() {
		SkillTracker t = trackerWith(new EnumMap<>(Skill.class)); // every skill at 5
		assertEquals(t.overallLevel(), LaborMarket.relevantLevel(t, Set.of()));
		assertEquals(t.overallLevel(), LaborMarket.relevantLevel(t, null));
	}
}
