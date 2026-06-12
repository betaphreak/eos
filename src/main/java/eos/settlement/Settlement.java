package eos.settlement;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

import eos.agent.Agent;
import eos.agent.Household;
import eos.agent.firm.StrategicFirm;
import eos.agent.laborer.Laborer;
import eos.agent.noble.Noble;
import eos.agent.ruler.Ruler;
import eos.bank.Bank;
import eos.bank.CurrencyType;
import eos.io.printer.Printer;
import eos.market.ConsumerGoodMarket;
import eos.market.Market;
import eos.mortality.Demography;
import eos.name.NameRegistry;
import eos.util.Averager;
import eos.util.Rng;
import lombok.Getter;
import lombok.extern.java.Log;

/**
 * Settlement provides a container to hold all agents and markets together. It is
 * an instance (no longer a static singleton): a {@link GameSession} owns the
 * random-number seed and creates colonies from it, so several independent
 * colonies may coexist in one JVM. Agents, banks and markets hold a reference
 * to the colony they belong to; printers receive it in {@link
 * Printer#print(Settlement)}.
 *
 * @author zhihongx
 *
 */
@Log
public class Settlement {

	/****************** constants *****************************/

	/**
	 * time window within which average inflation is computed
	 */
	public static final int INFLATION_TIME_WIN = 100;

	/********************************************************/

	// banks in the colony
	private final LinkedHashSet<Bank> banks = new LinkedHashSet<Bank>();

	// agents in the colony (who are still alive)
	private final LinkedHashSet<Agent> agents = new LinkedHashSet<Agent>();

	// agents who die in the current step
	private final LinkedHashSet<Agent> deadAgents = new LinkedHashSet<Agent>();

	// the colony's persons of interest: every noble and every notable household
	// (skill above the threshold). Registered at creation, removed on death; their
	// names and statistics are logged at the start of each year (see
	// logPersonsOfInterest) instead of logging every individual death.
	private final LinkedHashSet<Household> personsOfInterest = new LinkedHashSet<Household>();

	// symbol table mapping good names to their markets
	private final LinkedHashMap<String, Market> markets = new LinkedHashMap<String, Market>();

	// consumer goods market
	private final LinkedHashSet<ConsumerGoodMarket> consumerGoodMarkets = new LinkedHashSet<ConsumerGoodMarket>();

	// printers
	private final ArrayList<Printer> printers = new ArrayList<Printer>();

	// current time step
	private int timeStep = 0;

	// ID for the next agent created in this colony
	private int nextAvailableID = 1;

	// sequence number for the next bank's default name in this colony
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

	// target necessity stock every laborer in this colony tries to accumulate
	// (in real units): a colony-wide environmental constant, not a per-laborer
	// preference. A laborer directs its consumption budget toward necessity in
	// proportion to how far its stock sits below this target (see Laborer.act).
	@Getter
	private final double targetNStock;

	// mean of this colony's household skill distribution, fixed at colony start:
	// the center of the spread from which every household (founding and successor)
	// draws its skill, hence the colony's labor productivity (see Demography)
	@Getter
	private final double meanSkill;

	// lifecycle: a colony is "started" once it begins running (start()) and
	// "dies" the step its last laborer is gone (no workforce left). deathDate
	// records when, and the transition is terminal.
	private boolean started = false;
	private boolean died = false;
	@Getter
	private LocalDate deathDate;

	// the colony's single export firm, if any (see StrategicFirm). At most one
	// per colony; set via setStrategicFirm, which guards against a second.
	@Getter
	private StrategicFirm strategicFirm;

	// CPI in the last step
	private double lastCPI;

	// inflation in the current step
	private double inflation;

	// average inflation within <tt>INFLATION_TIME_WIN</tt>
	private double avgInflation;

	// an averager used to compute average inflation
	private final Averager inflationAvger = new Averager(INFLATION_TIME_WIN);

	// policies producing a replacement agent when one dies, tried in registration
	// order until one returns non-null (default: none, so the population shrinks).
	// A list so independent populations — laborer households, noble households —
	// can each register their own successor rule without overwriting the others.
	private final List<UnaryOperator<Agent>> replacementPolicies = new ArrayList<UnaryOperator<Agent>>();

	// per-step side effects run once each newDay after deaths/replacements
	// settle (e.g. injecting external money into a bank's equity in an open
	// colony); default: none
	private final List<Runnable> stepActions = new ArrayList<Runnable>();

