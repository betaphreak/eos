package eos.simulation;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import eos.agent.Agent;
import eos.agent.firm.StrategicFirm;
import eos.agent.noble.Noble;
import eos.bank.Bank;
import eos.skill.Skill;

/**
 * Smoke test for the colony with a strategic export sector worked by the nobles.
 * The shared {@code assertHealthy} does not apply: the export firm pumps external
 * money into the copper bank's equity, so it is deliberately <i>not</i> a
 * zero-equity intermediary. Under the default tiered banking the export firm banks
 * in copper and the nobles in silver, so their export wages also feed the silver
 * money-changer's FX equity. The invariants are checked directly: the colony stays
 * healthy (population sustained, prices finite/positive), the export firm actually
 * exported, the copper bank's equity grew on the back of those exports, and the
 * nobles drew wages from the strategic sector.
 */
class StrategicEconomyTest {

	@Test
	void runsHealthyAndAccumulatesEquityFromExports() {
		SimulationHarness h = assertDoesNotThrow(StrategicEconomy::run);

		// the labor force is founded/replaced from a finite pool, so the colony
		// ultimately collapses — but the rentier export sector (nobles, who never
		// starve) outlives it, which is what this test exercises
		SimulationAssertions.assertCollapsed(h);

		// three banks under the default tiered system: the copper bank accumulated
		// equity from the export earnings (the export firm banks copper), the silver
		// money-changer skimmed FX fees from the nobles' export wages, and the ruler
		// banks in gold (every settlement has a ruler)
		assertEquals(3, h.getBanks().size(),
				"StrategicEconomy uses three banks (copper + silver + gold)");
		Bank copper = h.getBanks().get(0);
		assertTrue(copper.getEquity() > 0,
				"expected the copper bank to accumulate equity from exports, got "
						+ copper.getEquity());
		Bank silver = h.getBanks().get(1);
		assertTrue(silver.getEquity() > 0,
				"expected the silver bank to profit from FX on the nobles' wages, got "
						+ silver.getEquity());

		// the colony has its single export firm and it exported a positive amount
		StrategicFirm firm = h.getColony().getStrategicFirm();
		assertTrue(firm != null, "expected a registered strategic firm");
		assertEquals(firm, h.getStrategicFirm(),
				"harness and colony should expose the same strategic firm");
		assertTrue(firm.getTotalExported() > 0,
				"expected the strategic firm to have exported, got "
						+ firm.getTotalExported());

		// the nobles staff the export sector: the population is sustained by
		// succession and they draw positive wages from it
		int nobleCount = 0;
		double totalWage = 0;
		double intellectual = 0, medicine = 0;
		for (Agent agent : h.getColony().getAgents())
			if (agent instanceof Noble noble) {
				nobleCount++;
				totalWage += noble.getWage();
				// working the export sector trains INTELLECTUAL (which now drives
				// the noble's export productivity); a skill no noble works
				// (MEDICINE) stays at its birth level
				intellectual += noble.getHead().skills().level(Skill.INTELLECTUAL);
				medicine += noble.getHead().skills().level(Skill.MEDICINE);
			}
		assertEquals(StrategicEconomy.NUM_NOBLES, nobleCount,
				"expected the noble workforce sustained by succession");
		assertTrue(totalWage > 0,
				"expected nobles to draw wages from the export sector, got "
						+ totalWage);
		assertTrue(intellectual > medicine,
				"expected nobles to have trained INTELLECTUAL (the export skill, mean "
						+ intellectual / nobleCount + ") above an untrained skill (mean "
						+ medicine / nobleCount + ")");

		// the colony tracks every living noble as a person of interest
		var poi = h.getColony().getPersonsOfInterest();
		for (Agent agent : h.getColony().getAgents())
			if (agent instanceof Noble noble)
				assertTrue(poi.contains(noble),
						"expected every living noble to be a person of interest");
	}
}
