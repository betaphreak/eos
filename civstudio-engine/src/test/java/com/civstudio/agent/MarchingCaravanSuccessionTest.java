package com.civstudio.agent;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.civstudio.name.Gender;
import com.civstudio.name.Person;
import com.civstudio.race.Race;
import com.civstudio.skill.Passion;
import com.civstudio.skill.Skill;
import com.civstudio.skill.SkillRecord;
import com.civstudio.skill.SkillTracker;

/**
 * The <b>leader-succession</b> pick: when a wandering band's leader dies, the ablest surviving
 * member takes command — the member with the highest {@code SURVIVAL} skill, ties broken toward the
 * earliest in following order (a stable, RNG-free "first maximum"). This covers that selection
 * ({@link MarchingCaravan#ablestSurvivor}) in isolation; the death roll + hand-over wiring rides the
 * full march in the scenario smoke tests. See {@code docs/caravan.md}.
 */
class MarchingCaravanSuccessionTest {

	// a living band member with a given SURVIVAL level (other skills default/empty)
	private static Member survivor(String name, int survival) {
		Map<Skill, SkillRecord> recs = new EnumMap<>(Skill.class);
		recs.put(Skill.SURVIVAL, new SkillRecord(survival, Passion.NONE));
		Person p = new Person(name, "Bandsman", Gender.MALE, SkillTracker.of(recs), Race.HUMAN);
		return new Member(p, LocalDate.of(1420, 1, 1));
	}

	@Test
	void promotesTheHighestSurvivalMember() {
		Member a = survivor("Alpha", 3), b = survivor("Bravo", 7), c = survivor("Cael", 5);
		assertSame(b, MarchingCaravan.ablestSurvivor(List.of(a, b, c)),
				"the ablest survivor is the highest SURVIVAL skill");
	}

	@Test
	void breaksTiesTowardTheEarliestInFollowingOrder() {
		Member a = survivor("Alpha", 7), b = survivor("Bravo", 7);
		// deterministic (no RNG): the first member holding the max wins, so the pick depends only on
		// the stable following order — the same guarantee a seed-reproducible replay needs.
		assertSame(a, MarchingCaravan.ablestSurvivor(List.of(a, b)));
		assertSame(b, MarchingCaravan.ablestSurvivor(List.of(b, a)));
	}

	@Test
	void anEmptyFollowingHasNoHeir() {
		assertNull(MarchingCaravan.ablestSurvivor(List.of()),
				"a band with no following has nobody to promote");
	}
}
