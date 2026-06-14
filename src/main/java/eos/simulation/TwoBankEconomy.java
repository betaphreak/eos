package eos.simulation;

import java.util.function.IntFunction;

import eos.bank.Bank;
import eos.bank.BankConfig;

/**
 * Simulation (two-bank case): the same colony as {@link HomogeneousEconomy}, but with
 * <b>two</b> banks and every agent type split across them (the capital firm
 * banks at A; enjoyment firms, necessity firms and laborers alternate A/B by
 * index). Wages, purchases and capital payments therefore cross bank
 * boundaries, exercising the agent-routed settlement. Each bank gets its own
 * {@code BankPrinter} ("BankA"/"BankB"). The two banks set rates independently
 * over their own pools, so this run is <i>not</i> expected to match
 * {@link HomogeneousEconomy}.
 */
public class TwoBankEconomy {

	/**
	 * Build and run the simulation.
	 *
	 * @return the harness, exposing the constructed markets, banks and agents
	 */
	public static SimulationHarness run() {
		// start with one enjoyment and one necessity firm; the ruler's dynamic
		// provisioning grows the count to fit demand (new firms bank at A)
		SimulationConfig cfg = SimulationConfig.DEFAULT.toBuilder()
				.numEFirms(1).numNFirms(1).build();
		SimulationHarness h = SimulationHarness.create(cfg, 7654321);
		h.createMarkets();
		Bank bankA = h.addBank(BankConfig.DEFAULT);
		Bank bankB = h.addBank(BankConfig.DEFAULT);
		IntFunction<Bank> alternate = i -> (i % 2 == 0) ? bankA : bankB;

		h.createFirms(bankA, alternate,
				i -> cfg.eFirm().savings(), i -> cfg.nFirm().savings());
		// every settlement has an export sector; its firm and nobles bank at A (so
		// the colony keeps its two banks rather than gaining a third)
		h.createDefaultStrategicSector(bankA);
		// the ruler (founding cash) and the pool precede the labor force, which the
		// ruler founds and replaces by promotion from the pool (the pool banks at A,
		// so the colony keeps its two copper banks)
		Bank gold = h.createDefaultRuler();
		h.enableDynamicFirmProvisioning(bankA);
		h.createDefaultPeasantPool(bankA);
		h.foundLaborersFromPool(alternate, i -> 15);
		h.enableExternalInflow(bankA);
		h.addCommonPrinters();
		h.addBankPrinter("BankA", bankA);
		h.addBankPrinter("BankB", bankB);
		h.addBankPrinter("Gold", gold);
		h.addStrategicSectorPrinters("", bankA);
		h.run();
		return h;
	}

	public static void main(String[] args) {
		run();
	}
}
