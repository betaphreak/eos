package eos.simulation;

import eos.agent.laborer.*;
import eos.agent.firm.CFirm;
import eos.agent.firm.EFirm;
import eos.agent.firm.NFirm;
import eos.agent.firm.FirmConfig;
import eos.market.*;
import eos.util.StdRandom;
import eos.economy.*;
import eos.io.SimLog;
import eos.io.printer.*;

/**
 * Simulation (heterogeneous case)
 *
 * @author zhihongx
 *
 */

/**
 * <b>Guidelines for creating a simulation</b>
 * <p>
 * Please follow the steps below. The order is important.
 * <p>
 * 1. Create the markets and add them into the economy. The markets need to be
 * created first because the constructors of the agents reference the markets.
 * Here is an example of creating a labor market and adding it to the economy.
 * <p>
 * <tt>LaborMarket lMkt = new LaborMarket();<br>
 * Economy.addMarket(lMkt);</tt>
 * <p>
 * 2. Create the firms. Capital firms need to be created before other firms,
 * because the constructors of consumer goods firms require reference to an
 * array of capital firms.
 * <p>
 * 3. Add the firms to the economy. For instance, here is how to add the
 * necessity firms:
 * <p>
 * <tt>for (int i = 0; i < NUM_NFIRMS; i++) Economy.addAgent(nFirms[i]);</tt>
 * <p>
 * 4. Create laborers and add them to the economy.
 * <p>
 * 5. Clear the labor market by calling <tt>lMkt.clear()</tt>. This is to allow
 * firms to get labor before the start of the first time step.
 * <p>
 * 6. Create printers and add them to the economy. Several printers have been
 * provided in <tt>eos.io.printer</tt>, but feel free to create more customized
 * printers.
 * <p>
 * 7. Run the simulation by calling <tt>Economy.run(NUM_STEP)</tt>.
 * <p>
 * 8. Clean up the printers by calling <tt>Economy.cleanUpPrinters()</tt>
 * <p>
 */
public class Simulation2 {

	public static void main(String[] args) {

		// configure step-prefixed logging
		SimLog.init();

		// run configuration; this simulation perturbs the initial state of
		// each agent around these values to create a heterogeneous population
		SimulationConfig cfg = SimulationConfig.DEFAULT;

		// set the seed for the pseudorandom number generator
		StdRandom.setSeed(2345);

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

		/* Create and add firms */
		CFirm cFirm = new CFirm(cfg.cFirm().checking(), cfg.cFirm().savings(),
				cfg.cFirm().wageBudget());
		CFirm[] cFirms = new CFirm[1];
		cFirms[0] = cFirm;

		EFirm[] eFirms = new EFirm[cfg.numEFirms()];
		for (int i = 0; i < cfg.numEFirms(); i++) {
			double initSavings = StdRandom.uniform(cfg.eFirm().savings() * 1.1,
					cfg.eFirm().savings() * 0.9);
			eFirms[i] = new EFirm(cfg.eFirm().checking(), initSavings,
					cfg.eFirm().output(), cfg.eFirm().wageBudget(),
					cfg.eFirm().capital(), cFirms, FirmConfig.DEFAULT);
		}

		NFirm[] nFirms = new NFirm[cfg.numNFirms()];
		for (int i = 0; i < cfg.numNFirms(); i++) {
			double initSavings = StdRandom.uniform(cfg.nFirm().savings() * 1.1,
					cfg.nFirm().savings() * 0.9);
			nFirms[i] = new NFirm(cfg.nFirm().checking(), initSavings,
					cfg.nFirm().output(), cfg.nFirm().wageBudget(),
					cfg.nFirm().capital(), cFirms, FirmConfig.DEFAULT);
		}

		Economy.addAgent(cFirm);
		for (int i = 0; i < cfg.numNFirms(); i++)
			Economy.addAgent(nFirms[i]);
		for (int i = 0; i < cfg.numEFirms(); i++)
			Economy.addAgent(eFirms[i]);

		/* Create and add laborers */
		Laborer[] laborers = new Laborer[cfg.numLaborers()];
		for (int i = 0; i < cfg.numLaborers(); i++) {
			double initN = StdRandom.gaussian(15, 3);
			double initSavings = StdRandom.uniform(cfg.laborer().savings() * 0.9,
					cfg.laborer().savings() * 1.1);
			laborers[i] = new Laborer(cfg.laborer().e(), initN,
					cfg.laborer().checking(), initSavings,
					cfg.laborer().savingsRate(), LaborerConfig.DEFAULT);
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

		BankPrinter bankPrt = new BankPrinter("Bank", stepSize);
		Economy.addPrinter(bankPrt);

		/* Run simulation */
		Economy.run(cfg.numStep());
		Economy.cleanUpPrinters();
	}
}
