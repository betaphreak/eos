package eos.simulation;

import eos.agent.laborer.*;
import eos.agent.firm.CFirm;
import eos.agent.firm.EFirm;
import eos.agent.firm.NFirm;
import eos.agent.firm.FirmConfig;
import eos.bank.Bank;
import eos.bank.BankConfig;
import eos.market.*;
import eos.util.StdRandom;
import eos.economy.*;
import eos.io.SimLog;
import eos.io.printer.*;

/**
 * Simulation (two-bank case).
 * <p>
 * Identical economy to {@link Simulation1}, except it registers <b>two</b>
 * banks and spreads every agent type across both (the capital firm banks at A;
 * enjoyment firms, necessity firms and laborers alternate A/B by index). Wages,
 * purchases and capital payments therefore routinely cross bank boundaries,
 * exercising the agent-routed settlement (each transfer is a
 * {@code withdraw} on the payer's bank plus a {@code credit} on the payee's).
 * <p>
 * Each bank gets its own {@link BankPrinter} ("BankA"/"BankB"), so the two
 * institutions' loan/deposit pools and interest rates can be compared. The two
 * banks set rates independently over their own pools, so this run is <i>not</i>
 * expected to match the single-bank {@link Simulation1}.
 */
public class Simulation3 {

	public static void main(String[] args) {

		// configure step-prefixed logging
		SimLog.init();

		// run configuration (homogeneous, as in Simulation1)
		SimulationConfig cfg = SimulationConfig.DEFAULT;

		// set the seed for the pseudorandom number generator
		StdRandom.setSeed(7654321);

		/* Create and add markets */
		ConsumerGoodMarket eMkt = new ConsumerGoodMarket("Enjoyment",
				cfg.ePrice().min(), cfg.ePrice().max());
		ConsumerGoodMarket nMkt = new ConsumerGoodMarket("Necessity",
				cfg.nPrice().min(), cfg.nPrice().max());
		LaborMarket lMkt = new LaborMarket();
		CapitalMarket cMkt = new CapitalMarket();
		Economy.addMarket(lMkt);
		Economy.addMarket(eMkt);
		Economy.addMarket(nMkt);
		Economy.addMarket(cMkt);

		/* Create and add two banks */
		Bank bankA = new Bank(BankConfig.DEFAULT);
		Bank bankB = new Bank(BankConfig.DEFAULT);
		Economy.addBank(bankA);
		Economy.addBank(bankB);

		/* Create and add firms; the capital firm banks at A */
		CFirm cFirm = new CFirm(cfg.cFirm().checking(), cfg.cFirm().savings(),
				cfg.cFirm().wageBudget(), bankA);
		CFirm[] cFirms = new CFirm[1];
		cFirms[0] = cFirm;

		EFirm[] eFirms = new EFirm[cfg.numEFirms()];
		for (int i = 0; i < cfg.numEFirms(); i++) {
			Bank bank = (i % 2 == 0) ? bankA : bankB;
			eFirms[i] = new EFirm(cfg.eFirm().checking(), cfg.eFirm().savings(),
					cfg.eFirm().output(), cfg.eFirm().wageBudget(),
					cfg.eFirm().capital(), cFirms, FirmConfig.DEFAULT, bank);
		}

		NFirm[] nFirms = new NFirm[cfg.numNFirms()];
		for (int i = 0; i < cfg.numNFirms(); i++) {
			Bank bank = (i % 2 == 0) ? bankA : bankB;
			nFirms[i] = new NFirm(cfg.nFirm().checking(), cfg.nFirm().savings(),
					cfg.nFirm().output(), cfg.nFirm().wageBudget(),
					cfg.nFirm().capital(), cFirms, FirmConfig.DEFAULT, bank);
		}

		Economy.addAgent(cFirm);
		for (int i = 0; i < cfg.numNFirms(); i++)
			Economy.addAgent(nFirms[i]);
		for (int i = 0; i < cfg.numEFirms(); i++)
			Economy.addAgent(eFirms[i]);

		/* Create and add laborers, alternating banks */
		Laborer[] laborers = new Laborer[cfg.numLaborers()];
		for (int i = 0; i < cfg.numLaborers(); i++) {
			Bank bank = (i % 2 == 0) ? bankA : bankB;
			double initN = 15;
			laborers[i] = new Laborer(cfg.laborer().e(), initN,
					cfg.laborer().checking(), cfg.laborer().savings(),
					cfg.laborer().savingsRate(), LaborerConfig.DEFAULT, bank);
			Economy.addAgent(laborers[i]);
		}

		/* clear labor market */
		lMkt.clear();

		/* Create and add printers */
		int stepSize = cfg.stepSize();
		LaborersPrinter laborersPrt = new LaborersPrinter("Laborer", stepSize,
				laborers);
		Economy.addPrinter(laborersPrt);

		ConsumerMktPricePrinter ePricePrt = new ConsumerMktPricePrinter(
				"EPrice", stepSize, eMkt);
		Economy.addPrinter(ePricePrt);
		ConsumerMktVolPrinter eVolPrt = new ConsumerMktVolPrinter("EVol",
				stepSize, eMkt);
		Economy.addPrinter(eVolPrt);
		FirmsPrinter eFirmsPrt = new FirmsPrinter("EFirms", stepSize, eFirms);
		Economy.addPrinter(eFirmsPrt);

		ConsumerMktPricePrinter nPricePrt = new ConsumerMktPricePrinter(
				"NPrice", stepSize, nMkt);
		Economy.addPrinter(nPricePrt);
		ConsumerMktVolPrinter nVolPrt = new ConsumerMktVolPrinter("NVol",
				stepSize, nMkt);
		Economy.addPrinter(nVolPrt);
		FirmsPrinter nFirmsPrt = new FirmsPrinter("NFirms", stepSize, nFirms);
		Economy.addPrinter(nFirmsPrt);

		// one bank printer per bank
		BankPrinter bankAPrt = new BankPrinter("BankA", stepSize, bankA);
		Economy.addPrinter(bankAPrt);
		BankPrinter bankBPrt = new BankPrinter("BankB", stepSize, bankB);
		Economy.addPrinter(bankBPrt);

		/* Run simulation */
		Economy.run(cfg.numStep());
		Economy.cleanUpPrinters();
	}
}
