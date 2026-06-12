package eos.simulation;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import eos.agent.Agent;
import eos.agent.firm.StrategicFirm;
import eos.agent.noble.Noble;
import eos.bank.Bank;

/**
 * Smoke test for the colony with a strategic export sector worked by the nobles.
 * The shared {@code assertHealthy} does not apply: the export firm pumps external
 * money into the bank's equity, so the bank is deliberately <i>not</i> a
 * zero-equity intermediary. The invariants are checked directly: the colony stays
 * healthy (population sustained, prices finite/positive), the export firm
 * actually exported, the bank's equity grew on the back of those exports, and the
 * nobles drew wages from the strategic sector.
 */
class StrategicEconomyTest {

	@Test
	void runsHealthyAndAccumulatesEquityFromExports() {
		SimulationHarness h = assertDoesNotThrow(StrategicEconomy::run);

		// core health: population sustained (>400), consumer prices finite/positive,
		// bank deposit/rates finite
		SimulationAssertions.assertCoreHealthy(h, 401);

		// exactly one bank, and it accumulated equity from the export earnings
		// (the closed default runs sit at ~zero equity)
		assertEquals(1, h.getBanks().size(), "StrategicEconomy uses one bank");
		Bank bank = h.getBanks().get(0);
		assertTrue(bank.getEquity() > 0,
				"expected the bank to accumulate equity from exports, got "
						+ bank.getEquity());

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
		for (Agent agent : h.getColony().getAgents())
			if (agent instanceof Noble noble) {
				nobleCount++;
				totalWage += noble.getWage();
			}
		assertEquals(StrategicEconomy.NUM_NOBLES, nobleCount,
				"expected the noble workforce sustained by succession");
		assertTrue(totalWage > 0,
				"expected nobles to draw wages from the export sector, got "
						+ totalWage);

		// the colony tracks every living noble as a person of interest
		var poi = h.getColony().getPersonsOfInterest();
		for (Agent agent : h.getColony().getAgents())
			if (agent instanceof Noble noble)
				assertTrue(poi.contains(noble),
						"expected every living noble to be a person of interest");
	}
}
