package eos.simulation;

import java.util.function.IntFunction;

import eos.bank.Bank;
import eos.bank.BankConfig;
import eos.io.SimLog;
import eos.util.StdRandom;

/**
 * Simulation (two-bank case): the same economy as {@link Simulation1}, but with
 * <b>two</b> banks and every agent type split across them (the capital firm
 * banks at A; enjoyment firms, necessity firms and laborers alternate A/B by
 * index). Wages, purchases and capital payments therefore cross bank
 * boundaries, exercising the agent-routed settlement. Each bank gets its own
 * {@code BankPrinter} ("BankA"/"BankB"). The two banks set rates independently
 * over their own pools, so this run is <i>not</i> expected to match
 * {@link Simulation1}.
 */
public class Simulation3 {

	/**
	 * Build and run the simulation.
	 *
	 * @return the harness, exposing the constructed markets, banks and agents
	 */
	public static SimulationHarness run() {
		SimLog.init();
		SimulationConfig cfg = SimulationConfig.DEFAULT;
		StdRandom.setSeed(7654321);

		SimulationHarness h = new SimulationHarness(cfg);
		h.createMarkets();
		Bank bankA = h.addBank(BankConfig.DEFAULT);
		Bank bankB = h.addBank(BankConfig.DEFAULT);
		IntFunction<Bank> alternate = i -> (i % 2 == 0) ? bankA : bankB;

		h.createFirms(bankA, alternate,
				i -> cfg.eFirm().savings(), i -> cfg.nFirm().savings());
		h.createLaborers(alternate, i -> 15, i -> cfg.laborer().savings());
		h.addCommonPrinters();
		h.addBankPrinter("BankA", bankA);
		h.addBankPrinter("BankB", bankB);
		h.run();
		return h;
	}

	public static void main(String[] args) {
		run();
	}
}
