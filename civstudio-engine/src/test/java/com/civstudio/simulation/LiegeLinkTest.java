package com.civstudio.simulation;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

import com.civstudio.agent.Agent;
import com.civstudio.agent.laborer.Laborer;
import com.civstudio.agent.ruler.Ruler;
import com.civstudio.bank.Bank;
import com.civstudio.era.Era;
import com.civstudio.settlement.Settlement;

/**
 * The feudal <b>liege</b> up-link on households (P1 of the vassalage plan,
 * {@code docs/estate-system.md}: ruler &rarr; nobles &rarr; peasants). In this behavior-neutral
 * first slice every household is sworn directly to the Crown by default — the grant/ennoblement
 * seam ({@link com.civstudio.agent.AbstractHousehold#setLiege}) re-points it later.
 */
class LiegeLinkTest {

	@Test
	void unassignedHouseholdsAreDirectVassalsOfTheCrown() {
		SimulationConfig cfg = SimulationConfig.DEFAULT;
		SimulationHarness h = SimulationHarness.create(cfg, 7654321);
		Settlement colony = h.getColony();
		h.createMarkets();
		Bank copper = h.getCopperBank();
		Era.Economy econ = colony.getEconomy();
		h.createFirms(copper, i -> copper,
				i -> econ.eFirm().savings(), i -> econ.nFirm().savings());
		h.createDefaultRuler();
		h.createDefaultRetinue();
		h.foundLaborersFromRetinue(i -> copper, i -> 15);

		Ruler ruler = colony.getRuler();
		assertNotNull(ruler, "the colony has a ruler");
		// the Crown is the sovereign root — sworn to no liege
		assertNull(ruler.getLiege(), "the ruler is the sovereign root of the feudal tree");

		// two distinct landed laborers to play liege and vassal
		Laborer a = null, b = null;
		for (Agent ag : colony.getAgents())
			if (ag instanceof Laborer l) {
				if (a == null)
					a = l;
				else if (b == null) {
					b = l;
					break;
				}
			}
		assertNotNull(a, "the colony has laborers");
		assertNotNull(b, "the colony has a second laborer");

		// an unassigned laborer defaults to a direct vassal of the Crown
		assertSame(ruler, a.getLiege(), "an unassigned laborer is the Crown's direct vassal");

		// the grant seam re-points the up-link, and clearing it reverts to the Crown default
		a.setLiege(b);
		assertSame(b, a.getLiege(), "setLiege re-points the household's liege");
		a.setLiege(null);
		assertSame(ruler, a.getLiege(), "clearing the liege reverts to the Crown default");
	}
}
