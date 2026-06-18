package eos.simulation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import eos.agent.Agent;
import eos.agent.Household;
import eos.calendar.LiturgicalCalendar;
import eos.race.Race;
import eos.settlement.Settlement;

/**
 * Phase 3 of the race feature: a {@link Race#HARIMARI Harimari}-founded, mixed-race
 * colony <b>founds, names, ages, and researches</b> correctly (see {@code
 * docs/race.md}). Built on the standard {@code foundStandardColony} path over a short
 * pre-collapse horizon, with a modestly-sized pool so the finite Harimari surname pool
 * is not exhausted. The colony keys its calendar and tech-effect overlay off its
 * founding race; its people are drawn from their own race's name tables.
 */
class MixedRaceColonyTest {

	@Test
	void harimariColonyFoundsNamesAgesAndResearches() {
		SimulationConfig cfg = SimulationConfig.DEFAULT.toBuilder()
				.settlementName("Sehir").retinueSize(200).promotionRatio(0.45)
				.durationYears(1).build();
		Map<Race, Double> mix = new EnumMap<>(Race.class);
		mix.put(Race.HARIMARI, 0.7);
		mix.put(Race.HUMAN, 0.3);

		SimulationHarness h =
				SimulationHarness.create(cfg, 7654321, Race.HARIMARI, mix);
		h.foundStandardColony(i -> cfg.eFirm().savings(),
				i -> cfg.nFirm().savings(), i -> 15);
		h.run();

		Settlement colony = h.getColony();

		// FOUNDS + NAMES: living households of both ancestries — the Harimari ruler and
		// aristocracy, and a labor force promoted from the mixed pool — each named from
		// its own race's tables (so its head carries its race)
		Set<Race> races = new HashSet<>();
		for (Agent a : colony.getAgents())
			if (a instanceof Household hh)
				races.add(hh.getHead().race());
		assertTrue(races.contains(Race.HARIMARI),
				"a Harimari-founded colony must hold Harimari households");
		assertTrue(races.contains(Race.HUMAN),
				"the mixed pool should put human households in the colony too: " + races);

		// AGES: the colony ran a full year, and every living head respects its race's
		// working-age floor through that aging (Harimari mature younger, at 9)
		assertTrue(colony.getTimeStep() >= 360, "the colony should have aged ~a year");
		for (Agent a : colony.getAgents())
			if (a instanceof Household hh) {
				Race r = hh.getHead().race();
				assertTrue(hh.getAgeYears() >= r.minInitAgeYears(),
						r + " head below its working-age floor: " + hh.getAgeYears());
			}

		// CALENDAR: the colony keeps the Harimari liturgical calendar, not the human one
		LiturgicalCalendar cal = colony.getLiturgicalCalendar();
		assertTrue(cal.isFeast(LocalDate.of(2000, 2, 20)),
				"Feb 20 (Festival of Stripes) is a Harimari feast");
		assertFalse(cal.isFeast(LocalDate.of(2000, 12, 25)),
				"Dec 25 (Christmas) is a human feast, not Harimari");

		// RESEARCHES: research is wired on the founding race's tech tree — it knows its
		// pre-known set and climbs from there — and that tree carries the Harimari
		// effect overlay the human tree does not
		assertNotNull(colony.getResearch(), "an export colony researches the tech tree");
		assertTrue(colony.getResearch().getKnownCount() > 0,
				"the colony should know its pre-known tech set");
		assertFalse(colony.getSession().getTechTree(Race.HARIMARI)
				.effectsOf("TECH_HUMANISM").isEmpty(),
				"the Harimari tech overlay should grant TECH_HUMANISM an effect");
		assertTrue(colony.getSession().getTechTree(Race.HUMAN)
				.effectsOf("TECH_HUMANISM").isEmpty(),
				"the human tech overlay grants no such effect (the default is empty)");
	}
}
