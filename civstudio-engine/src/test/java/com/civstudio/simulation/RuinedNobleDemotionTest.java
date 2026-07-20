package com.civstudio.simulation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.civstudio.agent.Agent;
import com.civstudio.agent.laborer.Laborer;
import com.civstudio.agent.noble.Noble;
import com.civstudio.agent.noble.NobleConfig;
import com.civstudio.bank.Bank;
import com.civstudio.bank.CurrencyType;
import com.civstudio.era.Era;
import com.civstudio.settlement.Settlement;

/**
 * Phase 4 of the rank ladder (see {@code docs/rank-ladder.md}): the demotion
 * <b>trigger</b>. A noble that runs out of money — insolvent (a net debtor) for a
 * sustained grace period — is "ruined" and demoted back to a laborer, the converse
 * of ennoblement.
 * <p>
 * To exercise it deterministically this builds a living pool colony and injects a
 * noble crushed under an unpayable debt (so it stays insolvent however the economy
 * moves), runs just past the one-year grace window, and asserts the noble was
 * reformed into a copper-banking laborer carrying the same head.
 */
class RuinedNobleDemotionTest {

	@Test
	void aNobleInsolventPastTheGracePeriodIsDemotedToLaborer() {
		SimulationConfig cfg = SimulationConfig.DEFAULT;
		SimulationHarness h = SimulationHarness.create(cfg, 7654321);
		Settlement colony = h.getColony();
		Era.Economy econ = colony.getEconomy();
		h.createMarkets();
		Bank copper = h.getCopperBank();
		// a standard ruler+pool colony (no export sector needed) so it lives well
		// past the one-year grace window; createDefaultRuler registers the demotion
		// trigger as a step action
		h.createFirms(copper, i -> copper,
				i -> econ.eFirm().savings(), i -> econ.nFirm().savings());
		h.createDefaultRuler();
		h.createDefaultRetinue();
		h.foundLaborersFromRetinue(i -> copper, i -> 15);

		// inject a noble drowning in debt: a huge opening loan it can never repay, so
		// it is insolvent every step regardless of any dividends it might collect
		Noble broke = new Noble(0, -1e7, List.of(), List.of(), NobleConfig.DEFAULT,
				h.getSilverBank(), colony);
		colony.addAgent(broke);
		String headName = broke.getHead().fullName();
		assertEquals(CurrencyType.SILVER, broke.getBank().getCurrency(),
				"the injected noble banks in silver");

		// run just past the one-year grace (365 days): the trigger fires ~day 365 and
		// the demotion settles at end of that step. Check soon after so the demoted
		// laborer (which inherits the debt) has not yet aged/starved out.
		colony.run(375);

		// the ruined noble is no longer a living noble of the colony...
		assertFalse(livingNobleNamed(colony, headName),
				"the ruined noble should have been demoted away");
		// ...and a living copper-banking laborer now carries its head
		Laborer demoted = livingLaborerNamed(colony, headName);
		assertNotNull(demoted,
				"the ruined noble should have been reformed into a laborer");
		assertEquals(CurrencyType.COPPER, demoted.getBank().getCurrency(),
				"a demoted noble re-banks in copper");
		assertTrue(demoted.isAlive(), "the demoted laborer is alive");
	}

	private static boolean livingNobleNamed(Settlement colony, String fullName) {
		for (Agent a : colony.getAgents())
			if (a instanceof Noble n && n.isAlive()
					&& n.getHead().fullName().equals(fullName))
				return true;
		return false;
	}

	private static Laborer livingLaborerNamed(Settlement colony, String fullName) {
		for (Agent a : colony.getAgents())
			if (a instanceof Laborer l && l.isAlive()
					&& l.getHead().fullName().equals(fullName))
				return l;
		return null;
	}
}
