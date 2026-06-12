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
 * Smoke test for the colony with an aristocracy that owns the firms <i>and</i> a
 * bank, atop the full three-currency hierarchy. Under the default tiered banking
 * the commoners bank in copper (the base currency), the nobles in silver, and the
 * settlement's ruler in gold; the senior noble owns the silver money-changer, which
 * profits from the FX fee on the nobles' copper-quoted dividends and purchases and
 * pays that out to its owner. The shared {@code assertHealthy} does not apply; the
 * invariants are checked directly. Beyond a sustained, finite-price colony it
 * verifies that the firms <em>and</em> the silver bank actually paid dividends to
 * the nobles, that the nobles grew their fortunes, that a noble succession occurred
 * (heir inherited the holdings), and that the gold-banking ruler draws its treasury
 * down on enjoyment (the coverage formerly carried by the bimetallic run).
 */
class AristocraticEconomyTest {

	@Test
	void runsHealthyAndPaysFirmAndBankDividends() {
		SimulationHarness h = assertDoesNotThrow(AristocraticEconomy::run);

		// core health: population sustained (>400), consumer prices
		// finite/positive, bank deposit/rates finite (shared with the closed runs)
		SimulationAssertions.assertCoreHealthy(h, 401);

		// three banks under the default tiered system: the copper base bank, the
		// noble-owned silver money-changer (which turns an FX profit and pays part out
		// as dividends), and the ruler's gold bank
		assertEquals(3, h.getBanks().size(),
				"AristocraticEconomy uses three banks (copper + silver + gold)");
		Bank copper = h.getBanks().get(0);
		Bank silver = h.getBanks().get(1);
		Bank gold = h.getBanks().get(2);
		assertEquals(CurrencyType.SILVER, silver.getCurrency(), "second bank is silver");
		assertEquals(CurrencyType.GOLD, gold.getCurrency(), "third bank is gold");
		// the copper bank is the base-currency intermediary, but it now also holds
		// the export firm's earnings (every settlement has a strategic sector), so
		// its equity grows over the run rather than staying pinned at zero
		assertTrue(copper.getEquity() > 0,
				"the copper bank should accumulate the export sector's earnings");
		assertTrue(silver.getEquity() > 0,
				"expected the noble-owned silver money-changer to retain FX profit");
		assertTrue(silver.getDistributedProfit() > 0,
				"expected the silver bank to have paid dividends to its noble owner");
		// the gold bank profits from the FX fee on the ruler's gold -> copper
		// enjoyment purchases (the only thing that makes the otherwise-idle gold bank
		// transact)
		assertTrue(gold.getEquity() > 0,
				"expected the gold bank to profit from the ruler's purchases, got "
						+ gold.getEquity());

		// agent IDs are assigned contiguously at construction, so the founding
		// agents occupy 1..foundingAgents; any noble with a higher ID is a
		// successor that replaced a founder who died of old age
		SimulationConfig cfg = h.getCfg();
		int foundingAgents = cfg.numEFirms() + cfg.numNFirms()
				+ 1 // capital firm
				+ 1 // strategic export firm (every settlement has one)
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

		// the settlement's ruler: exactly one, banking in gold, sustained by
		// succession, having spent some of its opening 10 gold on enjoyment (so its
		// treasury sits below 10 gold but stays solvent — it never starves)
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
	}
}
