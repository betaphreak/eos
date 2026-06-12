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
 * healthy, the three banks carry the three currencies, the nobles draw firm
 * dividends across the currency boundary, and the ruler holds 10 gold at the gold
 * bank as its sole client. The non-copper banks charge a currency-exchange fee, so
 * the silver bank (the nobles' money-changer) turns a profit while the copper bank
 * — the base currency, which never converts — and the gold bank — whose only
 * client, the passive ruler, never transacts — both stay at zero profit.
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

		// the banks are active intermediaries over their own pools of accounts. The
		// copper bank is the base currency and never converts, so it stays a
		// zero-profit intermediary; the silver and gold banks are money-changers
		// that profit from the currency-exchange fee — silver on every noble
		// dividend in and purchase out, gold whenever the ruler buys enjoyment.
		assertTrue(copper.getTotalDeposit() > 0,
				"copper bank should hold commoner deposits");
		assertTrue(silver.getTotalDeposit() > 0,
				"silver bank should hold noble deposits");
		assertEquals(0.0, copper.getEquity(), EQUITY_EPS,
				"copper bank must make zero profit");
		assertTrue(silver.getEquity() > 0,
				"silver bank should profit from the currency-exchange fee, got "
						+ silver.getEquity());
		assertTrue(silver.getDistributableProfit() > 0,
				"the silver bank's FX profit should be distributable to its noble owner");
		assertTrue(gold.getEquity() > 0,
				"gold bank should profit from the ruler's gold->copper purchases, got "
						+ gold.getEquity());

		// the ruler is sustained by succession, banks in gold, and has spent some of
		// its opening 10 gold on enjoyment (so its treasury sits below 10 gold but
		// stays solvent — it never starves)
		int rulerCount = 0;
		for (Agent agent : h.getColony().getAgents())
			if (agent instanceof Ruler ruler) {
				rulerCount++;
				assertEquals(gold, ruler.getBank(), "the ruler banks in gold");
				double rulerGold = h.getColony().convert(ruler.getWealth(),
						CurrencyType.COPPER, CurrencyType.GOLD);
				assertTrue(rulerGold > 0 && rulerGold < 10,
						"the ruler should have spent down its treasury, got "
								+ rulerGold + " gold");
			}
		assertEquals(1, rulerCount, "exactly one ruler, sustained by succession");
		assertTrue(h.getColony().convert(gold.getTotalDeposit(),
				CurrencyType.COPPER, CurrencyType.GOLD) < 10,
				"the gold bank holds only the ruler's (now drawn-down) deposit");

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
