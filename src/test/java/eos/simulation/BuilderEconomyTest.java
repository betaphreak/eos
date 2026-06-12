package eos.simulation;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import eos.agent.firm.BuilderFirm;
import eos.settlement.Settlement;
import eos.settlement.SlotTable;

/**
 * Smoke test for the colony that builds itself bigger. Beyond the core health
 * invariants it checks the builder mechanism actually fired: a live colony with a
 * builder grew (its founding footprint did not instantly stretch), the builder
 * delivered build-units doing it, and the colony ended larger than the floor it was
 * founded at. The {@code -ea} invariants in the builder/settlement growth path run
 * throughout, so a clean run also exercises them.
 */
class BuilderEconomyTest {

	@Test
	void runsHealthyAndBuildsItselfBigger() {
		SimulationHarness h = assertDoesNotThrow(BuilderEconomy::run);

		// core health: population sustained at (near) the minimum stable scale,
		// consumer prices finite/positive, bank deposit/rates finite
		SimulationAssertions.assertCoreHealthy(h, 150);

		Settlement colony = h.getColony();
		BuilderFirm builder = h.getBuilderFirm();
		assertNotNull(builder, "expected a registered builder");
		assertSame(builder, colony.getBuilder(),
				"harness and colony should expose the same builder");

		// the builder actually did construction work over the run
		assertTrue(builder.getTotalDelivered() > 0,
				"expected the builder to have delivered build-units, got "
						+ builder.getTotalDelivered());

		// and the colony grew past the floor size it was founded at — growth that,
		// for a live colony, can only have come through the builder
		assertTrue(colony.getSize() > SlotTable.MIN_SIZE,
				"expected the colony to have grown past its founding size "
						+ SlotTable.MIN_SIZE + ", got " + colony.getSize());
	}
}
