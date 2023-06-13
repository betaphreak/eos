package eos.simulation;

import eos.agent.laborer.*;
import eos.agent.firm.CFirm;
import eos.agent.firm.EFirm;
import eos.agent.firm.NFirm;
import eos.market.*;
import eos.util.StdRandom;
import eos.economy.*;
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

	/*************** constants **********************************/

	// number of steps between two printer outputs
	private static final int STEP_SIZE = 50;

	// number of steps to run
	private static final int NUM_STEP = 10000;

	private static final int NUM_LABORERS = 450;
	private static final int NUM_EFIRMS = 10;
	private static final int NUM_NFIRMS = 10;

	private static final double MIN_INIT_E_PRICE = 0.1;
	private static final double MAX_INIT_E_PRICE = 5;
	private static final double MIN_INIT_N_PRICE = 0.1;
	private static final double MAX_INIT_N_PRICE = 5;

	private static final double EFIRM_INIT_CHECKING = 100;
	private static final double EFIRM_INIT_SAVINGS = -1000;
	private static final double EFIRM_INIT_OUTPUT = 40;
	private static final double EFIRM_INIT_WAGEBUDGET = 100;
	private static final int EFIRM_INIT_CAPITAL = 30;

	private static final double NFIRM_INIT_CHECKING = 100;
	private static final double NFIRM_INIT_SAVINGS = -1000;
	private static final double NFIRM_INIT_OUTPUT = 50;
	private static final double NFIRM_INIT_WAGEBUDGET = 100;
	private static final int NFIRM_INIT_CAPITAL = 30;

	private static final double CFIRM_INIT_WAGEBUDGET = 500;
	private static final double CFIRM_INIT_CHECKING = CFIRM_INIT_WAGEBUDGET;
	private static final double CFIRM_INIT_SAVINGS = 0;

	private static final double LABORER_INIT_E = 0;
	private static final double LABORER_INIT_CHECKING = 0;
	private static final double LABORER_INIT_SAVINGS = 100;
	private static final double LABORER_INIT_SAVINGS_RATE = 0.9;

	/************************************************************/

	public static void main(String[] args) {

		// set the seed for the pseudorandom number generator
		StdRandom.setSeed(2345);

		/* Create and add markets */
		ConsumerGoodMarket eMkt = new ConsumerGoodMarket("Enjoyment",
				MIN_INIT_E_PRICE, MAX_INIT_E_PRICE);
		ConsumerGoodMarket nMkt = new ConsumerGoodMarket("Necessity",
				MIN_INIT_N_PRICE, MAX_INIT_N_PRICE);
		LaborMarket lMkt = new LaborMarket();
		CapitalMarket cMkt = new CapitalMarket();
		Economy.addMarket(lMkt);
		Economy.addMarket(eMkt);
		Economy.addMarket(nMkt);
		Economy.addMarket(cMkt);

		/* Create and add firms */
		CFirm cFirm = new CFirm(CFIRM_INIT_CHECKING, CFIRM_INIT_SAVINGS,
				CFIRM_INIT_WAGEBUDGET);
		CFirm[] cFirms = new CFirm[1];
		cFirms[0] = cFirm;

		EFirm[] eFirms = new EFirm[NUM_EFIRMS];
		for (int i = 0; i < NUM_EFIRMS; i++) {
			double initSavings = StdRandom.uniform(EFIRM_INIT_SAVINGS * 1.1,
					EFIRM_INIT_SAVINGS * 0.9);
			eFirms[i] = new EFirm(EFIRM_INIT_CHECKING, initSavings,
					EFIRM_INIT_OUTPUT, EFIRM_INIT_WAGEBUDGET,
					EFIRM_INIT_CAPITAL, cFirms);
		}

		NFirm[] nFirms = new NFirm[NUM_NFIRMS];
		for (int i = 0; i < NUM_NFIRMS; i++) {
			double initSavings = StdRandom.uniform(NFIRM_INIT_SAVINGS * 1.1,
					NFIRM_INIT_SAVINGS * 0.9);
			nFirms[i] = new NFirm(NFIRM_INIT_CHECKING, initSavings,
					NFIRM_INIT_OUTPUT, NFIRM_INIT_WAGEBUDGET,
					NFIRM_INIT_CAPITAL, cFirms);
		}

		Economy.addAgent(cFirm);
		for (int i = 0; i < NUM_NFIRMS; i++)
			Economy.addAgent(nFirms[i]);
		for (int i = 0; i < NUM_EFIRMS; i++)
			Economy.addAgent(eFirms[i]);

		/* Create and add laborers */
		Laborer[] laborers = new Laborer[NUM_LABORERS];
		for (int i = 0; i < NUM_LABORERS; i++) {
			double initN = StdRandom.gaussian(15, 3);
			double initSavings = StdRandom.uniform(LABORER_INIT_SAVINGS * 0.9,
					LABORER_INIT_SAVINGS * 1.1);
			laborers[i] = new Laborer(LABORER_INIT_E, initN,
					LABORER_INIT_CHECKING, initSavings,
					LABORER_INIT_SAVINGS_RATE);
			Economy.addAgent(laborers[i]);
		}

		/* clear labor market */
		lMkt.clear();

		/* Create and add printers */
		LaborersPrinter laborersPrt = new LaborersPrinter("Laborer", STEP_SIZE,
				laborers);
		Economy.addPrinter(laborersPrt);

		ConsumerMktPricePrinter ePricePrt = new ConsumerMktPricePrinter(
				"EPrice", STEP_SIZE, eMkt);
		Economy.addPrinter(ePricePrt);
		ConsumerMktVolPrinter eVolPrt = new ConsumerMktVolPrinter("EVol",
				STEP_SIZE, eMkt);
		Economy.addPrinter(eVolPrt);
		FirmsPrinter eFirmsPrt = new FirmsPrinter("EFirms", STEP_SIZE, eFirms);
		Economy.addPrinter(eFirmsPrt);

		ConsumerMktPricePrinter nPricePrt = new ConsumerMktPricePrinter(
				"NPrice", STEP_SIZE, nMkt);
		Economy.addPrinter(nPricePrt);
		ConsumerMktVolPrinter nVolPrt = new ConsumerMktVolPrinter("NVol",
				STEP_SIZE, nMkt);
		Economy.addPrinter(nVolPrt);
		FirmsPrinter nFirmsPrt = new FirmsPrinter("NFirms", STEP_SIZE, nFirms);
		Economy.addPrinter(nFirmsPrt);

		BankPrinter bankPrt = new BankPrinter("Bank", STEP_SIZE);
		Economy.addPrinter(bankPrt);

		/* Run simulation */
		Economy.run(NUM_STEP);
		Economy.cleanUpPrinters();
	}
}
