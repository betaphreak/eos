package eos.simulation;

import java.util.List;

import eos.agent.firm.StrategicFirm;
import eos.agent.firm.StrategicFirmConfig;
import eos.agent.noble.Noble;
import eos.agent.noble.NobleConfig;
import eos.bank.Bank;
import eos.bank.BankConfig;
import eos.io.printer.NoblesPrinter;
import eos.io.printer.StrategicPrinter;
import eos.settlement.Settlement;

/**
 * Simulation (with a strategic export sector worked by the aristocracy): the
 * homogeneous, single-bank colony of {@link HomogeneousEconomy}, plus a class of
 * <b>nobles</b> and the settlement's single {@link StrategicFirm}. Unlike the
 * consumer and capital firms (staffed by laborers), the export firm's labor pool
 * is constrained to the nobles: they alone are the employees of a dedicated
 * {@code NobleLabor} market, and the firm converts their <b>skill-scaled</b>
 * labor into the strategic good, which it <b>exports out of the economy</b>. The
 * export earnings flow into the bank's <b>equity</b>, and the firm's wage bill is
 * funded back out of it — so the firm is a pure conduit and the bank's equity
 * climbs with cumulative net exports.
 * <p>
 * The nobles here own no firms or banks; they live off the wages the strategic
 * firm pays them (plus their seed fortunes), so their income is the export
 * sector's labor share. They age, die and are succeeded by same-dynasty heirs who
 * keep working the export sector. Unlike the closed default runs (whose bank is a
 * zero-profit, ~zero-equity intermediary), here the bank's equity grows
 * monotonically with cumulative net exports — the defining observable.
 */
public class StrategicEconomy {

	/** Number of noble households that staff the export sector. */
	static final int NUM_NOBLES = 5;

	/** Each noble's opening savings (its seed fortune). */
	static final double NOBLE_INITIAL_SAVINGS = 1000;

	/**
	 * Build and run the simulation.
	 *
	 * @return the harness, exposing the constructed markets, bank, firms (the
	 *         export firm via {@code getStrategicFirm()}) and agents (the nobles
	 *         via {@code getColony().getAgents()})
	 */
	public static SimulationHarness run() {
		SimulationConfig cfg = SimulationConfig.DEFAULT;
		SimulationHarness h = SimulationHarness.create(cfg, 7654321);
		Settlement colony = h.getColony();

		h.createMarkets();
		Bank bank = h.addBank(BankConfig.DEFAULT);
		// the noble-only labor market must exist before the export firm and the
		// nobles are created (both look it up)
		h.createNobleLaborMarket();
		h.createFirms(bank, i -> bank,
				i -> cfg.eFirm().savings(), i -> cfg.nFirm().savings());
		h.createStrategicFirm(bank, StrategicFirmConfig.DEFAULT);

		// the nobles who work the export sector (they own no firms or banks here)
		for (int n = 0; n < NUM_NOBLES; n++) {
			Noble noble = new Noble(0, NOBLE_INITIAL_SAVINGS, List.of(), List.of(),
					NobleConfig.DEFAULT, bank, colony);
			colony.addAgent(noble);
		}
		// when a noble's head dies, a same-dynasty successor inherits its estate
		// and keeps working the export sector
		colony.addReplacementPolicy(dead -> dead instanceof Noble n
				? new Noble(n, NobleConfig.DEFAULT, colony)
				: null);

		// clear the noble labor market once so the export firm has noble workers
		// in step 0 (mirrors the pre-run clearing of the general labor market)
		h.primeNobleLabor();

		h.createLaborers(i -> bank, i -> 15, i -> cfg.laborer().savings());
		h.enableExternalInflow(bank);
		h.addCommonPrinters();
		h.addBankPrinter("Bank", bank);
		StrategicFirm firm = h.getStrategicFirm();
		colony.addPrinter(new StrategicPrinter("Strategic", firm, bank));
		colony.addPrinter(new NoblesPrinter("Nobles"));
		h.run();
		return h;
	}

	public static void main(String[] args) {
		run();
	}
}
