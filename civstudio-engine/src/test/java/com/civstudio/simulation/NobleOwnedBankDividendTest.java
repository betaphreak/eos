package com.civstudio.simulation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.civstudio.agent.Agent;
import com.civstudio.agent.firm.Firm;
import com.civstudio.agent.noble.Noble;
import com.civstudio.agent.noble.NobleConfig;
import com.civstudio.bank.Bank;
import com.civstudio.bank.CurrencyType;
import com.civstudio.era.Era;
import com.civstudio.settlement.Settlement;

/**
 * A <b>noble that owns a bank</b> draws the bank's retained profit as a dividend.
 * Under the default tiered banking the silver bank is a money-changer that skims an
 * FX fee whenever its noble client's money crosses the copper boundary (dividends in,
 * copper-quoted purchases out); a noble that owns it can skim that retained profit
 * out of the bank's equity via {@link Bank#payDividend}. This is the one piece of
 * coverage the removed {@code AristocraticEconomy} carried that no other run does —
 * {@code payDividend} is only ever called by a noble owner, so a non-zero
 * {@link Bank#getDistributedProfit() distributed profit} implies the whole pathway.
 * <p>
 * The colony is built directly (commoners + firms in copper, one silver-banking noble
 * owning all the founding firms and the silver bank, the default gold ruler and the
 * peasant pool) and run a short while — long enough for the firms to profit, the
 * noble to draw FX-bearing dividends and purchases, and the silver bank to pay its
 * owner.
 */
class NobleOwnedBankDividendTest {

	@Test
	void aNobleDrawsADividendFromTheBankItOwns() {
		SimulationConfig cfg = SimulationConfig.DEFAULT;
		SimulationHarness h = SimulationHarness.create(cfg, 7654321);
		Settlement colony = h.getColony();
		Era.Economy econ = colony.getEconomy();
		h.createMarkets();
		Bank copper = h.getCopperBank();
		Bank silver = h.getSilverBank();
		h.createFirms(copper, i -> copper,
				i -> econ.eFirm().savings(), i -> econ.nFirm().savings());

		// one noble, banking in silver, owning all the founding firms (its dividend
		// income crosses copper -> silver, feeding the silver money-changer's FX
		// profit) and the silver bank itself (so it can draw that profit out)
		List<Firm> allFirms = new ArrayList<>();
		allFirms.addAll(Arrays.asList(h.getEFirms()));
		allFirms.addAll(Arrays.asList(h.getNFirms()));
		allFirms.addAll(Arrays.asList(h.getCapitalFirms()));
		Noble senior = new Noble(0, 1000, allFirms, List.of(silver),
				NobleConfig.DEFAULT, silver, colony);
		colony.addAgent(senior);

		h.createDefaultRuler();
		h.createDefaultRetinue();
		h.foundLaborersFromRetinue(i -> copper, i -> 15);

		// long enough for firm profit -> noble dividends/purchases (FX) -> bank profit
		// -> the owner's bank dividend to flow
		colony.run(300);

		assertEquals(CurrencyType.SILVER, silver.getCurrency(),
				"the noble's bank is the silver money-changer");
		assertTrue(silver.getEquity() > 0,
				"the silver money-changer should retain FX profit, got "
						+ silver.getEquity());
		assertTrue(silver.getDistributedProfit() > 0,
				"the silver bank should have paid a dividend to its noble owner, got "
						+ silver.getDistributedProfit());

		// the owning noble is alive with positive, finite wealth
		Noble owner = null;
		for (Agent a : colony.getAgents())
			if (a instanceof Noble n && n.isAlive()) {
				owner = n;
				break;
			}
		assertTrue(owner != null && owner.getWealth() > 0
				&& Double.isFinite(owner.getWealth()),
				"the bank-owning noble keeps positive, finite wealth");
	}
}
