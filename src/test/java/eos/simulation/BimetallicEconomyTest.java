package eos.simulation;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import eos.agent.Agent;
import eos.agent.noble.Noble;
import eos.agent.ruler.Ruler;
import eos.bank.Bank;
import eos.bank.CurrencyType;

/**
 * Smoke test for the three-currency colony: commoners (laborers and firms) bank
 * in copper, nobles in silver, and the ruler in gold. Verifies the colony stays
 * healthy, the three banks carry the three currencies and are active zero-profit
 * intermediaries, the nobles draw firm dividends across the currency boundary,
 * and the ruler holds 10 gold at the gold bank as its sole client.
 */
class BimetallicEconomyTest {

	private static final double EQUITY_EPS = 1e-3;

	@Test
	void runsHealthyWithThreeCurrenciesSplitByClass() {
		SimulationHarness h = assertDoesNotThrow(BimetallicEconomy::run);

		assertEquals(3, h.getBanks().size(), "BimetallicEconomy uses three banks");

		// core health: population sustained (>400), prices finite/positive, banks
		// finite intermediaries
		SimulationAssertions.assertCoreHealthy(h, 401);

		// the three banks carry the three currencies (copper is the default first)
		Bank copper = h.getBanks().get(0);
		Bank silver = h.getBanks().get(1);
		Bank gold = h.getBanks().get(2);
		assertEquals(CurrencyType.COPPER, copper.getCurrency(),
				"the default first bank is copper");
		assertEquals(CurrencyType.SILVER, silver.getCurrency(),
				"the noble bank is silver");
		assertEquals(CurrencyType.GOLD, gold.getCurrency(),
				"the ruler's bank is gold");

		// the banks are active, ordinary zero-profit intermediaries over their own
		// pools of accounts
		assertTrue(copper.getTotalDeposit() > 0,
				"copper bank should hold commoner deposits");
		assertTrue(silver.getTotalDeposit() > 0,
				"silver bank should hold noble deposits");
		assertEquals(0.0, copper.getEquity(), EQUITY_EPS,
				"copper bank must make zero profit");
		assertEquals(0.0, silver.getEquity(), EQUITY_EPS,
				"silver bank must make zero profit");
		assertEquals(0.0, gold.getEquity(), EQUITY_EPS,
				"gold bank must make zero profit");

		// the ruler is sustained by succession, banks in gold, and holds its 10
		// gold there (the gold bank's only deposit)
		int rulerCount = 0;
		for (Agent agent : h.getColony().getAgents())
			if (agent instanceof Ruler ruler) {
				rulerCount++;
				assertEquals(gold, ruler.getBank(), "the ruler banks in gold");
				assertEquals(10, h.getColony().convert(ruler.getWealth(),
						CurrencyType.COPPER, CurrencyType.GOLD), 1e-6,
						"the ruler holds 10 gold");
			}
		assertEquals(1, rulerCount, "exactly one ruler, sustained by succession");
		assertEquals(10, h.getColony().convert(gold.getTotalDeposit(),
				CurrencyType.COPPER, CurrencyType.GOLD), 1e-6,
				"the gold bank holds only the ruler's 10 gold");

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
