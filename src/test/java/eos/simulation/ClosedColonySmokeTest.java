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
import eos.bank.CurrencyType;
import eos.simulation.tools.ScaleSweep;

/**
 * Smoke tests for the bundled <b>closed colonies</b> that share the standard
 * health contract ({@link SimulationAssertions#assertHealthy}): the population is
 * sustained, prices stay finite and positive, and every default bank stays a
 * finite intermediary. (These colonies are closed to immigration, but like every
 * settlement they run an export sector — a {@link eos.agent.firm.StrategicFirm}
 * staffed by nobles — whose earnings accumulate in the bank's equity, so the banks
 * are no longer zero-profit.) Each case runs its simulation once and then applies
 * that shared check plus its own expected bank count and any extra invariant. The
 * colonies whose invariants differ from this contract — the open, aristocratic,
 * strategic and bimetallic runs — keep their own dedicated tests.
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
				// 2 banks: the commoner copper bank and the ruler's gold bank (every
				// settlement now has a gold-banking ruler)
				new Case("HomogeneousEconomy", HomogeneousEconomy::run, 2,
						h -> assertNull(ScaleSweep.diagnose(h),
								"a healthy default colony should be judged stable")),
				new Case("HeterogeneousEconomy", HeterogeneousEconomy::run, 2,
						h -> {
						}),
				// 3 banks: the two commoner copper banks plus the ruler's gold bank.
				// Both copper banks must carry real loan and deposit pools: if
				// cross-bank routing were broken, one side's agents would be starved
				// of credit. (The gold bank has only the ruler, who never borrows.)
				new Case("TwoBankEconomy", TwoBankEconomy::run, 3, h -> {
					for (Bank bank : h.getBanks()) {
						if (bank.getCurrency() != CurrencyType.COPPER)
							continue;
						assertTrue(bank.getTotalLoan() > 0,
								"expected positive total loan on each copper bank");
						assertTrue(bank.getTotalDeposit() > 0,
								"expected positive total deposit on each copper bank");
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
