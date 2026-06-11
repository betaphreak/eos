package eos.simulation;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import eos.agent.Agent;
import eos.agent.noble.Noble;

/**
 * Smoke test for the economy with an aristocracy. The firms and laborers are the
 * default homogeneous run, so the shared health checks still apply — including
 * the bank's zero-profit invariant, which confirms the dividends are pure
 * within-economy transfers (firm → noble) rather than newly created money. On
 * top of that it verifies the nobles actually drew dividends through the
 * secondary-income channel and grew their fortunes past their seed wealth.
 */
class Simulation6Test {

	@Test
	void runsHealthyAndPaysDividendsToNobles() {
		SimulationHarness h = assertDoesNotThrow(Simulation6::run);

		// firms + laborers unchanged, so the standard invariants hold (population
		// sustained, prices finite/positive, bank a finite zero-profit pass-through)
		SimulationAssertions.assertHealthy(h);

		// the nobles are present and actually received dividends + accumulated
		// wealth beyond their seed fortune (proving the secIC channel fired)
		int nobleCount = 0;
		double totalDividends = 0;
		double totalWealth = 0;
		boolean anyHeir = false;

		// agent IDs are assigned contiguously at construction, so the founding
		// agents occupy 1..foundingAgents; any noble with a higher ID is a
		// successor that replaced a founder who died of old age
		SimulationConfig cfg = h.getCfg();
		int foundingAgents = cfg.numEFirms() + cfg.numNFirms() + 1
				+ cfg.numLaborers() + Simulation6.NUM_NOBLES;

		for (Agent agent : h.getEconomy().getAgents())
			if (agent instanceof Noble noble) {
				nobleCount++;
				totalDividends += noble.getDividends();
				totalWealth += noble.getWealth();
				if (noble.getID() > foundingAgents)
					anyHeir = true;
			}

		assertEquals(Simulation6.NUM_NOBLES, nobleCount,
				"expected the noble population sustained by succession");
		assertTrue(totalDividends > 0,
				"expected nobles to draw positive dividends, got " + totalDividends);
		assertTrue(
				totalWealth > Simulation6.NUM_NOBLES
						* Simulation6.NOBLE_INITIAL_SAVINGS,
				"expected noble wealth to grow past the seed fortunes, got "
						+ totalWealth);

		// mortality + inheritance fired: a founder died and an heir of the same
		// dynasty took over its firms (so dividends above came from a successor)
		assertTrue(anyHeir,
				"expected at least one noble succession (heir inherited the firms)");
	}
}
