package com.civstudio.mission.export;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.civstudio.mission.Mission;
import com.civstudio.mission.MissionSeries;

/**
 * Pins the mission parser against a fixture shaped like a real Anbennar mission file (Arakeprun) — the
 * exporter's {@link MissionExporter#parseContent} is driven directly, so no network/mod files are
 * touched. See {@code docs/campaign-selector.md}.
 */
class MissionExporterTest {

	// a two-node series with a tag potential, plus a generic (always = yes) series and a comment
	private static final String FIXTURE = """
			# a header comment that must be stripped
			arakeprun1_missions = {
				slot = 1
				generic = no
				ai = yes
				has_country_shield = yes
				potential = { tag = H01 }

				arakeprun_ruins_of_greatness = {
					icon = mission_city_of_victory_vij
					required_missions = {  }
					position = 1
					provinces_to_highlight = {
						province_id = 2007
						NOT = { has_tax_building_trigger = yes }
					}
					trigger = { 2007 = { has_tax_building_trigger = yes } }
					effect = { increase_legitimacy_medium_effect = yes add_prestige = 10 }
				}

				arakeprun_splendor = {
					icon = mission_dharma
					required_missions = { arakeprun_ruins_of_greatness }
					position = 3
					trigger = { 2007 = { development = 24 } }
					effect = { add_country_modifier = { name = "x" duration = 5475 } }
				}
			}

			generic_expansion_missions = {
				slot = 2
				generic = yes
				potential = { always = yes }
				expand_a_bit = { icon = mission_generic position = 1 }
			}
			""";

	@Test
	void parsesSeriesTagSlotFlagsAndMissions() {
		Map<String, String> titles = Map.of("arakeprun_ruins_of_greatness", "Ruins of Greatness");
		Map<String, String> descs = Map.of("arakeprun_ruins_of_greatness", "A mighty ruined city.");
		List<MissionSeries> series = MissionExporter.parseContent(FIXTURE, "Arakeprun_Missions.txt",
				titles, descs);

		assertEquals(2, series.size(), "one tag-gated series + one generic series");
		MissionSeries s = series.get(0);
		assertEquals("arakeprun1_missions", s.id());
		assertEquals("H01", s.tag(), "the tag is read from potential { tag = … }");
		assertEquals(1, s.slot());
		assertFalse(s.generic());
		assertTrue(s.ai());
		assertTrue(s.hasCountryShield());
		assertEquals("Arakeprun_Missions.txt", s.file());
		assertEquals(2, s.missions().size());

		Mission m0 = s.missions().get(0);
		assertEquals("arakeprun_ruins_of_greatness", m0.key());
		assertEquals("Ruins of Greatness", m0.title(), "joined from the loc map");
		assertEquals("A mighty ruined city.", m0.description());
		assertEquals("mission_city_of_victory_vij", m0.icon());
		assertEquals(1, m0.position());
		assertTrue(m0.requiredMissions().isEmpty(), "a root mission has no prerequisites");
		assertEquals(List.of(2007), m0.highlightProvinces(), "province ids are pulled from the highlight block");
		assertTrue(m0.trigger().contains("has_tax_building_trigger"));
		assertTrue(m0.effect().contains("add_prestige = 10"));

		Mission m1 = s.missions().get(1);
		assertEquals(List.of("arakeprun_ruins_of_greatness"), m1.requiredMissions(), "prerequisite edge");
		assertEquals(3, m1.position());
		assertNull(m1.title(), "no loc for this key → null, not empty");
	}

	@Test
	void aGenericSeriesHasNoTag() {
		MissionSeries generic = MissionExporter.parseContent(FIXTURE, "f", Map.of(), Map.of()).get(1);
		assertEquals("generic_expansion_missions", generic.id());
		assertTrue(generic.generic());
		assertNull(generic.tag(), "always = yes potential resolves to no single tag");
		assertEquals(1, generic.missions().size());
	}
}
