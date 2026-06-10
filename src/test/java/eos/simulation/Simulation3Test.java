package eos.simulation;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import eos.bank.Bank;

/**
 * Smoke test for the two-bank simulation. Beyond the shared health checks, it
 * verifies both banks are active intermediaries — confirming cross-bank
 * settlement works when agents are split across banks.
 */
class Simulation3Test {

	@Test
	void runsToCompletionWithTwoActiveBanks() {
		SimulationHarness h = assertDoesNotThrow(Simulation3::run);

		assertEquals(2, h.getBanks().size(), "Simulation3 uses two banks");
		SimulationAssertions.assertHealthy(h);

		// both banks must carry real loan and deposit pools: if cross-bank
		// routing were broken, one side's agents would be starved of credit
		for (Bank bank : h.getBanks()) {
			assertTrue(bank.getTotalLoan() > 0,
					"expected positive total loan on each bank");
			assertTrue(bank.getTotalDeposit() > 0,
					"expected positive total deposit on each bank");
		}
	}
}
