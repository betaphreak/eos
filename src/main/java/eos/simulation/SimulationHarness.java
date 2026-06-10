package eos.simulation;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;
import java.util.function.IntToDoubleFunction;

import eos.agent.firm.CFirm;
import eos.agent.firm.EFirm;
import eos.agent.firm.FirmConfig;
import eos.agent.firm.NFirm;
import eos.agent.laborer.Laborer;
import eos.agent.laborer.LaborerConfig;
import eos.bank.Bank;
import eos.bank.BankConfig;
import eos.economy.Economy;
import eos.economy.GameSession;
import eos.io.printer.*;
import eos.market.*;
import lombok.Getter;

/**
 * Shared construction and run logic for the bundled simulations. Each
 * {@code Simulation} creates an {@link Economy} from a {@link GameSession}
 * (which owns the seed) and populates it through this harness, supplying only
 * what differs between runs: which bank each agent uses, and how each agent's
 * initial state is drawn. After {@link #run()} the harness exposes the
 * constructed markets, banks and agents so tests can assert on the final state.
 * <p>
 * Call order mirrors the original hand-written simulations and matters because
 * of deferred settlement and reproducible RNG consumption: {@link
 * #createMarkets()}, {@link #addBank(BankConfig)}, {@link #createFirms}, {@link
 * #createLaborers}, printers, then {@link #run()}.
 */
@Getter
public class SimulationHarness {

	private final SimulationConfig cfg;
	private final Economy economy;
	private final List<Bank> banks = new ArrayList<>();

	private ConsumerGoodMarket enjoymentMkt;
	private ConsumerGoodMarket necessityMkt;
	private LaborMarket laborMkt;
	private CapitalMarket capitalMkt;

	private CFirm[] capitalFirms;
	private EFirm[] eFirms;
	private NFirm[] nFirms;
	private Laborer[] laborers;

	public SimulationHarness(SimulationConfig cfg, Economy economy) {
		this.cfg = cfg;
		this.economy = economy;
	}

	/** Create the four markets and register them (labor market first). */
	public void createMarkets() {
		enjoymentMkt = new ConsumerGoodMarket("Enjoyment", cfg.ePrice().min(),
				cfg.ePrice().max(), economy);
		necessityMkt = new ConsumerGoodMarket("Necessity", cfg.nPrice().min(),
				cfg.nPrice().max(), economy);
		laborMkt = new LaborMarket(economy);
		capitalMkt = new CapitalMarket(economy);
		economy.addMarket(laborMkt);
		economy.addMarket(enjoymentMkt);
		economy.addMarket(necessityMkt);
		economy.addMarket(capitalMkt);
	}

	/**
	 * Create a bank from <tt>bankConfig</tt>, register it, and return it.
	 */
	public Bank addBank(BankConfig bankConfig) {
		Bank bank = new Bank(bankConfig, economy);
		banks.add(bank);
		economy.addBank(bank);
		return bank;
	}

	/**
	 * Create the capital firm (banking at <tt>capitalFirmBank</tt>) and the
	 * consumer-good firms, then add them to the economy. The bank and initial
	 * savings of each consumer-good firm are supplied by the caller (by index);
	 * everything else comes from the config.
	 */
	public void createFirms(Bank capitalFirmBank, IntFunction<Bank> firmBank,
			IntToDoubleFunction eSavings, IntToDoubleFunction nSavings) {
		CFirm cFirm = new CFirm(cfg.cFirm().checking(), cfg.cFirm().savings(),
				cfg.cFirm().wageBudget(), capitalFirmBank, economy);
		capitalFirms = new CFirm[] { cFirm };

		eFirms = new EFirm[cfg.numEFirms()];
		for (int i = 0; i < cfg.numEFirms(); i++)
			eFirms[i] = new EFirm(cfg.eFirm().checking(),
					eSavings.applyAsDouble(i), cfg.eFirm().output(),
					cfg.eFirm().wageBudget(), cfg.eFirm().capital(),
					capitalFirms, FirmConfig.DEFAULT, firmBank.apply(i), economy);

		nFirms = new NFirm[cfg.numNFirms()];
		for (int i = 0; i < cfg.numNFirms(); i++)
			nFirms[i] = new NFirm(cfg.nFirm().checking(),
					nSavings.applyAsDouble(i), cfg.nFirm().output(),
					cfg.nFirm().wageBudget(), cfg.nFirm().capital(),
					capitalFirms, FirmConfig.DEFAULT, firmBank.apply(i), economy);

		economy.addAgent(cFirm);
		for (NFirm f : nFirms)
			economy.addAgent(f);
		for (EFirm f : eFirms)
			economy.addAgent(f);
	}

	/**
	 * Create the laborers and add them to the economy, then clear the labor
	 * market once so firms have workers before step 0. The bank, initial
	 * necessity stock and initial savings of each laborer are supplied by the
	 * caller (by index).
	 */
	public void createLaborers(IntFunction<Bank> laborerBank,
			IntToDoubleFunction initN, IntToDoubleFunction savings) {
		laborers = new Laborer[cfg.numLaborers()];
		for (int i = 0; i < cfg.numLaborers(); i++) {
			laborers[i] = new Laborer(cfg.laborer().e(), initN.applyAsDouble(i),
					cfg.laborer().checking(), savings.applyAsDouble(i),
					cfg.laborer().savingsRate(), LaborerConfig.DEFAULT,
					laborerBank.apply(i), economy);
			economy.addAgent(laborers[i]);
		}
		laborMkt.clear();
	}

	/** Register the printers common to every simulation. */
	public void addCommonPrinters() {
		int stepSize = cfg.stepSize();
		economy.addPrinter(new LaborersPrinter("Laborer", stepSize, laborers));
		economy.addPrinter(
				new ConsumerMktPricePrinter("EPrice", stepSize, enjoymentMkt));
		economy.addPrinter(
				new ConsumerMktVolPrinter("EVol", stepSize, enjoymentMkt));
		economy.addPrinter(new FirmsPrinter("EFirms", stepSize, eFirms));
		economy.addPrinter(
				new ConsumerMktPricePrinter("NPrice", stepSize, necessityMkt));
		economy.addPrinter(
				new ConsumerMktVolPrinter("NVol", stepSize, necessityMkt));
		economy.addPrinter(new FirmsPrinter("NFirms", stepSize, nFirms));
	}

	/** Register a {@link BankPrinter} writing to <tt>fileName</tt>. */
	public void addBankPrinter(String fileName, Bank bank) {
		economy.addPrinter(new BankPrinter(fileName, cfg.stepSize(), bank));
	}

	/** Run the simulation for the configured number of steps, then clean up. */
	public void run() {
		economy.run(cfg.numStep());
		economy.cleanUpPrinters();
	}

	/** Number of laborers still alive after the run. */
	public long aliveLaborerCount() {
		long n = 0;
		for (Laborer l : laborers)
			if (l.isAlive())
				n++;
		return n;
	}
}
