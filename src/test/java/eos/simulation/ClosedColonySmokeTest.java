package eos.simulation;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.function.Supplier;
import java.util.stream.Stream;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * Smoke tests for the bundled <b>closed colonies</b>. Each now founds and replaces
 * its labor force by promotion from a finite peasant pool, so each runs the full
 * horizon without tripping an {@code -ea} invariant and then <b>collapses</b> once
 * the reserve drains (the {@code -ea} checks in the agents/markets run throughout,
 * so a clean run exercises them). Each case asserts its expected bank count (the
 * banks persist past the colony's death) and that the colony ended collapsed. Every
 * colony now also populates the silver bank, since its export nobles are raised by
 * ennoblement (and re-bank in silver). The open run keeps its own dedicated test.
 */
class ClosedColonySmokeTest {

	/** One simulation under test: how to run it and its expected bank count. */
	private record Case(String name, Supplier<SimulationHarness> run, int banks) {
	}

	private static Stream<Case> simulations() {
		return Stream.of(
				// 3 banks: the commoner copper bank, the silver bank the export nobles
				// (raised by ennoblement) hold, and the ruler's gold bank
				new Case("HomogeneousEconomy", HomogeneousEconomy::run, 3));
	}

	@TestFactory
	Stream<DynamicTest> runsToCompletionThenCollapses() {
		return simulations().map(c -> DynamicTest.dynamicTest(c.name(), () -> {
			SimulationHarness h = assertDoesNotThrow(() -> c.run().get());
			assertEquals(c.banks(), h.getBanks().size(),
					c.name() + " bank count");
			SimulationAssertions.assertCollapsed(h);
		}));
	}
}
