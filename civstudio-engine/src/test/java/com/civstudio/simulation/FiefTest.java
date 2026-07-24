package com.civstudio.simulation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

import com.civstudio.agent.Agent;
import com.civstudio.agent.firm.StrategicFirmConfig;
import com.civstudio.agent.noble.Noble;
import com.civstudio.bank.Bank;
import com.civstudio.era.Era;
import com.civstudio.settlement.Settlement;

/**
 * The <b>fief</b> (vassalage P3, {@code docs/estate-system.md}): the ablest laborer raised to the
 * aristocracy keeps the ground it rose from as its fief — the plot it lived on becomes the new
 * noble's, and the noble holds it (its palace is later raised there). A noble is a direct vassal of
 * the Crown. This builds a colony with a strategic sector but no initial nobles (so the ruler
 * ennobles the ablest laborer to staff it) and asserts the enfeoffment.
 */
class FiefTest {

	@Test
	void anEnnobledLaborerHoldsTheGroundItRoseFromAsAFief() {
		SimulationConfig cfg = SimulationConfig.DEFAULT; // homePlots + buildEconomy on
		SimulationHarness h = SimulationHarness.create(cfg, 7654321);
		Settlement colony = h.getColony();
		h.createMarkets();
		Bank copper = h.getCopperBank();
		// a strategic export sector but NO initial nobles, so the ruler ennobles the ablest laborer
		h.createNobleLaborMarket();
		Era.Economy econ = colony.getEconomy();
		h.createFirms(copper, i -> copper,
				i -> econ.eFirm().savings(), i -> econ.nFirm().savings());
		h.createStrategicFirm(copper, StrategicFirmConfig.DEFAULT);
		h.primeNobleLabor();
		h.createDefaultRuler();
		h.createDefaultRetinue();
		h.foundLaborersFromRetinue(i -> copper, i -> 15);

		// run past the aristocracy top-up (ennobles the ablest laborer to reach targetNobles)
		colony.run(150);

		Noble raised = null;
		for (Agent a : colony.getAgents())
			if (a instanceof Noble n && n.isAlive()) {
				raised = n;
				break;
			}
		assertNotNull(raised, "a laborer was ennobled to staff the strategic sector");

		// P3: the ennobled laborer holds the ground it rose from as its fief, and the plot names it
		assertNotNull(raised.getFief(), "the ennobled noble holds a fief — its former home plot");
		assertNotNull(raised.getFief().ownerId(), "the fief plot has a holder");
		assertEquals(raised.getID(), (int) raised.getFief().ownerId(),
				"the fief plot is held by the noble that was raised on it");

		// a noble is a direct vassal of the Crown (the ruler leads the feudal tree)
		assertSame(colony.getRuler(), raised.getLiege(), "a noble is a direct vassal of the crown");
	}
}
