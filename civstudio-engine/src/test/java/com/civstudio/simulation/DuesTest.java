package com.civstudio.simulation;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.civstudio.agent.Agent;
import com.civstudio.agent.firm.StrategicFirmConfig;
import com.civstudio.agent.laborer.Laborer;
import com.civstudio.agent.noble.Noble;
import com.civstudio.bank.Bank;
import com.civstudio.era.Era;
import com.civstudio.settlement.Settlement;

/**
 * Feudal <b>dues</b> (vassalage P4, {@code docs/estate-system.md}): a vassal renders a slice of its
 * income to its liege each step. A noble collects dues from the households on its fiefs — its
 * counterpart of the crown's levy on the noble (which already exists as {@code
 * Ruler.collectTaxes}). This enfeoffs a landed commoner under a noble and asserts the noble collects
 * dues from it once it is earning.
 */
class DuesTest {

	@Test
	void aNobleCollectsDuesFromItsFiefVassals() {
		SimulationConfig cfg = SimulationConfig.DEFAULT;
		SimulationHarness h = SimulationHarness.create(cfg, 7654321);
		Settlement colony = h.getColony();
		h.createMarkets();
		Bank copper = h.getCopperBank();
		h.createNobleLaborMarket();
		Era.Economy econ = colony.getEconomy();
		h.createFirms(copper, i -> copper,
				i -> econ.eFirm().savings(), i -> econ.nFirm().savings());
		h.createStrategicFirm(copper, StrategicFirmConfig.DEFAULT);
		h.primeNobleLabor();
		h.createDefaultRuler();
		h.createDefaultRetinue();
		h.foundLaborersFromRetinue(i -> copper, i -> 15);
		colony.run(150);

		Noble noble = null;
		for (Agent a : colony.getAgents())
			if (a instanceof Noble n && n.isAlive()) {
				noble = n;
				break;
			}
		assertNotNull(noble, "a laborer was ennobled");

		// enfeoff a landed commoner under the noble — a vassal that renders dues
		Laborer vassal = null;
		for (Agent a : colony.getAgents())
			if (a instanceof Laborer l && l.isAlive() && l.getHomePlot() != null
					&& l.getHomePlot() != noble.getFief()) {
				vassal = l;
				break;
			}
		assertNotNull(vassal, "the colony has a landed commoner to enfeoff");
		colony.grantFief(vassal.getHomePlot(), noble);
		assertSame(noble, vassal.getLiege(), "the commoner is now the noble's vassal");

		// run on so the vassal earns income and the noble collects its dues
		double before = noble.getDuesCollected();
		colony.run(60);
		assertTrue(noble.isAlive(), "the noble survives collecting its dues");
		assertTrue(noble.getDuesCollected() > before,
				"the noble collected dues from its vassal(s), total " + noble.getDuesCollected());
	}
}
