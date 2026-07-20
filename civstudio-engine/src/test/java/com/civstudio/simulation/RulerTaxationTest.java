package com.civstudio.simulation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.civstudio.agent.ruler.Ruler;

/**
 * Phase-1 Ruler taxation: the sovereign skims a fraction of each <b>public</b>
 * bank's distributable profit and of each noble's income into its treasury each
 * step, while its own (gold) bank is exempt.
 * <p>
 * Built on the standard {@code foundStandardColony} path (a pool colony with a
 * builder, an export aristocracy raised by ennoblement, and the three-currency
 * banking) over a short pre-collapse horizon, so the silver money-changer accrues
 * FX profit and the nobles have taxable income. A taxed run is compared against an
 * otherwise identical untaxed control on the same seed.
 */
class RulerTaxationTest {

	private static final long SEED = 7654321L;

	/** Build and run a standard colony with the given tax rates; short horizon. */
	private static SimulationHarness run(double bankRate, double nobleRate) {
		SimulationConfig cfg = SimulationConfig.DEFAULT.toBuilder()
				.durationYears(3).build();
		SimulationHarness h = SimulationHarness.create(cfg, SEED);
		h.tuneEconomy(e -> e.toBuilder()
				.bankProfitTaxRate(bankRate).nobleIncomeTaxRate(nobleRate).build());
		h.foundStandardColony();
		h.run();
		return h;
	}

	@Test
	void taxationCollectsFromPublicBanksAndNoblesButNotTheCrownsOwnBank() {
		SimulationHarness untaxed = run(0, 0);
		SimulationHarness taxed = run(0.02, 0.02);

		Ruler untaxedRuler = untaxed.getColony().getRuler();
		Ruler taxedRuler = taxed.getColony().getRuler();

		// off by default: no tax collected, and no bank distributes profit toward the
		// treasury
		assertEquals(0, untaxedRuler.getTaxCollected(), 1e-9,
				"no tax should be collected when both rates are 0");
		assertEquals(0, untaxed.getSilverBank().getDistributedProfit(), 1e-9,
				"silver bank distributes nothing in the untaxed control");

		// taxed: the Ruler actually collected revenue, and it came (at least partly)
		// from skimming the public silver bank's distributable profit
		assertTrue(taxedRuler.getTaxCollected() > 0,
				"the Ruler should have collected tax");
		assertTrue(taxed.getSilverBank().getDistributedProfit() > 0,
				"the bank-profit tax should have skimmed the silver bank");

		// ...but the crown's own gold bank is exempt — it is a crown holding whose
		// retained profit IS the treasury, never taxed into the Ruler's account
		assertEquals(0, taxed.getGoldBank().getDistributedProfit(), 1e-9,
				"the crown's own gold bank is exempt from the bank-profit tax");
	}
}
