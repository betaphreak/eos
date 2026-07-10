package com.civstudio.simulation;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.civstudio.settlement.Settlement;

/**
 * Verifies the default scenario ({@code HomogeneousEconomy}) founds into the
 * Dhenijansar province and that the dynamic firm provisioning <b>respects the
 * province's plots cap</b> rather than overrunning it: a colony whose 74 plots cap
 * its plot count at 74 ramps food firms until the settlement is physically full,
 * then stops chartering instead of throwing on a failed grow. This is the Phase 2.5
 * guarantee in {@code docs/geography.md}; without the capacity gate ({@code
 * Settlement.hasRoomToExpand}) the run would crash.
 */
class DefaultProvinceFoundingTest {

	@Test
	void foundsIntoDhenijansarAndProvisioningRespectsTheCap() {
		SimulationHarness h = assertDoesNotThrow(HomogeneousEconomy::run);
		Settlement colony = h.getColony();

		assertNotNull(colony.getProvince(), "default colony should be founded into a province");
		assertEquals("Dhenijansar", colony.getProvince().name());
		assertEquals(23.16, colony.getLatitude(), 1e-6);
		// 74 plots cap the colony's plot count; growth never exceeds it
		assertEquals(74, colony.getMaxPlots());
		assertTrue(colony.getPlotCount() <= colony.getMaxPlots(),
				"colony plot count " + colony.getPlotCount() + " exceeded its plots cap "
						+ colony.getMaxPlots());
	}
}
