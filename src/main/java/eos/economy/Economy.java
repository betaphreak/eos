package eos.economy;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;

import eos.agent.Agent;
import eos.bank.Bank;
import eos.io.printer.Printer;
import eos.market.ConsumerGoodMarket;
import eos.market.Market;
import eos.name.NameRegistry;
import eos.util.Averager;
import eos.util.Rng;
import lombok.Getter;

/**
 * Economy provides a container to hold all agents and markets together. It is
 * an instance (no longer a static singleton): a {@link GameSession} owns the
 * random-number seed and creates economies from it, so several independent
 * economies may coexist in one JVM. Agents, banks and markets hold a reference
 * to the economy they belong to; printers receive it in {@link
 * Printer#print(Economy)}.
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

	// banks in the economy
	private final LinkedHashSet<Bank> banks = new LinkedHashSet<Bank>();

	// agents in the economy (who are still alive)
	private final LinkedHashSet<Agent> agents = new LinkedHashSet<Agent>();

	// agents who die in the current step
	private final LinkedHashSet<Agent> deadAgents = new LinkedHashSet<Agent>();

	// symbol table mapping good names to their markets
	private final LinkedHashMap<String, Market> markets = new LinkedHashMap<String, Market>();

	// consumer goods market
	private final LinkedHashSet<ConsumerGoodMarket> consumerGoodMarkets = new LinkedHashSet<ConsumerGoodMarket>();

	// printers
	private final ArrayList<Printer> printers = new ArrayList<Printer>();

	// current time step
	private int timeStep = 0;

	// ID for the next agent created in this economy
	private int nextAvailableID = 1;

	// in-game date of step 0; each step advances one day
	private final LocalDate startDate;

	// random-number generator (shared with the owning game session)
	@Getter
	private final Rng rng;

	// name sets (shared with the owning game session)
	@Getter
	private final NameRegistry names;

	// CPI in the last step
	private double lastCPI;

	// inflation in the current step
	private double inflation;

	// average inflation within <tt>INFLATION_TIME_WIN</tt>
	private double avgInflation;

	// an averager used to compute average inflation
	private final Averager inflationAvger = new Averager(INFLATION_TIME_WIN);

	/**
	 * Create a new economy whose step 0 falls on <tt>startDate</tt>, drawing
	 * randomness from <tt>rng</tt>. Each step advances one day. Use {@link
	 * GameSession#newEconomy(LocalDate)} to create an economy with a
	 * reproducible random-number seed.
	 *
	 * @param startDate
	 *            the in-game date of step 0
	 * @param rng
	 *            the random-number generator for this economy
	 * @param names
	 *            the name sets for this economy
	 */
	public Economy(LocalDate startDate, Rng rng, NameRegistry names) {
		this.startDate = startDate;
		this.rng = rng;
		this.names = names;
	}

	/**
	 * Return a fresh unique agent ID within this economy (also used as the
	 * agent's bank account number). IDs are per-economy, so two economies have
	 * independent ID spaces.
	 *
	 * @return a fresh unique agent ID
	 */
	public int nextAgentID() {
		return nextAvailableID++;
	}

	/**
	 * Return market corresponding to <tt>good</tt>
	 *
	 * @param good
	 *            name of a good
	 * @return market corresponding to <tt>good</tt>
	 */
	public Market getMarket(String good) {
		return markets.get(good);
	}

	/**
	 * Run simulation for <tt>steps</tt> number of steps
	 *
	 * @param steps
	 */
	public void run(int steps) {
		for (int i = 0; i < steps; i++) {
			LocalDate date = getDate();
			if (date.getMonthValue() == 1 && date.getDayOfMonth() == 1)
				System.out.println(date);
			newDay();
		}
	}

	/**
	 * Return the current time step
	 *
	 * @return the current time step
	 */
	public int getTimeStep() {
		return timeStep;
	}

	/**
	 * Return the current in-game date (the date of step 0 plus the current
	 * time step in days).
	 *
	 * @return the current in-game date
	 */
	public LocalDate getDate() {
		return startDate.plusDays(timeStep);
	}

	/**
	 * Advance the simulation by one day (one step).
	 */
	public void newDay() {
		for (Agent agent : agents) {
			agent.act();
			if (!agent.isAlive())
				deadAgents.add(agent);
		}

		for (Bank bank : banks)
			bank.act();

		for (Agent agent : deadAgents)
			agents.remove(agent);
		deadAgents.clear();

		for (Market market : markets.values()) {
			market.clear();
		}

		for (Printer printer : printers)
			printer.print(this);

		updateInflation();
		timeStep++;
	}

	/**
	 * Update inflation value
	 */
	private void updateInflation() {
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
	public double getInflation() {
		return avgInflation;
	}

	/**
	 * Return agents who are still alive
	 *
	 * @return agents who are still alive
	 */
	public Collection<Agent> getAgents() {
		return agents;
	}

	/**
	 * Add <tt>market</tt> to the economy
	 *
	 * @param market
	 */
	public void addMarket(Market market) {
		assert (market != null);
		if (markets.containsKey(market.getGood()))
			throw new RuntimeException("Economy already contains a market for "
					+ market.getGood());
		markets.put(market.getGood(), market);
		if (market instanceof ConsumerGoodMarket)
			consumerGoodMarkets.add((ConsumerGoodMarket) market);
	}

	/**
	 * Add <tt>bank</tt> to the economy
	 *
	 * @param bank
	 */
	public void addBank(Bank bank) {
		assert (bank != null);
		banks.add(bank);
	}

	/**
	 * Add <tt>agent</tt> to the economy
	 *
	 * @param agent
	 */
	public void addAgent(Agent agent) {
		agents.add(agent);
	}

	/**
	 * Add <tt>printer</tt>
	 *
	 * @param printer
	 */
	public void addPrinter(Printer printer) {
		assert (printer != null);
		printers.add(printer);
		printer.printTitles();
	}

	/**
	 * clean up printers
	 */
	public void cleanUpPrinters() {
		for (Printer printer : printers)
			printer.cleanup();
	}
}
