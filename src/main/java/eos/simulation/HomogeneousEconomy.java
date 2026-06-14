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
		// laborers are likewise replaced by promotion until the reserve drains.
		// The colony starts with a single enjoyment and a single necessity firm and
		// lets the ruler's dynamic provisioning grow the count to fit demand (see
		// enableDynamicFirmProvisioning) rather than founding a fixed sector.
		SimulationConfig cfg = SimulationConfig.DEFAULT.toBuilder()
				.numEFirms(1).numNFirms(1).build();
		SimulationHarness h = SimulationHarness.create(cfg, 7654321);
		h.createMarkets();
		Bank bank = h.getCopperBank();
		h.createFirms(bank, i -> bank,
				i -> cfg.eFirm().savings(), i -> cfg.nFirm().savings());
		// every settlement has an export sector: the strategic firm and the nobles
		// who staff it, all banking at the single bank
		h.createDefaultStrategicSector(bank);
		// the ruler (holding the founding cash) and the pool are created before the
		// labor force, which the ruler then promotes out of the pool on day 0
		Bank gold = h.createDefaultRuler();
		// let the ruler charter/dissolve consumer-good firms as demand warrants
		h.enableDynamicFirmProvisioning(bank);
		h.createDefaultPeasantPool();
		h.foundLaborersFromPool(i -> bank, i -> 15);
		h.enableExternalInflow(bank);
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
