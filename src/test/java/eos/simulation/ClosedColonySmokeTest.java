package eos.simulation;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import eos.bank.Bank;

/**
 * Smoke tests for the bundled <b>closed colonies</b> that share the standard
 * health contract ({@link SimulationAssertions#assertHealthy}): the population is
 * sustained, prices stay finite and positive, and every (default, zero-profit)
 * bank stays a finite intermediary. Each case runs its simulation once and then
 * applies that shared check plus its own expected bank count and any extra
 * invariant. The colonies whose invariants differ from this contract — the open,
 * aristocratic, strategic and bimetallic runs — keep their own dedicated tests.
 */
class ClosedColonySmokeTest {

	/** One simulation under test: how to run it, its bank count, extra checks. */
	private record Case(String name, Supplier<SimulationHarness> run, int banks,
			Consumer<SimulationHarness> extra) {
	}

	private static Stream<Case> simulations() {
		return Stream.of(
				// the homogeneous run doubles as the regression guard for
				// ScaleSweep.diagnose() on a healthy colony (its default scale is
				// this very economy), so it asserts diagnose() judges it stable
				new Case("HomogeneousEconomy", HomogeneousEconomy::run, 1,
						h -> assertNull(ScaleSweep.diagnose(h),
								"a healthy default colony should be judged stable")),
				new Case("HeterogeneousEconomy", HeterogeneousEconomy::run, 1,
						h -> {
						}),
				// both banks must carry real loan and deposit pools: if cross-bank
				// routing were broken, one side's agents would be starved of credit
				new Case("TwoBankEconomy", TwoBankEconomy::run, 2, h -> {
					for (Bank bank : h.getBanks()) {
						assertTrue(bank.getTotalLoan() > 0,
								"expected positive total loan on each bank");
						assertTrue(bank.getTotalDeposit() > 0,
								"expected positive total deposit on each bank");
					}
				}));
	}

	@TestFactory
	Stream<DynamicTest> runsToCompletionWithHealthyEconomy() {
		return simulations().map(c -> DynamicTest.dynamicTest(c.name(), () -> {
			SimulationHarness h = assertDoesNotThrow(() -> c.run().get());
			assertEquals(c.banks(), h.getBanks().size(),
					c.name() + " bank count");
			SimulationAssertions.assertHealthy(h);
			c.extra().accept(h);
		}));
	}
}
