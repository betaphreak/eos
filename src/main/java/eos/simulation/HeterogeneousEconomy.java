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
		// found a standard single-copper-bank colony, but with each agent's initial
		// state randomized around the configured values (the init functions consume
		// the economic RNG lazily, as they are applied inside the founding sequence —
		// see foundStandardColony)
		Bank gold = h.foundStandardColony(
				i -> rng.uniform(cfg.eFirm().savings() * 1.1,
						cfg.eFirm().savings() * 0.9),
				i -> rng.uniform(cfg.nFirm().savings() * 1.1,
						cfg.nFirm().savings() * 0.9),
				i -> rng.gaussian(15, 3));
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
