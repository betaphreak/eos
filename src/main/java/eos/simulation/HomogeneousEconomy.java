package eos.simulation;

import eos.bank.Bank;

/**
 * Simulation (homogeneous case): every agent of a type starts identical, with the
 * commoners (firms, laborers) and the export-sector nobles all banking at a single
 * copper bank. Like every settlement it also has the default export sector and a
 * gold-banking ruler, so it carries a second (gold) bank. Construction is delegated
 * to {@link SimulationHarness}; this class supplies only the seed, the commoners'
 * bank, and the fixed initial state.
 *
 * @author zhihongx
 */
public class HomogeneousEconomy {

	/**
	 * Build and run the simulation.
	 *
	 * @return the harness, exposing the constructed markets, bank and agents
	 */
	public static SimulationHarness run() {
		// the labor force is founded through the pool: the ruler promotes peasants
		// into laborer households on day 0 (see foundLaborersFromPool), and dead
		// laborers are likewise replaced by promotion until the reserve drains. The
		// colony is founded with a single enjoyment and a single necessity firm (the
		// config default) and the ruler's dynamic provisioning — on by default for
		// every ruler-bearing colony — grows the count to fit demand.
		SimulationConfig cfg = SimulationConfig.DEFAULT;
		SimulationHarness h = SimulationHarness.create(cfg, 7654321);
		// found a standard single-copper-bank colony: markets, firms, export sector,
		// ruler + gold treasury, peasant pool, and the labor force promoted from it on
		// day 0 (see foundStandardColony). The ruler's dynamic provisioning — on by
		// default — grows the firm count from the 1E+1N seed to fit demand.
		Bank gold = h.foundStandardColony(
				i -> cfg.eFirm().savings(), i -> cfg.nFirm().savings(), i -> 15);
		Bank bank = h.getCopperBank();
		h.addCommonPrinters();
		h.addBankPrinter("Bank", bank);
		h.addBankPrinter("Gold", gold);
		h.addStrategicSectorPrinters("", bank);
		h.run();
		return h;
	}

	public static void main(String[] args) {
		run();
	}
}
