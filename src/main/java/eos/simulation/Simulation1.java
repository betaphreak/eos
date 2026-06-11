package eos.simulation;

import eos.bank.Bank;
import eos.bank.BankConfig;
import eos.economy.Economy;
import eos.economy.GameSession;
import eos.io.SimLog;

/**
 * Simulation (homogeneous case): every agent of a type starts identical, all
 * banking at a single bank. Construction is delegated to
 * {@link SimulationHarness}; this class supplies only the seed, the (single)
 * bank, and the fixed initial state.
 *
 * @author zhihongx
 */
public class Simulation1 {

	/**
	 * Build and run the simulation.
	 *
	 * @return the harness, exposing the constructed markets, bank and agents
	 */
	public static SimulationHarness run() {
		SimulationConfig cfg = SimulationConfig.DEFAULT;
		GameSession session = new GameSession(7654321);
		Economy economy = session.newEconomy(cfg.startDate(),
				cfg.meanInitAgeYears());
		SimLog.init(economy);

		SimulationHarness h = new SimulationHarness(cfg, economy);
		h.createMarkets();
		Bank bank = h.addBank(BankConfig.DEFAULT);
		h.createFirms(bank, i -> bank,
				i -> cfg.eFirm().savings(), i -> cfg.nFirm().savings());
		h.createLaborers(i -> bank, i -> 15, i -> cfg.laborer().savings());
		h.addCommonPrinters();
		h.addBankPrinter("Bank", bank);
		h.run();
		return h;
	}

	public static void main(String[] args) {
		run();
	}
}
