package eos.simulation;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import eos.agent.Agent;
import eos.agent.noble.Noble;
import eos.bank.Bank;

/**
 * Smoke test for the colony with an aristocracy that owns the firms <i>and</i> a
 * bank. Under the default tiered banking the commoners bank in copper (the
 * zero-profit base currency) and the nobles in silver; the senior noble owns the
 * silver money-changer, which profits from the FX fee on the nobles' copper-quoted
 * dividends and purchases and pays that out to its owner. The shared {@code
 * assertHealthy} — which assumes only zero-profit banks — does not apply; the
 * invariants are checked directly. Beyond a sustained, finite-price colony it
 * verifies that the firms <em>and</em> the silver bank actually paid dividends to
 * the nobles, that the nobles grew their fortunes, and that a noble succession
 * occurred (heir inherited the holdings).
 */
class AristocraticEconomyTest {

	@Test
	void runsHealthyAndPaysFirmAndBankDividends() {
		SimulationHarness h = assertDoesNotThrow(AristocraticEconomy::run);

		// core health: population sustained (>400), consumer prices
		// finite/positive, bank deposit/rates finite (shared with the closed runs)
		SimulationAssertions.assertCoreHealthy(h, 401);

		// two banks under the default tiered system: a zero-profit copper base bank
		// and the noble-owned silver money-changer, which turns an FX profit and has
		// paid part of it out as dividends
		assertEquals(2, h.getBanks().size(),
				"AristocraticEconomy uses two banks (copper + silver)");
		Bank copper = h.getBanks().get(0);
		Bank silver = h.getBanks().get(1);
		assertEquals(0.0, copper.getEquity(), 1e-3,
				"the copper bank is the zero-profit base-currency intermediary");
		assertTrue(silver.getEquity() > 0,
				"expected the noble-owned silver money-changer to retain FX profit");
		assertTrue(silver.getDistributedProfit() > 0,
				"expected the silver bank to have paid dividends to its noble owner");

		// agent IDs are assigned contiguously at construction, so the founding
		// agents occupy 1..foundingAgents; any noble with a higher ID is a
		// successor that replaced a founder who died of old age
		SimulationConfig cfg = h.getCfg();
		int foundingAgents = cfg.numEFirms() + cfg.numNFirms() + 1
				+ cfg.numLaborers() + AristocraticEconomy.NUM_NOBLES;

		int nobleCount = 0;
		double totalDividends = 0;
		double totalWealth = 0;
		boolean anyHeir = false;
		for (Agent agent : h.getColony().getAgents())
			if (agent instanceof Noble noble) {
				nobleCount++;
				totalDividends += noble.getDividends();
				totalWealth += noble.getWealth();
				if (noble.getID() > foundingAgents)
					anyHeir = true;
			}

		assertEquals(AristocraticEconomy.NUM_NOBLES, nobleCount,
				"expected the noble population sustained by succession");
		assertTrue(totalDividends > 0,
				"expected nobles to draw positive dividends, got " + totalDividends);
		assertTrue(
				totalWealth > AristocraticEconomy.NUM_NOBLES
						* AristocraticEconomy.NOBLE_INITIAL_SAVINGS,
				"expected noble wealth to grow past the seed fortunes, got "
						+ totalWealth);
		assertTrue(anyHeir,
				"expected at least one noble succession (heir inherited the holdings)");
	}
}
