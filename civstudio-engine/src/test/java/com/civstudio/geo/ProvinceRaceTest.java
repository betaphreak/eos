package com.civstudio.geo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.civstudio.race.Race;

/**
 * {@link WorldMap#raceOf(Province)} against the shipped world — a settlement learns who lives in it
 * from the map rather than being told (see {@code docs/studio-control-plane-plan.md} §A1).
 */
class ProvinceRaceTest {

	@Test
	void aDwarvenProvinceIsDwarvenAndAnElvenOneElven() {
		WorldMap map = WorldMap.load();
		assertSame(Race.DWARVEN, map.raceOf(map.findByName("Rubyhold").orElseThrow()),
				"Rubyhold's culture (ruby_dwarf) is in the dwarven group");
		assertSame(Race.ELVEN, map.raceOf(map.findByName("Dancers Retreat").orElseThrow()),
				"Dancers Retreat's culture (moon_elf) is in the elven group");
	}

	@Test
	void dhenijansarReadsHumanSoEveryCurrentScenarioIsUnaffected() {
		// The default founding site. Its group (south_raheni) has no race authored, so it falls back
		// to HUMAN — which is what the province-founding overload hardcoded before it resolved race
		// from the map. This is the assertion that makes that change behaviour-neutral.
		WorldMap map = WorldMap.load();
		Province dh = map.province(4411);
		assertEquals("rabhidarubsad", dh.culture());
		assertSame(Race.HUMAN, map.raceOf(dh));
	}

	@Test
	void anAbsentOrUnknownProvinceIsHumanRatherThanAnError() {
		WorldMap map = WorldMap.load();
		assertSame(Race.HUMAN, map.raceOf(null));
	}

	@Test
	void theWorldIsNotAccidentallyAllHuman() {
		// Guards the failure mode that would look like working code: if the culture-group keys ever
		// stop matching race ids, every province quietly reads HUMAN and the world loses its peoples.
		WorldMap map = WorldMap.load();
		long nonHuman = map.provinces().stream().filter(p -> map.raceOf(p) != Race.HUMAN).count();
		assertTrue(nonHuman > 100,
				"expected many non-human provinces across Anbennar, got " + nonHuman);
	}
}
