package eos.simulation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import eos.agent.firm.StrategicFirmConfig;
import eos.agent.noble.Noble;
import eos.agent.noble.NobleConfig;
import eos.agent.ruler.Ruler;
import eos.bank.Bank;
import eos.bank.CurrencyType;
import eos.settlement.Settlement;

/**
 * Phase-1 Ruler taxation: the sovereign skims a fraction of each bank's
 * distributable profit and of each noble's income into its treasury each step.
 * <p>
 * The scenario mirrors {@link StrategicEconomy} (nobles bank in silver and earn
 * export wages, so the silver money-changer accrues FX profit and the nobles have
 * taxable income), built at a short horizon with no printers. A taxed run is
 * compared against an otherwise identical untaxed control on the same seed.
 */
class RulerTaxationTest {

	private static final long SEED = 7654321L;
	private static final int NUM_NOBLES = 5;
	private static final double NOBLE_INITIAL_SAVINGS = 1000;
	private static final double RULER_START_COPPER =
			CurrencyType.GOLD.toCopper(SimulationHarness.DEFAULT_RULER_GOLD);

	/** Build and run a Strategic-style colony with the given tax rates; no printers. */
	private static SimulationHarness run(double bankRate, double nobleRate) {
		SimulationConfig cfg = SimulationConfig.DEFAULT.toBuilder()
				.bankProfitTaxRate(bankRate).nobleIncomeTaxRate(nobleRate)
				.durationYears(5).build();
		SimulationHarness h = SimulationHarness.create(cfg, SEED);
		Settlement colony = h.getColony();

		h.createMarkets();
		Bank copper = h.getCopperBank();
		Bank silver = h.getSilverBank();
		h.createNobleLaborMarket();
		h.createFirms(copper, i -> copper,
				i -> cfg.eFirm().savings(), i -> cfg.nFirm().savings());
		h.createStrategicFirm(copper, StrategicFirmConfig.DEFAULT);
		for (int n = 0; n < NUM_NOBLES; n++)
			colony.addAgent(new Noble(0, NOBLE_INITIAL_SAVINGS, List.of(), List.of(),
					NobleConfig.DEFAULT, silver, colony));
		h.primeNobleLabor();
		h.createLaborers(i -> copper, i -> 15, i -> cfg.laborer().savings());
		h.createDefaultRuler();
		h.run();
		return h;
	}

	@Test
	void taxationCollectsFromBanksAndNoblesAndIsOffByDefault() {
		SimulationHarness untaxed = run(0, 0);
		SimulationHarness taxed = run(0.02, 0.02);

		Ruler untaxedRuler = untaxed.getColony().getRuler();
		Ruler taxedRuler = taxed.getColony().getRuler();

		// off by default: no tax collected, and the Ruler's own bank skims no
		// distributable profit toward it
		assertEquals(0, untaxedRuler.getTaxCollected(), 1e-9,
				"no tax should be collected when both rates are 0");
		assertEquals(0, untaxed.getSilverBank().getDistributedProfit(), 1e-9,
				"silver bank distributes nothing in the untaxed control");

		// taxed: the Ruler actually collected revenue, and it came (at least
		// partly) from skimming the silver bank's distributable profit
		assertTrue(taxedRuler.getTaxCollected() > 0,
				"the Ruler should have collected tax");
		assertTrue(taxed.getSilverBank().getDistributedProfit() > 0,
				"the bank-profit tax should have skimmed the silver bank");

		// the levies enrich the treasury: an untaxed Ruler only spends its fortune
		// down, while a taxed one accumulates past its 10-gold start
		assertTrue(untaxedRuler.getWealth() < RULER_START_COPPER,
				"the untaxed Ruler should spend its treasury down");
		assertTrue(taxedRuler.getWealth() > untaxedRuler.getWealth(),
				"taxation should leave the Ruler richer than the untaxed control");

		// taxation must not have wrecked the colony
		assertTrue(taxed.currentLaborerCount() >= 0.85 * taxed.getCfg().numLaborers(),
				"the taxed colony should keep its population");
		assertTrue(Double.isFinite(taxed.getNecessityMkt().getLastMktPrice())
				&& taxed.getNecessityMkt().getLastMktPrice() > 0,
				"necessity price should stay finite and positive under taxation");
	}
}
