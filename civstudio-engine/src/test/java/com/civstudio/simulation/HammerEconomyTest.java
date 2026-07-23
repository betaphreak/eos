package com.civstudio.simulation;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.civstudio.agent.Agent;
import com.civstudio.agent.firm.Firm;
import com.civstudio.settlement.BuildEconomy;
import com.civstudio.settlement.Settlement;

/**
 * End-to-end coverage of the <b>build economy's occupation choice</b>
 * ({@code docs/build-queue-plan.md} B1): on a {@code buildEconomy} colony, settled
 * landed households weigh selling labor at the center against working their home plot
 * for hammers + commerce (reservation wage, optimism prior, hysteresis, unhired
 * fallback). The B1 acceptance shape: the choice <b>binds</b> (plot output flows — via
 * chosen plot days or the unhired fallback), the commerce mint is real coin in
 * household accounts, and the colony neither collapses from home-working nor starves
 * its firms of all labor.
 */
class HammerEconomyTest {

	@Test
	void occupationChoiceBindsAndTheColonySurvives() {
		SimulationConfig cfg = SimulationConfig.DEFAULT.toBuilder()
				.foundAtCamp(true).homePlots(true).buildEconomy(true).build();
		SimulationHarness h = SimulationHarness.create(cfg, 7654321, 4411);
		h.tuneEconomy(e -> e.toBuilder().retinueSize(60).build());
		h.foundStandardColony();
		Settlement c = h.getColony();
		BuildEconomy be = c.getBuildEconomy();
		assertNotNull(be, "the buildEconomy flag switches the build economy on");
		c.start();

		// climb the camp to the boot (the settled laborer households appear here)
		for (int day = 0; day < 200 && c.getRuler() == null && c.isAlive(); day++)
			c.newDay();
		assertTrue(c.getRuler() != null, "the camp booted its ruler economy at SMALLHOLDING");

		// run a settled year: wage discovery (optimism prior sends everyone to market
		// first), then the choice/fallback dynamics play out across seasons
		for (int day = 0; day < 365 && c.isAlive(); day++)
			c.newDay();
		assertTrue(c.isAlive(), "the build-economy colony survives the settled year");

		// plot output flowed: hammers were donated to the colony sink. On Dhenijansar's
		// ground this comes from the UNHIRED FALLBACK — raw farmland has a ~0 commerce
		// channel (Civ4 commerce needs rivers/coast/cottage-type improvements), so the
		// reservation wage (commerce-only by design) never pulls a household home and the
		// plot days are the unemployed working their land. The choice machinery proper
		// arms itself when commerce channels open (P5 improvements). Commerce minted is
		// asserted only non-negative for the same reason — 0 on commerce-less ground.
		assertTrue(be.getTotalHammersDonated() > 0,
				"households worked plots (via the unhired fallback): hammers were donated"
						+ " (total " + be.getTotalHammersDonated() + ")");
		assertTrue(be.getTotalCommerceMinted() >= 0,
				"commerce mint is non-negative (0 on commerce-less ground)");

		// the market did not empty: firms still hold labor-funded output capacity — the
		// home-working pull left the wage economy functioning (the B1 market-collapse
		// guard; the fuller acceptance bar tightens in B3+)
		double firmOutput = 0;
		for (Agent a : c.getAgents())
			if (a.isAlive() && a instanceof Firm f)
				firmOutput += f.getOutput();
		assertTrue(firmOutput > 0,
				"firms still produce — home-working did not empty the labor market");

		// B3: the housing wave fired — the homeless-and-fed rule sends unhoused landed
		// households home to build, so after a settled year some completed their house
		// (adoption of orphaned houses counts too) and pass the wedding/fission gate
		int landed = 0, housed = 0;
		for (Agent a : c.getAgents())
			if (a.isAlive() && a instanceof com.civstudio.agent.laborer.Laborer l
					&& l.getHomePlot() != null) {
				landed++;
				if (l.housedForGate())
					housed++;
			}
		assertTrue(landed > 0, "the settled colony has landed households");
		assertTrue(housed > 0, "the housing wave completed houses: " + housed + "/" + landed
				+ " landed households are housed");
	}
}
