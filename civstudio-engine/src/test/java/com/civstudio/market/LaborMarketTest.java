package com.civstudio.market;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
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

	// --- village affinity (city-of-hamlets V3): a village farm's slice of the shared workforce is
	// filled with its own village's residents before outsiders ---

	// workers named for the village they live in; identity comparison, so distinct Strings are needed
	private static List<String> workers(String... villages) {
		List<String> out = new ArrayList<>();
		for (String v : villages)
			out.add(v);
		return out;
	}

	@Test
	void aVillageFirmPullsItsOwnResidentsIntoItsSlice() {
		String a = "A", b = "B";
		// two firms, two workers each: the A-farm is served first but its slice holds B's people
		List<String> pool = workers(b, b, a, a);
		LaborMarket.placeVillagers(pool, w -> w, new Object[] { a, b }, new int[] { 2, 4 });
		assertEquals(List.of(a, a, b, b), pool,
				"the village farm's slice fills with its own villagers, and B's fall to B's farm");
	}

	@Test
	void aShortVillageSpillsOverToOutsiders() {
		String a = "A", b = "B";
		// only one A villager exists, so the A-farm's second seat goes to whoever is left
		List<String> pool = workers(b, b, a, b);
		LaborMarket.placeVillagers(pool, w -> w, new Object[] { a, b }, new int[] { 2, 4 });
		assertEquals(a, pool.get(0), "the one villager is placed in its own village's farm");
		assertEquals(b, pool.get(1), "the rest of the slice spills over to other villages' peasants");
		assertEquals(4, pool.size(), "no one is added or dropped — affinity only reorders");
	}

	@Test
	void aVillageFirmTakesItsPeopleBackFromACityFirm() {
		String a = "A", b = "B";
		// employer 0 is a city firm (no village) holding A's people; employer 1 is A's own farm — it
		// takes them back, and the city firm works with the outsiders instead. Served last and still
		// filled: affinity is not a race to be first in the shuffle.
		List<String> pool = workers(a, a, b, b);
		LaborMarket.placeVillagers(pool, w -> w, new Object[] { null, a }, new int[] { 2, 4 });
		assertEquals(List.of(b, b, a, a), pool,
				"the village's own people move to their village's farm; the city firm keeps a full slice");
	}

	@Test
	void oneFarmNeverRobsAnotherOfItsOwnVillagers() {
		String a = "A", b = "B";
		// two farms serve village B; the second is short, and the only other B in the colony is
		// already working the first one's fields — off limits, so the second fills with outsiders
		// rather than gutting its neighbour
		List<String> pool = workers(b, a, a, a);
		LaborMarket.placeVillagers(pool, w -> w, new Object[] { b, b, a }, new int[] { 1, 3, 4 });
		assertEquals(List.of(b, a, a, a), pool,
				"the first B farm keeps its villager; the second works with outsiders");
	}
}
