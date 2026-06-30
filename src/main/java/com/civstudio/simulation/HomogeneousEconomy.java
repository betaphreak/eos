package com.civstudio.simulation;

import com.civstudio.bank.Bank;

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

	// the default founding province: Dhenijansar (province_id 4411), a small coastal
	// LAND province in the Rahen Coast region. Its 74 plots cap the colony's plot
	// count (only the necessity farms occupy plots); the dynamic provisioning respects
	// that cap rather than overrunning it (see Settlement.hasRoomToExpand / docs/plots.md).
	private static final int DHENIJANSAR = 4411;

	/**
	 * Build and run the simulation.
	 *
	 * @return the harness, exposing the constructed markets, bank and agents
	 */
	public static SimulationHarness run() {
		// the labor force is founded through the pool: the ruler promotes peasants
		// into laborer households on day 0 (see foundLaborersFromRetinue), and dead
		// laborers are likewise replaced by promotion until the reserve drains. The
		// colony is founded with a single enjoyment and a single necessity firm (the
		// config default) and the ruler's dynamic provisioning — on by default for
		// every ruler-bearing colony — grows the count to fit demand.
		SimulationConfig cfg = SimulationConfig.DEFAULT;
		// founded into Dhenijansar: its latitude drives the daylight and its plots
		// cap the settlement size, which the dynamic provisioning now respects
		SimulationHarness h = SimulationHarness.create(cfg, 7654321, DHENIJANSAR);
		// found a standard single-copper-bank colony: markets, firms, export sector,
		// ruler + gold treasury, peasant pool, and the labor force promoted from it on
		// day 0 (see foundStandardColony). The ruler's dynamic provisioning — on by
		// default — grows the firm count from the 1E+1N seed to fit demand.
		h.foundStandardColony(
				i -> cfg.eFirm().savings(), i -> cfg.nFirm().savings(), i -> 15);
		Bank bank = h.getCopperBank();
		h.addCommonPrinters();
		h.addBanksPrinter("Banks");
		h.addStrategicSectorPrinters("", bank);
		h.addGranaryPrinter("Granary");
		h.addPlotInventoryPrinters("");
		h.run();
		return h;
	}

	public static void main(String[] args) {
		run();
	}
}
