package com.civstudio.simulation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.civstudio.settlement.Settlement;

/**
 * Phase 4/7 of the Explorer caravan (docs/explorer-caravan.md): mustering winter foraging levies
 * is the <b>default behaviour for a City settlement</b> — the colony musters them itself, drives
 * them, and the food they forage home lands in its granary. A <b>Village</b> (a single urban plot)
 * does none of this. No opt-in flag: the tier decides.
 */
class ExplorerProvisioningTest {

	private static final int DHENIJANSAR = 4411; // a City
	private static final int EARGATE = 2;        // a settleable Village

	private static SimulationHarness colony(int province) {
		SimulationConfig cfg = SimulationConfig.DEFAULT.toBuilder().durationYears(10).build();
		SimulationHarness h = SimulationHarness.create(cfg, 7654321, province);
		h.foundStandardColony(i -> cfg.eFirm().savings(), i -> cfg.nFirm().savings(), i -> 15);
		return h;
	}

	@Test
	void aCityMustersExplorersThatImportFoodByDefault() {
		SimulationHarness h = colony(DHENIJANSAR);
		Settlement colony = h.getColony();
		h.run(); // runs to the colony's collapse

		assertTrue(colony.getGranary().getTotalImported() > 0,
				"a City musters winter foraging levies by default and their food lands in the granary");
	}

	@Test
	void aVillageMustersNoExplorers() {
		SimulationHarness h = colony(EARGATE);
		Settlement colony = h.getColony();
		h.run();

		assertEquals(0.0, colony.getGranary().getTotalImported(), 1e-9,
				"a Village musters no levy, so no food is imported");
		assertTrue(colony.getExcursions().isEmpty(), "a Village founds no excursions");
	}
}
