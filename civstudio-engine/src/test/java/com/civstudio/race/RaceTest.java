package com.civstudio.race;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.civstudio.mortality.Demography;
import com.civstudio.name.NameRegistry;
import com.civstudio.name.Person;
import com.civstudio.settlement.GameSession;
import com.civstudio.settlement.Settlement;
import com.civstudio.util.Rng;

/**
 * Phase 2 of the race feature ("make it vary"): a colony given a non-degenerate
 * race-mix actually rolls each generated person's ancestry (reproducibly, on the
 * demographic RNG) and names people from their own race's tables, while a
 * mono-cultural colony rolls nothing. Verifies the race-mix roll, the per-race
 * name draw (a Harimari dynasty drawn from the Harimari surname pool), and the
 * founding-race calendar/tech-overlay selection seam. See {@code docs/race.md}.
 */
class RaceTest {

	private static final LocalDate START = LocalDate.of(1444, 12, 11);

	private Settlement mixedColony(GameSession s) {
		Map<Race, Double> mix = new EnumMap<>(Race.class);
		mix.put(Race.HUMAN, 1.0);
		mix.put(Race.HARIMARI, 1.0);
		return s.newSettlement("Mixed", START, 35, 26, 5, 2, 51.5074, -0.1278,
				Race.HUMAN, mix);
	}

	@Test
	void sampleRaceIsReproducibleAndCoversTheMix() {
		Map<Race, Double> mix = new EnumMap<>(Race.class);
		mix.put(Race.HUMAN, 1.0);
		mix.put(Race.HARIMARI, 1.0);

		Demography a = new Demography(new Rng(7), new Rng(11));
		Demography b = new Demography(new Rng(7), new Rng(11));
		Set<Race> seen = new HashSet<>();
		for (int i = 0; i < 1000; i++) {
			Race ra = a.sampleRace(mix);
			assertEquals(ra, b.sampleRace(mix), "same seed must roll the same race");
			seen.add(ra);
		}
		assertTrue(seen.contains(Race.HUMAN) && seen.contains(Race.HARIMARI),
				"a 50/50 mix should produce both races over 1000 draws: " + seen);
	}

	@Test
	void degenerateMixDrawsNoRandomness() {
		// a single-race mix must consume no RNG, so a human-only colony stays
		// byte-identical: a skill draw after many degenerate race rolls matches one
		// taken with no race rolls at all
		Demography rolled = new Demography(new Rng(3), new Rng(5));
		Demography plain = new Demography(new Rng(3), new Rng(5));
		Map<Race, Double> human = Map.of(Race.HUMAN, 1.0);
		for (int i = 0; i < 50; i++)
			assertEquals(Race.HUMAN, rolled.sampleRace(human));
		assertEquals(plain.sampleSkill(5), rolled.sampleSkill(5),
				"degenerate race rolls must not have advanced the skill RNG");
	}

	@Test
	void headsAreNamedFromTheirOwnRaceTables() {
		Settlement colony = mixedColony(new GameSession(42));
		NameRegistry names = colony.getNames();

		// every drawn head carries the race it was drawn for, with a non-blank name
		Person human = names.nextHead(Race.HUMAN);
		assertEquals(Race.HUMAN, human.race());
		assertFalse(human.surname().isBlank(), "human head needs a surname");

		Person harimari = names.nextHead(Race.HARIMARI);
		assertEquals(Race.HARIMARI, harimari.race());
		assertFalse(harimari.surname().isBlank(), "harimari head needs a surname");

		// the Harimari surname pool is the Anbennar clan epithets ("of the White Stripe", ...), which
		// are unique to it: over a batch of Harimari surnames an epithet appears, and no human surname
		// is ever one — proving each race is named from its own pool. The batch stays well under the
		// generated Harimari pool size (drawn without replacement, so it must not exhaust it).
		boolean sawClanEpithet = false;
		for (int i = 0; i < 60; i++)
			if (names.nextDynastyName(Race.HARIMARI).startsWith("of the "))
				sawClanEpithet = true;
		assertTrue(sawClanEpithet,
				"a batch of Harimari dynasties should include a clan epithet");
		for (int i = 0; i < 120; i++)
			assertFalse(names.nextDynastyName(Race.HUMAN).startsWith("of the "),
					"a human dynasty should never be a Harimari clan epithet");
	}

	@Test
	void foundingRaceSelectsCalendarAndTechOverlay() {
		GameSession s = new GameSession(1);
		// the seam exists for every race; non-human races fall back to the shared
		// human calendar / tech graph until per-race files are authored (Phase 3)
		assertNotNull(s.getLiturgicalCalendar(Race.HARIMARI));
		assertNotNull(s.getTechTree(Race.HARIMARI));
	}

	@Test
	void ageDemographicsAreRaceSpecific() {
		Demography demo = new Demography(new Rng(7), new Rng(11));
		// founding-age floor: Harimari (9) mature faster than humans (15); at a low
		// mean the Harimari draw dips below the human floor, the human draw never does
		boolean harimariBelowHumanFloor = false;
		for (int i = 0; i < 2000; i++) {
			int human = demo.sampleInitialAgeDays(12, Race.HUMAN) / 365;
			assertTrue(human >= 15, "human founding age must respect its floor: " + human);
			int harimari = demo.sampleInitialAgeDays(12, Race.HARIMARI) / 365;
			assertTrue(harimari >= 9, "harimari founding age must respect its floor: " + harimari);
			if (harimari < 15)
				harimariBelowHumanFloor = true;
		}
		assertTrue(harimariBelowHumanFloor,
				"a faster-maturing Harimari should sometimes start below the human floor");

		// young-adult immigrant range: humans 16–25, Harimari 9–16
		for (int i = 0; i < 2000; i++) {
			int human = demo.sampleYoungAdultAgeDays(Race.HUMAN) / 365;
			assertTrue(human >= 16 && human <= 25, "human young adult out of range: " + human);
			int harimari = demo.sampleYoungAdultAgeDays(Race.HARIMARI) / 365;
			assertTrue(harimari >= 9 && harimari <= 16,
					"harimari young adult out of range: " + harimari);
		}
	}

	@Test
	void everyRaceHasAMortalitySchedule() {
		// the per-person old-age check reads the dying head's race's table; both races
		// currently share WEST_LEVEL_3 (the Harimari get their own schedule later)
		Demography demo = new Demography(new Rng(1), new Rng(2));
		for (Race race : Race.values()) {
			assertNotNull(race.lifeTable(), race + " needs a life table");
			// exercises the race-keyed mortality path without asserting a death
			demo.diesOfOldAge(40 * 365, race);
		}
	}
}
