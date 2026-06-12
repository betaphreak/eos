package eos.simulation;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import eos.agent.Agent;
import eos.agent.noble.Noble;
import eos.bank.Bank;
import eos.bank.CurrencyType;

/**
 * Smoke test for the two-currency colony: commoners (laborers and firms) bank in
 * copper, nobles bank in silver. Verifies the colony stays healthy, the two banks
 * carry the two currencies and are both active zero-profit intermediaries, and
 * the nobles bank in silver while drawing firm dividends across the currency
 * boundary.
 */
class BimetallicEconomyTest {

	private static final double EQUITY_EPS = 1e-3;

	@Test
	void runsHealthyWithCommonersInCopperAndNoblesInSilver() {
		SimulationHarness h = assertDoesNotThrow(BimetallicEconomy::run);

		assertEquals(2, h.getBanks().size(), "BimetallicEconomy uses two banks");

		// core health: population sustained (>400), prices finite/positive, banks
		// finite intermediaries
		SimulationAssertions.assertCoreHealthy(h, 401);

		// the two banks carry the two currencies (copper is the default first bank)
		Bank copper = h.getBanks().get(0);
		Bank silver = h.getBanks().get(1);
		assertEquals(CurrencyType.COPPER, copper.getCurrency(),
				"the default first bank is copper");
		assertEquals(CurrencyType.SILVER, silver.getCurrency(),
				"the noble bank is silver");

		// both banks are active, ordinary zero-profit intermediaries over their
		// own pools of accounts
		assertTrue(copper.getTotalDeposit() > 0,
				"copper bank should hold commoner deposits");
		assertTrue(silver.getTotalDeposit() > 0,
				"silver bank should hold noble deposits");
		assertEquals(0.0, copper.getEquity(), EQUITY_EPS,
				"copper bank must make zero profit");
		assertEquals(0.0, silver.getEquity(), EQUITY_EPS,
				"silver bank must make zero profit");

		// the nobles bank in silver and draw firm dividends across the currency
		// boundary (firm in copper -> noble in silver)
		int nobleCount = 0;
		double totalDividends = 0;
		for (Agent agent : h.getColony().getAgents())
			if (agent instanceof Noble noble) {
				nobleCount++;
				totalDividends += noble.getDividends();
				assertEquals(silver, noble.getBank(),
						"nobles must bank exclusively in silver");
			}
		assertEquals(BimetallicEconomy.NUM_NOBLES, nobleCount,
				"expected the noble population sustained by succession");
		assertTrue(totalDividends > 0,
				"expected nobles to draw dividends, got " + totalDividends);
	}
}
