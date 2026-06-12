package eos.simulation;

import eos.bank.Bank;
import eos.util.Rng;

/**
 * Simulation (heterogeneous case): one bank, but each agent's initial state is
 * randomized around the configured values. Construction is delegated to
 * {@link SimulationHarness}; this class supplies the seed, the (single) bank,
 * and the randomized init logic.
 *
 * @author zhihongx
 */
public class HeterogeneousEconomy {

	/**
	 * Build and run the simulation.
	 *
	 * @return the harness, exposing the constructed markets, bank and agents
	 */
	public static SimulationHarness run() {
		SimulationConfig cfg = SimulationConfig.DEFAULT;
		SimulationHarness h = SimulationHarness.create(cfg, 2345);
		Rng rng = h.getColony().getRng();
		h.createMarkets();
		Bank bank = h.getCopperBank();
		h.createFirms(bank, i -> bank,
				i -> rng.uniform(cfg.eFirm().savings() * 1.1,
						cfg.eFirm().savings() * 0.9),
				i -> rng.uniform(cfg.nFirm().savings() * 1.1,
						cfg.nFirm().savings() * 0.9));
		h.createLaborers(i -> bank,
				i -> rng.gaussian(15, 3),
				i -> rng.uniform(cfg.laborer().savings() * 0.9,
						cfg.laborer().savings() * 1.1));
		h.enableExternalInflow(bank);
		h.addCommonPrinters();
		h.addBankPrinter("Bank", bank);
		h.run();
		return h;
	}

	public static void main(String[] args) {
		run();
	}
}