	// policy admitting brand-new households each newDay (e.g. externally-funded
	// immigration), run after the step actions so it sees their effects;
	// default: none
	private Supplier<List<Agent>> immigrationPolicy = () -> List.of();

	/**
	 * Create a new colony whose step 0 falls on <tt>startDate</tt>, drawing
	 * randomness from <tt>rng</tt>. Each step advances one day. Use {@link
	 * GameSession#newSettlement(LocalDate, double, double)} to create a colony
	 * with a reproducible random-number seed.
	 *
	 * @param startDate
	 *            the in-game date of step 0
	 * @param rng
	 *            the random-number generator for this colony
	 * @param names
	 *            the name sets for this colony
	 * @param demography
	 *            the demographic service for this colony
	 * @param meanInitAgeYears
	 *            mean initial age (years) of founding household heads
	 * @param targetNStock
	 *            target necessity stock every laborer tries to accumulate
	 * @param meanSkill
	 *            mean of this colony's household skill distribution
	 */
	public Settlement(LocalDate startDate, Rng rng, NameRegistry names,
			Demography demography, double meanInitAgeYears, double targetNStock,
			double meanSkill) {
		this.startDate = startDate;
		this.rng = rng;
		this.names = names;
		this.demography = demography;
		this.meanInitAgeYears = meanInitAgeYears;
		this.targetNStock = targetNStock;
		this.meanSkill = meanSkill;
	}

	/**
	 * Begin the colony's life: mark it started (founded and populated), so that
	 * later losing all its laborers counts as the colony <b>dying</b> rather than
	 * never having lived. Logs the founding once. Called at the start of {@link
	 * #run(int)}; idempotent.
	 */
	public void start() {
		if (started)
			return;
		started = true;
		log.info("The colony was founded on " + getDate() + ".");
	}

	/**
	 * Whether the colony is alive: it has started and has not died.
	 *
	 * @return true if the colony is alive
	 */
	public boolean isAlive() {
		return started && !died;
	}

	/**
	 * Whether the colony has died — a started colony that lost its last laborer
	 * (it has no workforce left). The transition is terminal.
	 *
	 * @return true if the colony has died
	 */
	public boolean isDead() {
		return died;
	}

	// number of living laborer households in this colony (the workforce)
	private long livingLaborerCount() {
		long n = 0;
		for (Agent agent : agents)
			if (agent instanceof Laborer)
				n++;
		return n;
	}

	// detect colony death: a started, still-living colony that has lost its last
	// laborer dies now (terminal). Called each newDay once the population settles.
	void updateLifecycle() {
		if (started && !died && livingLaborerCount() == 0) {
			died = true;
			deathDate = getDate();
			log.info("The colony died on " + deathDate
					+ " (its last laborer is gone)");
		}
	}

	/**
	 * Return a fresh unique agent ID within this colony (also used as the
	 * agent's bank account number). IDs are per-colony, so two colonies have
	 * independent ID spaces.
	 *
	 * @return a fresh unique agent ID
	 */
	public int nextAgentID() {
		return nextAvailableID++;
	}

	/**
	 * Return the next sequence number for a bank's default name in this colony
	 * (1, 2, ...), so banks are numbered independently per colony.
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
		start();
		for (int i = 0; i < steps; i++) {
			LocalDate date = getDate();
			if (date.getMonthValue() == 1 && date.getDayOfMonth() == 1)
				System.out.println(date);
			newDay();
		}
	}

	/**
	 * Convert <tt>amount</tt> from currency <tt>from</tt> into currency
	 * <tt>to</tt> at the colony's <b>fixed exchange rate</b> (see {@link
	 * CurrencyType}). All internal accounting is in copper (the base unit prices
	 * are quoted in); this converts amounts specified in another currency and is
	 * used by the printers to display a bank's balances in its own currency.
	 *
	 * @param amount
	 *            an amount in currency <tt>from</tt>
	 * @param from
	 *            the source currency
	 * @param to
	 *            the target currency
	 * @return the equivalent amount in currency <tt>to</tt>
	 */
	public double convert(double amount, CurrencyType from, CurrencyType to) {
		return CurrencyType.convert(amount, from, to);
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
			// a dead person of interest leaves the roster (a successor, if any,
			// registers itself afresh in its constructor); log its passing once —
			// the only per-death logging the colony does
			if (agent instanceof Household h && personsOfInterest.remove(h)) {
				String role = h instanceof Noble ? "noble"
						: h instanceof Ruler ? "ruler" : "notable laborer";
				log.info(h.getHead().fullName() + " (" + role + ", skill "
						+ h.getSkill() + ") died at age " + h.getAgeYears());
			}
			Agent replacement = null;
			for (UnaryOperator<Agent> policy : replacementPolicies) {
				replacement = policy.apply(agent);
				if (replacement != null)
					break;
			}
			if (replacement != null) {
				replacements.add(replacement);
			} else if (agent instanceof Household h) {
				// no successor: this dynasty is extinct, so recycle its surname
				// back into the session-wide pool (successors keep the surname,
				// so they never reach here and the name stays in use)
				names.releaseDynastyName(h.getHead().surname());
			}
		}
		deadAgents.clear();
		agents.addAll(replacements);

