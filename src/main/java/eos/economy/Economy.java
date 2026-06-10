package eos.economy;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;

import eos.agent.Agent;
import eos.bank.Bank;
import eos.io.printer.Printer;
import eos.market.ConsumerGoodMarket;
import eos.market.Market;
import eos.util.Averager;

/**
 * Economy provides a container to hold all agents and markets together.
 * 
 * @author zhihongx
 * 
 */
public class Economy {

	/****************** constants *****************************/

	/**
	 * time window within which average inflation is computed
	 */
	public static final int INFLATION_TIME_WIN = 100;

	/********************************************************/

	// agents in the economy (who are still alive)
	private static LinkedHashSet<Agent> agents = new LinkedHashSet<Agent>();

	// agents who die in the current step
	private static LinkedHashSet<Agent> deadAgents = new LinkedHashSet<Agent>();

	// symbol table mapping good names to their markets
	private static LinkedHashMap<String, Market> markets = new LinkedHashMap<String, Market>();

	// consumer goods market
	private static LinkedHashSet<ConsumerGoodMarket> consumerGoodMarkets = new LinkedHashSet<ConsumerGoodMarket>();

	// printers
	private static ArrayList<Printer> printers = new ArrayList<Printer>();

	// current time step
	private static int timeStep = 0;

	// CPI in the last step
	private static double lastCPI;

	// inflation in the current step
	private static double inflation;

	// average inflation within <tt>INFLATION_TIME_WIN</tt>
	private static double avgInflation;

	// an averager used to compute average inflation
	private static Averager inflationAvger = new Averager(INFLATION_TIME_WIN);

	/**
	 * Return market corresponding to <tt>good</tt>
	 * 
	 * @param good
	 *            name of a good
	 * @return market corresponding to <tt>good</tt>
	 */
	public static Market getMarket(String good) {
		return markets.get(good);
	}

	/**
	 * Run simulation for <tt>steps</tt> number of steps
	 * 
	 * @param steps
	 */
	public static void run(int steps) {
		for (int i = 0; i < steps; i++) {
			if (i % 1000 == 0)
				System.out.println(i);
			step();
		}
	}

	/**
	 * Return the current time step
	 * 
	 * @return the current time step
	 */
	public static int getTimeStep() {
		return timeStep;
	}

	/**
	 * Run simulation for one step
	 */
	public static void step() {
		for (Agent agent : agents) {
			agent.act();
			if (!agent.isAlive())
				deadAgents.add(agent);
		}

		Bank.act();

		for (Agent agent : deadAgents)
			agents.remove(agent);
		deadAgents.clear();

		for (Market market : markets.values()) {
			market.clear();
		}

		for (Printer printer : printers)
			printer.print();

		updateInflation();
		timeStep++;
	}

	/**
	 * Update inflation value
	 */
	private static void updateInflation() {
		double cpi = 0;
		for (ConsumerGoodMarket mkt : consumerGoodMarkets) {
			cpi += mkt.getLastMktPrice();
		}
		cpi /= consumerGoodMarkets.size();

		if (timeStep == 0) {
			inflation = 0;
			avgInflation = 0;
		} else {
			inflation = (cpi - lastCPI) / lastCPI;
			avgInflation = inflationAvger.update(inflation);
		}
		lastCPI = cpi;
	}

	/**
	 * Return the average inflation within <tt>INFLATION_TIME_WIN</tt>
	 * 
	 * @return the average inflation within <tt>INFLATION_TIME_WIN</tt>
	 */
	public static double getInflation() {
		return avgInflation;
	}

	/**
	 * Return agents who are still alive
	 * 
	 * @return agents who are still alive
	 */
	public static Collection<Agent> getAgents() {
		return agents;
	}

	/**
	 * Add <tt>market</tt> to the economy
	 * 
	 * @param market
	 */
	public static void addMarket(Market market) {
		assert (market != null);
		if (markets.containsKey(market.getGood()))
			throw new RuntimeException("Economy already contains a market for "
					+ market.getGood());
		markets.put(market.getGood(), market);
		if (market instanceof ConsumerGoodMarket)
			consumerGoodMarkets.add((ConsumerGoodMarket) market);
	}

	/**
	 * Add <tt>agent</tt> to the economy
	 * 
	 * @param agent
	 */
	public static void addAgent(Agent agent) {
		agents.add(agent);
	}

	/**
	 * Add <tt>printer</tt>
	 * 
	 * @param printer
	 */
	public static void addPrinter(Printer printer) {
		assert (printer != null);
		printers.add(printer);
		printer.printTitles();
	}

	/**
	 * clean up printers
	 */
	public static void cleanUpPrinters() {
		for (Printer printer : printers)
			printer.cleanup();
	}
}
