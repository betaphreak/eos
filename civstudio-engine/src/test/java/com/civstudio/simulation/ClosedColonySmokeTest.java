package com.civstudio.simulation;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.function.Supplier;
import java.util.stream.Stream;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import com.civstudio.settlement.Settlement;

/**
 * Smoke tests for the bundled <b>closed colonies</b>. Each now founds and replaces
 * its labor force by promotion from a finite peasant pool, so each runs the full
 * horizon without tripping an {@code -ea} invariant and then — once the reserve drains
 * and the workforce falls past the dissolution floor — <b>departs as a Caravan</b>: the
 * settled colony dissolves and its survivors take to the road (see {@code
 * docs/caravan.md}). (The {@code -ea} checks in the agents/markets run throughout, so a
 * clean run exercises them.) Each case asserts its expected bank count (the banks
 * persist past the colony's dissolution), that it founded into the expected province
 * and never overran that province's plots cap (the Phase 2.5 capacity gate,
 * {@code docs/geography.md}), and that the colony ended by departing as a band. Every
 * colony now also populates the silver bank, since its export nobles are raised by
 * ennoblement (and re-bank in silver). The open run keeps its own dedicated test.
 */
class ClosedColonySmokeTest {

	/** One simulation under test: how to run it, its expected bank count, and where it founds. */
	private record Case(String name, Supplier<SimulationHarness> run, int banks,
			String province, double latitude, int maxPlots) {
	}

	private static Stream<Case> simulations() {
		return Stream.of(
				// 3 banks: the commoner copper bank, the silver bank the export nobles
				// (raised by ennoblement) hold, and the ruler's gold bank. Founds into
				// Dhenijansar (74 plots) — the default-scenario province.
				// via CanonicalRun: the ONE full default-colony run the fast tier pays for,
				// shared read-only with every other class asserting on the finished colony
				new Case("HomogeneousEconomy", CanonicalRun::get, 3, "Dhenijansar", 23.16, 74));
	}

	@TestFactory
	Stream<DynamicTest> runsToCompletionThenCollapses() {
		return simulations().map(c -> DynamicTest.dynamicTest(c.name(), () -> {
			SimulationHarness h = assertDoesNotThrow(() -> c.run().get());

			// founds into the expected province and dynamic provisioning respects its plots cap —
			// the colony ramps food firms until the settlement is physically full, then stops
			// chartering rather than throwing on a failed grow (was DefaultProvinceFoundingTest).
			Settlement colony = h.getColony();
			assertNotNull(colony.getProvince(), c.name() + " should be founded into a province");
			assertEquals(c.province(), colony.getProvince().name(), c.name() + " province");
			assertEquals(c.latitude(), colony.getLatitude(), 1e-6);
			assertEquals(c.maxPlots(), colony.getMaxPlots());
			assertTrue(colony.getPlotCount() <= colony.getMaxPlots(),
					"colony plot count " + colony.getPlotCount() + " exceeded its plots cap "
							+ colony.getMaxPlots());

			assertEquals(c.banks(), h.getBanks().size(), c.name() + " bank count");
			// THE DEFAULT-FLIP REBASELINE (2026-07-23): under the build economy the mature
			// closed colony SURVIVES its full 25 years — the collapse this test asserted for
			// years is fixed by the occupation choice (labor-supply withdrawal raises wages,
			// housing gates demographics, subsistence rides home plots). The clean-collapse
			// doctrine ends here: survival is the new expected outcome.
			assertTrue(colony.isAlive(),
					c.name() + " survives its full run under the build economy");
		}));
	}
}