		// run per-step side effects (e.g. external money inflow), then admit any
		// brand-new households (e.g. immigration funded by that inflow); they
		// join this step's labor clearing and first act() next step
		for (Runnable action : stepActions)
			action.run();
		agents.addAll(immigrationPolicy.get());

		// the population for this step is now settled: a started colony that has
		// lost its last laborer dies here
		updateLifecycle();

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
	 * Add <tt>market</tt> to the colony
	 *
	 * @param market
	 */
	public void addMarket(Market market) {
		assert (market != null);
		if (markets.containsKey(market.getGood()))
			throw new RuntimeException("Settlement already contains a market for "
					+ market.getGood());
		markets.put(market.getGood(), market);
		if (market instanceof ConsumerGoodMarket)
			consumerGoodMarkets.add((ConsumerGoodMarket) market);
	}

	/**
	 * Add <tt>bank</tt> to the colony
	 *
	 * @param bank
	 */
	public void addBank(Bank bank) {
		assert (bank != null);
		banks.add(bank);
	}

	/**
	 * Add <tt>agent</tt> to the colony
	 *
	 * @param agent
	 */
	public void addAgent(Agent agent) {
		agents.add(agent);
	}

	/**
	 * Register a <b>person of interest</b> — a noble or a notable household — so
	 * the colony can log its name and statistics in the yearly roster. Households
	 * register themselves at creation; the entry is removed when the household
	 * dies. Idempotent (a set).
	 *
	 * @param household
	 *            the noble or notable household to track
	 */
	public void addPersonOfInterest(Household household) {
		personsOfInterest.add(household);
	}

	/**
	 * Return the colony's persons of interest — its living nobles and notable
	 * households. A {@link eos.io.printer.PersonsOfInterestPrinter} writes their
	 * names and statistics to CSV once a year.
	 *
	 * @return the persons of interest
	 */
	public Collection<Household> getPersonsOfInterest() {
		return personsOfInterest;
	}

	/**
	 * Register the colony's single export firm. A colony has at most one {@link
	 * StrategicFirm}; this throws if one is already registered. Registration only
	 * records the firm (and enforces uniqueness) — the firm must still be added to
	 * the colony via {@link #addAgent(Agent)} like any other agent to take part in
	 * the step loop.
	 *
	 * @param firm
	 *            the colony's export firm
	 * @throws IllegalStateException
	 *             if the colony already has a strategic firm
	 */
	public void setStrategicFirm(StrategicFirm firm) {
		if (strategicFirm != null)
			throw new IllegalStateException(
					"Settlement already has a StrategicFirm");
		strategicFirm = firm;
	}

	/**
	 * Register a policy that produces a replacement agent when one dies. The
	 * policy receives the dead agent and returns its replacement, or null if it
	 * does not handle that agent. Registered policies are tried in registration
	 * order until one returns non-null, so independent populations (e.g. laborer
	 * and noble households) can each register their own successor rule.
	 * Replacements are created and registered each step, right after the dead are
	 * removed.
	 *
	 * @param policy
	 *            the replacement policy
	 */
	public void addReplacementPolicy(UnaryOperator<Agent> policy) {
		replacementPolicies.add(policy);
	}

	/**
	 * Register a side effect to run once each step, after the dead are removed
	 * and replacements admitted but before markets clear. Used for open-colony
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
