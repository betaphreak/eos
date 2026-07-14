package com.civstudio.simulation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.civstudio.settlement.Settlement;

/**
 * Phase 4 of the Explorer caravan (docs/explorer-caravan.md): a colony musters food-import
 * levies under food pressure (the {@code ExplorerProvisioner} step action), drives them itself,
 * and the food they forage home lands in its granary. Off by default, so a headless run is
 * unchanged; this drives the enabled path end to end on the default colony.
 */
class ExplorerProvisioningTest {

	private static final int DHENIJANSAR = 4411;

	private static SimulationHarness colony(boolean explorers) {
		SimulationConfig cfg = SimulationConfig.DEFAULT.toBuilder().durationYears(10).build();
		SimulationHarness h = SimulationHarness.create(cfg, 7654321, DHENIJANSAR);
		h.setExplorerProvisioning(explorers);
		h.foundStandardColony(i -> cfg.eFirm().savings(), i -> cfg.nFirm().savings(), i -> 15);
		return h;
	}

	@Test
	void aColonyUnderFoodPressureMustersExplorersThatImportFood() {
		SimulationHarness h = colony(true);
		Settlement colony = h.getColony();
		h.run(); // runs to the colony's collapse

		assertTrue(colony.getGranary().getTotalImported() > 0,
				"explorer levies foraged food home into the granary over the run");
	}

	@Test
	void explorerProvisioningIsOffByDefault() {
		SimulationHarness h = colony(false);
		Settlement colony = h.getColony();
		h.run();

		assertEquals(0.0, colony.getGranary().getTotalImported(), 1e-9,
				"with provisioning off, no levy musters and no food is imported");
		assertTrue(colony.getExcursions().isEmpty(), "no excursions are ever mustered");
	}
}
