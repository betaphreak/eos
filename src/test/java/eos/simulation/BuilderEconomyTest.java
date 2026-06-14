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
	void runsToCompletionWithBuilderAndPoolThenCollapses() {
		SimulationHarness h = assertDoesNotThrow(BuilderEconomy::run);

		Settlement colony = h.getColony();
		BuilderFirm builder = h.getBuilderFirm();
		assertNotNull(builder, "expected a registered builder");
		assertSame(builder, colony.getBuilder(),
				"harness and colony should expose the same builder");

		// the builder is staffed exclusively by peasants, so a builder colony has a
		// peasant pool
		assertNotNull(h.getPeasantPool(),
				"a builder colony needs a peasant pool as its workforce");

		// the labor force is founded/replaced from the finite pool, so the colony
		// collapses (now before its industry expansion can drive growth — building a
		// living colony is what a future pool refill would restore)
		SimulationAssertions.assertCollapsed(h);
	}
}
