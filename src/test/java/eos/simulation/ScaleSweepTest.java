package eos.simulation;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import eos.simulation.ScaleSweep.ScaleResult;

/**
 * Tests for the scale-sweep simulation. The full sweep runs many colonies, so
 * the heavy run-to-completion check covers only the default scale (a regression
 * guard equivalent to the other smoke tests); the minimum-selection logic is
 * checked as a pure function over synthetic results to stay fast.
 */
class ScaleSweepTest {

	@Test
	void defaultScaleIsStable() {
		SimulationHarness h = assertDoesNotThrow(() -> ScaleSweep
				.runScale(ScaleSweep.DEFAULT_FIRMS, ScaleSweep.DEFAULT_LABORERS));
		assertNull(ScaleSweep.diagnose(h),
				"default scale should be judged stable");
		assertTrue(h.currentLaborerCount() > 400,
				"expected >400 laborers alive, got " + h.currentLaborerCount());
	}

	@Test
	void minimumStablePicksFewestStableFirms() {
		List<ScaleResult> results = List.of(
				new ScaleResult(10, 450, true, "ok", 450, 1.0, 1.0),
				new ScaleResult(5, 225, true, "ok", 225, 1.0, 1.0),
				new ScaleResult(3, 135, false, "price blew up", 135, 1e30, 1.0),
				new ScaleResult(2, 90, true, "ok", 90, 1.0, 1.0));

		// fewest firms among the stable rows (k=2), even though k=3 is unstable:
		// stability need not be monotonic in the scale
		assertEquals(2, ScaleSweep.minimumStable(results).firmsPerType());
	}

	@Test
	void minimumStableIsNullWhenNoneStable() {
		List<ScaleResult> results = List.of(
				new ScaleResult(2, 90, false, "collapsed", 0, Double.NaN, 1.0),
				new ScaleResult(1, 45, false, "threw", 0, Double.NaN, 1.0));
		assertEquals(null, ScaleSweep.minimumStable(results));
	}
}
