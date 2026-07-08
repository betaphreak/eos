package com.civstudio.simulation;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.civstudio.bank.Bank;
import com.civstudio.tech.ResearchState;

/**
 * Phase 3 end-to-end check: a standard ruler+export colony actually researches — its
 * strategic sector's intellectual labor accrues research points, the ruler picks a
 * monthly focus, and at least one tech completes before the colony collapses. (With
 * the shipped overlay empty, completed techs carry no effects yet, so the economy is
 * unchanged; effect application on completion is covered by
 * {@code eos.tech.ResearchStateTest}.)
 */
class TechResearchTest {

	@Test
	void aStandardColonyCompletesATechBeforeCollapse() {
		ResearchState research = runStandard().getColony().getResearch();
		assertNotNull(research, "a standard ruler+export colony has research enabled");
		assertTrue(research.getCompletedCount() >= 1,
				"expected at least one tech completed before collapse, got "
						+ research.getCompletedCount());
		// known set grew past the pre-known Classical-complete baseline (229 techs)
		assertTrue(research.getKnownCount() > 229,
				"known techs should grow as research completes");
	}

	/** Build and run a standard HomogeneousEconomy-style colony with no printers. */
	private static SimulationHarness runStandard() {
		SimulationConfig cfg = SimulationConfig.DEFAULT;
		SimulationHarness h = SimulationHarness.create(cfg, 24680);
		h.createMarkets();
		Bank bank = h.getCopperBank();
		h.createFirms(bank, i -> bank, i -> cfg.eFirm().savings(),
				i -> cfg.nFirm().savings());
		h.createDefaultStrategicSector(bank);
		h.createDefaultRuler();
		h.createDefaultRetinue();
		h.foundLaborersFromRetinue(i -> bank, i -> 15);
		h.run();
		return h;
	}
}
