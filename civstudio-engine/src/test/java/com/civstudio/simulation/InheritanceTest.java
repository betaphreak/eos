package com.civstudio.simulation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

import com.civstudio.agent.Agent;
import com.civstudio.agent.firm.StrategicFirmConfig;
import com.civstudio.agent.laborer.Laborer;
import com.civstudio.agent.noble.Noble;
import com.civstudio.bank.Bank;
import com.civstudio.era.Era;
import com.civstudio.settlement.Plot;
import com.civstudio.settlement.Settlement;

/**
 * Fief <b>inheritance</b> (vassalage P5, {@code docs/estate-system.md}): a noble's fief and its
 * vassals pass to the heir on the noble's death, so a holding stays with the dynasty rather than
 * reverting to the Crown. This enfeoffs a vassal under a noble, then succeeds the noble (the
 * colony's built-in hereditary replacement) and asserts the heir holds the fief and the vassal is
 * re-sworn to it.
 */
class InheritanceTest {

	@Test
	void anHeirInheritsTheFiefAndItsVassals() {
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

		// enfeoff a landed commoner under the noble — a vassal that should pass to the heir
		Laborer vassal = null;
		for (Agent a : colony.getAgents())
			if (a instanceof Laborer l && l.isAlive() && l.getHomePlot() != null
					&& l.getHomePlot() != noble.getFief()) {
				vassal = l;
				break;
			}
		assertNotNull(vassal, "the colony has a landed commoner to enfeoff");
		colony.grantFief(vassal.getHomePlot(), noble);
		Plot fief = noble.getFief();
		assertSame(noble, vassal.getLiege(), "the commoner is the noble's vassal before succession");

		// succeed the noble — the colony's built-in hereditary replacement mints the heir
		Noble heir = (Noble) noble.successor(colony);
		colony.addAgent(heir); // seat the heir so getLiege resolves to it

		assertSame(fief, heir.getFief(), "the heir inherits the fief");
		assertEquals(heir.getID(), (int) fief.ownerId(), "the fief plot now names the heir");
		assertSame(heir, vassal.getLiege(), "the vassal is re-sworn to the heir, not reverted to the Crown");
	}
}
