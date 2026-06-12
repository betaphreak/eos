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
		SimulationConfig cfg = SimulationConfig.DEFAULT;
		SimulationHarness h = SimulationHarness.create(cfg, 7654321);
		h.createMarkets();
		Bank bank = h.getCopperBank();
		h.createFirms(bank, i -> bank,
				i -> cfg.eFirm().savings(), i -> cfg.nFirm().savings());
		// every settlement has an export sector: the strategic firm and the nobles
		// who staff it, all banking at the single bank
		h.createDefaultStrategicSector(bank);
		h.createLaborers(i -> bank, i -> 15, i -> cfg.laborer().savings());
		h.enableExternalInflow(bank);
		// every settlement has a ruler, banking in gold (created last)
		Bank gold = h.createDefaultRuler();
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
