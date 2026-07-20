package com.civstudio.race;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.civstudio.geo.Culture;
import com.civstudio.geo.WorldMap;

/**
 * {@link Race#ofCultureGroup(String)} — reading a settlement's race off the world map instead of
 * authoring it a second time. A province has a culture, a culture has a group, and the group key is
 * this enum's {@code id()}: both came from {@code anb_cultures.txt}.
 */
class RaceCultureGroupTest {

	@Test
	void everyRaceIsReachableByItsOwnId() {
		// the lookup is a rename, not a mapping table — so it must be total over the enum
		for (Race race : Race.values())
			assertSame(race, Race.ofCultureGroup(race.id()), race + " must resolve from its own id");
	}

	@Test
	void theAnbennarGroupsThatNameARaceResolveToIt() {
		assertSame(Race.DWARVEN, Race.ofCultureGroup("dwarven"));
		assertSame(Race.ELVEN, Race.ofCultureGroup("elven"));
		assertSame(Race.HARIMARI, Race.ofCultureGroup("harimari"));
		assertSame(Race.AMADIAN_RUINBORN_ELF, Race.ofCultureGroup("amadian_ruinborn_elf"));
	}

	@Test
	void aGroupWithNoRaceYetFallsBackToHuman() {
		// real Anbennar groups with no race authored — the fallback every unauthored per-race
		// resource takes (life table, calendar, tech overlay)
		for (String group : new String[] { "baashidi", "ynnsman", "anakue", "construct", "undead" }) {
			assertSame(Race.HUMAN, Race.ofCultureGroup(group), group + " has no race yet");
			assertFalse(Race.isAuthoredCultureGroup(group),
					group + " must report as unauthored, not as a human people");
		}
	}

	@Test
	void authoredAndFallenBackAreDistinguishable() {
		// ofCultureGroup collapses "no race yet" into HUMAN; this is how a caller tells them apart
		assertTrue(Race.isAuthoredCultureGroup("dwarven"));
		assertFalse(Race.isAuthoredCultureGroup("baashidi"));
		// HUMAN is the one race with no Anbennar culture group — it is the engine's default people
		assertTrue(Race.isAuthoredCultureGroup("human"), "the id itself always resolves");
	}

	@Test
	void unknownBlankAndNullAllReadAsHuman() {
		assertSame(Race.HUMAN, Race.ofCultureGroup(null));
		assertSame(Race.HUMAN, Race.ofCultureGroup(""));
		assertSame(Race.HUMAN, Race.ofCultureGroup("   "));
		assertSame(Race.HUMAN, Race.ofCultureGroup("no_such_group"));
	}

	@Test
	void mostShippedCultureGroupsStillNameARace() {
		// Against the REAL world, not hand-picked keys: the ids came from anb_cultures.txt, so if
		// Anbennar renames its groups this lookup rots silently into "everyone is human" — which
		// would look like working code and produce a world of humans. Measured 57/70 on the shipped
		// map; the floor is deliberately loose enough to survive a couple of new groups being added
		// and tight enough that a rename cannot pass.
		Set<String> groups = WorldMap.load().cultures().stream()
				.map(Culture::group)
				.filter(g -> g != null && !g.isBlank())
				.collect(Collectors.toSet());
		assertTrue(groups.size() > 50, "expected the shipped culture groups, got " + groups.size());

		List<String> unauthored = groups.stream().filter(g -> !Race.isAuthoredCultureGroup(g)).sorted()
				.toList();
		long authored = groups.size() - unauthored.size();
		assertTrue(authored >= groups.size() * 0.7,
				"only " + authored + "/" + groups.size() + " culture groups name a race — the enum's"
						+ " ids have drifted from anb_cultures.txt. Unauthored: " + unauthored);
	}

	@Test
	void theKeyIsCaseAndWhitespaceTolerant() {
		// culture keys arrive from imported data, not from a Java constant
		assertEquals(Race.DWARVEN, Race.ofCultureGroup("  DWARVEN  "));
	}
}
