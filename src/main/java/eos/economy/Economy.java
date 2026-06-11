package eos.economy;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

import eos.agent.Agent;
import eos.bank.Bank;
import eos.io.printer.Printer;
import eos.market.ConsumerGoodMarket;
import eos.market.Market;
import eos.mortality.Demography;
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

	// sequence number for the next bank's default name in this economy
	private int nextBankNo = 1;

	// in-game date of step 0; each step advances one day
	private final LocalDate startDate;

	// random-number generator (shared with the owning game session)
	@Getter
	private final Rng rng;

	// name sets (shared with the owning game session)
	@Getter
	private final NameRegistry names;

	// demographic service (shared with the owning game session)
	@Getter
	private final Demography demography;

	// mean of the normal distribution from which founding household heads draw
	// their initial age, in years (see Demography.sampleInitialAgeDays)
	@Getter
	private final double meanInitAgeYears;

	// CPI in the last step
	private double lastCPI;

	// inflation in the current step
	private double inflation;

	// average inflation within <tt>INFLATION_TIME_WIN</tt>
	private double avgInflation;

	// an averager used to compute average inflation
	private final Averager inflationAvger = new Averager(INFLATION_TIME_WIN);

	// policy producing a replacement agent when one dies (default: no
	// replacement, so the population shrinks)
	private UnaryOperator<Agent> replacementPolicy = dead -> null;

	// per-step side effects run once each newDay after deaths/replacements
	// settle (e.g. injecting external money into a bank's equity in an open
	// economy); default: none
	private final List<Runnable> stepActions = new ArrayList<Runnable>();

	// policy admitting brand-new households each newDay (e.g. externally-funded
	// immigration), run after the step actions so it sees their effects;
	// default: none
	private Supplier<List<Agent>> immigrationPolicy = () -> List.of();

	// whether the mortality feature is active (aging, old-age death,
	// inheritance); false recovers the pre-mortality behavior
	@Getter
	private boolean mortalityEnabled = true;

	/**
	 * Create a new economy whose step 0 falls on <tt>startDate</tt>, drawing
	 * randomness from <tt>rng</tt>. Each step advances one day. Use {@link
	 * GameSession#newEconomy(LocalDate, double)} to create an economy with a
	 * reproducible random-number seed.
	 *
	 * @param startDate
	 *            the in-game date of step 0
	 * @param rng
	 *            the random-number generator for this economy
	 * @param names
	 *            the name sets for this economy
	 * @param demography
	 *            the demographic service for this economy
	 * @param meanInitAgeYears
	 *            mean initial age (years) of founding household heads
	 */
	public Economy(LocalDate startDate, Rng rng, NameRegistry names,
			Demography demography, double meanInitAgeYears) {
		this.startDate = startDate;
		this.rng = rng;
		this.names = names;
		this.demography = demography;
		this.meanInitAgeYears = meanInitAgeYears;
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
	 * Return the next sequence number for a bank's default name in this economy
	 * (1, 2, ...), so banks are numbered independently per economy.
	 *
	 * @return the next bank sequence number
	 */
	public int nextBankNumber() {
		return nextBankNo++;
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

		// remove the dead and spawn any replacement agents (e.g. a new
		// household when a laborer dies), so the population can stay stable
		ArrayList<Agent> replacements = new ArrayList<Agent>();
		for (Agent agent : deadAgents) {
			agents.remove(agent);
			Agent replacement = replacementPolicy.apply(agent);
			if (replacement != null)
				replacements.add(replacement);
		}
		deadAgents.clear();
		agents.addAll(replacements);

		// run per-step side effects (e.g. external money inflow), then admit any
		// brand-new households (e.g. immigration funded by that inflow); they
		// join this step's labor clearing and first act() next step
		for (Runnable action : stepActions)
			action.run();
		agents.addAll(immigrationPolicy.get());

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
	 * Return the latest consumer price index (the mean of the consumer-good
	 * market prices), as last computed by {@code updateInflation()}.
	 *
	 * @return the latest CPI
	 */
	public double getCPI() {
		return lastCPI;
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
	 * Set the policy that produces a replacement agent when one dies. The
	 * policy receives the dead agent and returns its replacement, or null for
	 * none. Replacements are created and registered each step, right after the
	 * dead are removed.
	 *
	 * @param policy
	 *            the replacement policy
	 */
	public void setReplacementPolicy(UnaryOperator<Agent> policy) {
		this.replacementPolicy = policy;
	}

	/**
	 * Register a side effect to run once each step, after the dead are removed
	 * and replacements admitted but before markets clear. Used for open-economy
	 * effects such as injecting external money into a bank's equity. Multiple
	 * actions run in registration order.
	 *
	 * @param action
	 *            the per-step side effect
	 */
	public void addStepAction(Runnable action) {
		stepActions.add(action);
	}

	/**
	 * Set the policy that admits brand-new households each step (e.g.
	 * externally-funded immigration). The policy returns the new agents to
	 * admit, or an empty list for none. It runs each step after the step
	 * actions, so it sees their effects (e.g. freshly injected equity).
	 *
	 * @param policy
	 *            the immigration policy
	 */
	public void setImmigrationPolicy(Supplier<List<Agent>> policy) {
		this.immigrationPolicy = policy;
	}

	/**
	 * Enable or disable the mortality feature (aging, old-age death and estate
	 * inheritance). When disabled, laborers neither age nor die of old age and
	 * deaths close the account without the bank inheriting it; set this before
	 * constructing agents.
	 *
	 * @param enabled
	 *            whether mortality is active
	 */
	public void setMortalityEnabled(boolean enabled) {
		this.mortalityEnabled = enabled;
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
