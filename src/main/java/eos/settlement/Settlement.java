package eos.settlement;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

import eos.agent.Agent;
import eos.agent.Household;
import eos.agent.Member;
import eos.calendar.DayType;
import eos.calendar.LiturgicalCalendar;
import eos.agent.firm.BuilderConfig;
import eos.agent.firm.BuilderFirm;
import eos.agent.firm.StrategicFirm;
import eos.agent.ruler.Ruler;
import eos.bank.Bank;
import eos.bank.CurrencyType;
import eos.io.printer.Printer;
import eos.market.ConsumerGoodMarket;
import eos.market.Market;
import eos.mortality.Demography;
import eos.name.Gender;
import eos.name.NameRegistry;
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

	// the settlement's name (e.g. "Lübeck Altstadt"); a display label, fixed at
	// colony start
	@Getter
	private final String name;

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

	// the precalculated slot table (shared with the owning game session): a
	// size -> SlotInfo lookup the colony reads its current geometry from
	private final SlotTable slotTable;

	// the liturgical calendar (shared with the owning game session): classifies
	// the current in-game date as a workday/weekend/holiday. A pure date lookup,
	// independent of seed and location. See getDayType.
	private final LiturgicalCalendar liturgicalCalendar;

	// the colony's current size (disc radius). Founded at SlotTable.MIN_SIZE and
	// grows upward as it needs more slots (see claimSlot); the slot table maps it
	// to the total/road/wall/effective counts.
	private int size;

	// the colony's effective build slots (occupied and vacant), one per effective
	// slot at the current size, in a fixed order. Rebuilt — extended — whenever the
	// size grows; an occupant keeps its slot. Today only firms occupy slots.
	private final List<Slot> slots = new ArrayList<Slot>();

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

	// mean of this colony's skill distribution, fixed at colony start: the center
	// of the spread from which a person draws its skill, hence the colony's labor
	// productivity (see Demography). Split by gender — males average meanSkillMale,
	// females meanSkillFemale — so the gendered skill gap is a colony-start
	// property; see getMeanSkill(Gender).
	@Getter
	private final double meanSkillMale;
	@Getter
	private final double meanSkillFemale;

	// the colony's geographic location in decimal degrees (north/east positive),
	// fixed at colony start. Used for daylight calculations (see getSunrise): the
	// SolarEventCalculator turns this location plus the current in-game date into
	// sunrise/sunset times.
	@Getter
	private final double latitude;
	@Getter
	private final double longitude;

	// the colony's solar clock for its (fixed) location: computes the day's
	// dawn/sunrise/sunset/dusk and daylight length, refreshed for the current
	// in-game date at the top of every newDay (and seeded in the constructor).
	// See getDawn/getSunrise/getSunset/getDusk/getDaylightHours, which delegate
	// to it.
	private final SolarClock solarClock;

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

	// the colony's builder, if any (see BuilderFirm). At most one per colony; set
	// via setBuilder. Once a colony is live (started), this is the *only* way it
	// can grow: claimSlot routes a slot demand it cannot satisfy through the
	// builder. A colony without one cannot grow during the run.
	@Getter
	private BuilderFirm builder;

	// the colony's sovereign, if any (see Ruler). Recorded so the builder can bill
	// it for the public works (roads and walls) of a growth ring.
	@Getter
	private Ruler ruler;

	// outstanding construction tasks the builder is working through, in the order
	// rings were demanded (lowest ring first). Empty unless a live colony has
	// outgrown its slots; see claimSlot / requestGrowth / completeFinishedRings.
	private final List<BuildProject> buildQueue = new ArrayList<BuildProject>();

	// occupants that demanded a slot a live colony could not yet supply: they are
	// seated as the builder finishes the rings being built for them.
	private final List<Agent> pendingOccupants = new ArrayList<Agent>();

	// tracks the colony's CPI and inflation, recomputed once per newDay. Reads
	// the live consumerGoodMarkets set, so markets added later are included.
	private final InflationTracker inflationTracker = new InflationTracker(consumerGoodMarkets);

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
	 * Create a new colony named <tt>name</tt> whose step 0 falls on
	 * <tt>startDate</tt>, drawing randomness from <tt>rng</tt>. Each step advances
	 * one day. Use {@link GameSession#newSettlement(String, LocalDate, double,
	 * double, double, double, double)} to create a colony with a reproducible
	 * random-number seed.
	 *
	 * @param name
	 *            the settlement's name (a display label)
	 * @param startDate
	 *            the in-game date of step 0
	 * @param rng
	 *            the random-number generator for this colony
	 * @param names
	 *            the name sets for this colony
	 * @param demography
	 *            the demographic service for this colony
	 * @param slotTable
	 *            the precalculated slot table (shared across the session)
	 * @param liturgicalCalendar
	 *            the liturgical calendar (shared across the session)
	 * @param meanInitAgeYears
	 *            mean initial age (years) of founding household heads
	 * @param targetNStock
	 *            target necessity stock every laborer tries to accumulate
	 * @param meanSkillMale
	 *            mean of this colony's male skill distribution
	 * @param meanSkillFemale
	 *            mean of this colony's female skill distribution
	 * @param latitude
	 *            the colony's geographic latitude in decimal degrees (north positive)
	 * @param longitude
	 *            the colony's geographic longitude in decimal degrees (east positive)
	 */
	public Settlement(String name, LocalDate startDate, Rng rng,
			NameRegistry names, Demography demography, SlotTable slotTable,
			LiturgicalCalendar liturgicalCalendar, double meanInitAgeYears,
			double targetNStock, double meanSkillMale, double meanSkillFemale,
			double latitude, double longitude) {
		this.name = name;
		this.startDate = startDate;
		this.rng = rng;
		this.names = names;
		this.demography = demography;
		this.slotTable = slotTable;
		this.liturgicalCalendar = liturgicalCalendar;
		this.meanInitAgeYears = meanInitAgeYears;
		this.targetNStock = targetNStock;
		this.meanSkillMale = meanSkillMale;
		this.meanSkillFemale = meanSkillFemale;
		this.latitude = latitude;
		this.longitude = longitude;
		this.solarClock = new SolarClock(latitude, longitude);
		// every household knows how to produce its own heir; register one built-in
		// policy that asks each dead household to do so, tried before any the
		// simulation adds. Self-replacing types (nobles, the ruler) are succeeded
		// automatically with no per-simulation wiring; a household that needs
		// colony-level seeding the colony does not hold (a laborer's replacement
		// stock) returns null here and is covered by a policy the harness registers.
		addReplacementPolicy(dead -> dead instanceof Household h
				? h.successor(this) : null);
		// found the colony at the floor size, building its initial effective slots
		setSize(SlotTable.MIN_SIZE);
		// seed the starting day's solar times so they are valid before the first
		// newDay (e.g. for inspection at step 0); newDay recomputes them each day
		updateSolarTimes();
	}

	/**
	 * The mean skill of the colony's distribution for a person of the given
	 * {@code gender} — the center of the spread {@code Demography} draws its skills
	 * around. Males average {@link #getMeanSkillMale()}, females {@link
	 * #getMeanSkillFemale()}.
	 *
	 * @param gender
	 *            the person's gender
	 * @return the colony's mean skill for that gender
	 */
	public double getMeanSkill(Gender gender) {
		return gender == Gender.FEMALE ? meanSkillFemale : meanSkillMale;
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
		log.info(name + " was founded on " + getDate() + ".");
	}

	/**
	 * Whether the colony has been started (its life has begun via {@link
	 * #start()}). Distinct from {@link #isAlive()}: a started colony that has
	 * since died is no longer alive but is still started. Used to apply the
	 * operating calendar only to a live colony — the pre-run seeding (before
	 * {@code start()}) hires every firm so step 0 has a workforce regardless of
	 * what kind of day it falls on.
	 *
	 * @return true once {@link #start()} has been called
	 */
	public boolean isStarted() {
		return started;
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

	// number of living workforce households in this colony (the laborers whose
	// labor sustains the colony; see Household.isWorkforce)
	private long livingLaborerCount() {
		long n = 0;
		for (Agent agent : agents)
			if (agent instanceof Household h && h.isWorkforce())
				n++;
		return n;
	}

	// detect colony death: a started, still-living colony that has lost its last
	// laborer dies now (terminal). Called each newDay once the population settles.
	void updateLifecycle() {
		if (started && !died && livingLaborerCount() == 0) {
			died = true;
			deathDate = getDate();
			log.info(name + " died on " + deathDate
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
	 * The colony's current size (its disc radius). Colonies are founded at {@link
	 * SlotTable#MIN_SIZE} and grow upward as they need more slots.
	 *
	 * @return the current size
	 */
	public int getSize() {
		return size;
	}

	/**
	 * The slot geometry (total/road/wall/effective counts and unlocked special
	 * sites) at the colony's current {@link #getSize() size}.
	 *
	 * @return the current slot info
	 */
	public SlotInfo getSlotInfo() {
		return slotTable.forSize(size);
	}

	/**
	 * The colony's effective build slots — occupied and vacant — in a fixed
	 * order, as an unmodifiable view. Its length is {@code getSlotInfo().effective()}.
	 *
	 * @return the colony's slots
	 */
	public List<Slot> getSlots() {
		return Collections.unmodifiableList(slots);
	}

	/**
	 * Set the colony's size to <tt>newSize</tt>, extending its effective-slot list
	 * to match (new slots are vacant; existing slots and their occupants are kept).
	 * Used to <b>grow</b> the colony; shrinking below the number of slots already
	 * in existence is unsupported (it would orphan occupants). In the colony's
	 * normal operating range effective slots increase with size, so growth only
	 * appends.
	 *
	 * @param newSize
	 *            the new size, in {@code [0, slotTable.maxSize()]}
	 * @throws IllegalStateException
	 *             if the new size would have fewer effective slots than already exist
	 */
	public void setSize(int newSize) {
		int newEffective = slotTable.forSize(newSize).effective();
		if (newEffective < slots.size())
			throw new IllegalStateException(name + " cannot shrink from "
					+ slots.size() + " to " + newEffective + " effective slots");
		this.size = newSize;
		while (slots.size() < newEffective)
			slots.add(new Slot());
	}

	/**
	 * Place <tt>occupant</tt> on a vacant effective slot. How the colony makes room
	 * when none is free depends on its lifecycle:
	 * <ul>
	 * <li><b>At founding</b> (before {@link #start()}) it lays out its initial
	 * footprint, growing one size at a time until the occupant fits — a one-time
	 * genesis sizing, not live growth — and returns the slot it took.</li>
	 * <li><b>While live</b> (after {@code start()}) it does <em>not</em> grow
	 * itself: the only way a running colony gets bigger is through its {@link
	 * BuilderFirm}. The demand is queued for the builder (firm-funded land plus
	 * ruler-funded roads and walls; see {@link #requestGrowth(Agent)}), the
	 * occupant is held pending, and this returns {@code null} — the occupant is
	 * seated once the builder finishes the ring. A live colony with no builder
	 * cannot grow, so this throws.</li>
	 * </ul>
	 * Either way, slot placement moves no money and consumes no randomness.
	 *
	 * @param occupant
	 *            the occupant to place (today, a firm)
	 * @return the slot it was placed on, or {@code null} if a live colony has
	 *         queued the demand for its builder to build
	 * @throws IllegalStateException
	 *             if the colony cannot make room (full at max size while founding,
	 *             or full with no builder while live)
	 */
	public Slot claimSlot(Agent occupant) {
		Slot slot = firstVacantSlot();
		if (slot != null) {
			slot.occupy(occupant);
			return slot;
		}
		if (started)
			return requestBuild(occupant);
		return foundOnto(occupant);
	}

	// founding (pre-run genesis): extend the colony's initial footprint one size at
	// a time until the occupant fits, and seat it. Not the live-growth path.
	private Slot foundOnto(Agent occupant) {
		Slot slot = null;
		while (slot == null && size < slotTable.maxSize()) {
			setSize(size + 1);
			slot = firstVacantSlot();
		}
		if (slot == null)
			throw new IllegalStateException(name
					+ " cannot seat " + occupant + " even at its maximum size "
					+ size);
		slot.occupy(occupant);
		return slot;
	}

	// live colony: only the builder can make room. Queue the next ring's work and
	// hold the occupant pending; it is seated when the builder finishes the ring.
	private Slot requestBuild(Agent occupant) {
		if (builder == null)
			throw new IllegalStateException(name
					+ " is full and has no builder to grow it for " + occupant);
		requestGrowth(occupant);
		pendingOccupants.add(occupant);
		return null;
	}

	// the first vacant slot, or null if every effective slot is occupied
	private Slot firstVacantSlot() {
		for (Slot slot : slots)
			if (slot.isVacant())
				return slot;
		return null;
	}

	/**
	 * Register the colony's builder (the firm that grows a live colony). A colony
	 * has at most one; this throws if one is already registered. The {@link
	 * BuilderFirm} constructor calls this — the firm must still be added via {@link
	 * #addAgent(Agent)} to take part in the step loop, like any other agent.
	 *
	 * @param builder
	 *            the colony's builder
	 * @throws IllegalStateException
	 *             if the colony already has a builder
	 */
	public void setBuilder(BuilderFirm builder) {
		if (this.builder != null)
			throw new IllegalStateException("Settlement already has a builder");
		this.builder = builder;
	}

	/**
	 * Record the colony's sovereign, so the builder can bill it for public works
	 * (the roads and walls of each growth ring). Set by the harness when it creates
	 * the default ruler. Storing the reference moves no money and is a no-op for any
	 * colony that never grows, so it leaves runs byte-identical.
	 *
	 * @param ruler
	 *            the colony's ruler
	 */
	public void setRuler(Ruler ruler) {
		this.ruler = ruler;
	}

	/**
	 * The colony's current sovereign (the founding ruler or the heir who has since
	 * succeeded it), or {@code null} if the colony has no ruler.
	 *
	 * @return the colony's ruler, or {@code null}
	 */
	public Ruler getRuler() {
		return ruler;
	}

	// queue construction work for the next ring (size -> size+1) on behalf of one
	// occupant. The occupant funds the LAND for the single slot it will stand on
	// (so each firm pays its own land clearance); the ring's ROAD and WALL public
	// works are queued once, on the ruler's account. The wall work is scaled by the
	// wall build-speed factor (walls go up fast while the colony is small, slower as
	// its circumference grows). The sponsor stored for the public works is the
	// current ruler; the builder bills whoever is ruler when it does the work, so a
	// succession mid-build does not strand the task on a closed account.
	private void requestGrowth(Agent requester) {
		int next = size + 1;
		if (next > slotTable.maxSize())
			throw new IllegalStateException(
					name + " cannot grow past its maximum size " + size);
		BuilderConfig c = builder.getConfig();

		// the requester funds its own slot's land
		buildQueue.add(new BuildProject(next, BuildProject.Kind.LAND,
				c.landWorkPerSlot(), requester));

		// queue the ring's public works once (the ruler funds roads and walls)
		if (!ringHasPublicWorks(next)) {
			if (ruler == null)
				throw new IllegalStateException(name
						+ " has no ruler to fund the public works of its growth");
			SlotInfo cur = slotTable.forSize(size);
			SlotInfo nxt = slotTable.forSize(next);
			int dRoad = nxt.road() - cur.road();
			int dWall = nxt.wall() - cur.wall();
			// wallBuildTimePercent is a build-*speed* percentage (100% = parity);
			// invert it to a work multiplier (a faster wall costs less work)
			double wallFactor = 100.0 / Math.max(1e-9, nxt.wallBuildTimePercent());
			buildQueue.add(new BuildProject(next, BuildProject.Kind.ROAD,
					dRoad * c.roadWorkPerSlot(), ruler));
			buildQueue.add(new BuildProject(next, BuildProject.Kind.WALL,
					dWall * c.wallWorkPerSlot() * wallFactor, ruler));
		}
	}

	// whether the ring of the given size already has its road/wall tasks queued
	private boolean ringHasPublicWorks(int ringSize) {
		for (BuildProject p : buildQueue)
			if (p.getRingSize() == ringSize
					&& p.getKind() != BuildProject.Kind.LAND)
				return true;
		return false;
	}

	/**
	 * The unfinished construction tasks of the ring the builder is currently
	 * building — the lowest-numbered ring still in the queue. The {@link
	 * BuilderFirm} applies its build-units to these each step. Empty when there is
	 * nothing to build.
	 *
	 * @return the active ring's outstanding tasks (an empty list when idle)
	 */
	public List<BuildProject> activeProjects() {
		int next = size + 1;
		List<BuildProject> active = new ArrayList<BuildProject>();
		for (BuildProject p : buildQueue)
			if (p.getRingSize() == next && !p.isComplete())
				active.add(p);
		return active;
	}

	/**
	 * If the ring being built is fully done — its land, road and wall tasks all
	 * complete — grow the colony into it and seat the occupants that were waiting on
	 * it. Called by the {@link BuilderFirm} each step after it applies its work. A
	 * no-op until a ring finishes.
	 */
	public void completeFinishedRings() {
		int next = size + 1;
		boolean hasRing = false;
		for (BuildProject p : buildQueue) {
			if (p.getRingSize() != next)
				continue;
			hasRing = true;
			if (!p.isComplete())
				return; // this ring is not finished yet
		}
		if (!hasRing)
			return;
		buildQueue.removeIf(p -> p.getRingSize() == next);
		setSize(next); // appends the ring's now-built effective slots
		placePending();
	}

	// seat the waiting occupants onto newly-built slots; if any still do not fit,
	// queue the next ring for them (funded by the first one still waiting)
	private void placePending() {
		java.util.Iterator<Agent> it = pendingOccupants.iterator();
		while (it.hasNext()) {
			Slot slot = firstVacantSlot();
			if (slot == null)
				break;
			slot.occupy(it.next());
			it.remove();
		}
		if (!pendingOccupants.isEmpty())
			requestGrowth(pendingOccupants.get(0));
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
		// stop early once the colony has died (its last laborer is gone): a dead
		// colony produces nothing more of interest, so running on only burns compute
		for (int i = 0; i < steps && !died; i++) {
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
	 * The {@link DayType} of the current in-game date — whether today is an
	 * ordinary workday, the weekly day of rest (Sunday), or a liturgical feast
	 * day. Delegates to the colony's shared {@link LiturgicalCalendar}.
	 * <p>
	 * This is presently a <em>tag</em> only: nothing in the economy reads it yet
	 * (wiring labor output to the day type is a later step).
	 *
	 * @return the current date's day type
	 */
	public DayType getDayType() {
		return liturgicalCalendar.dayType(getDate());
	}

	/**
	 * The {@link DayType} of an arbitrary date under this colony's calendar.
	 *
	 * @param date
	 *            the date to classify
	 * @return that date's day type
	 */
	public DayType getDayType(LocalDate date) {
		return liturgicalCalendar.dayType(date);
	}

	/** The colony's liturgical calendar (shared across the session). */
	public LiturgicalCalendar getLiturgicalCalendar() {
		return liturgicalCalendar;
	}

	/**
	 * Recompute the day's solar times — {@link #getDawn() dawn}, {@link
	 * #getSunrise() sunrise}, {@link #getSunset() sunset} and {@link #getDusk()
	 * dusk} — for the current in-game date at the colony's location. Called at the
	 * top of every {@link #newDay()} (and once from the constructor to seed the
	 * starting day). Delegates to the colony's {@link SolarClock}.
	 */
	private void updateSolarTimes() {
		solarClock.update(getDate());
	}

	/**
	 * The current day's astronomical dawn (UTC), or null when there is no
	 * astronomical twilight on this date (e.g. midsummer at high latitude).
	 */
	public LocalTime getDawn() {
		return solarClock.getDawn();
	}

	/** The current day's official sunrise (UTC). */
	public LocalTime getSunrise() {
		return solarClock.getSunrise();
	}

	/** The current day's official sunset (UTC). */
	public LocalTime getSunset() {
		return solarClock.getSunset();
	}

	/**
	 * The current day's astronomical dusk (UTC), or null when there is no
	 * astronomical twilight on this date (e.g. midsummer at high latitude).
	 */
	public LocalTime getDusk() {
		return solarClock.getDusk();
	}

	/**
	 * Hours of daylight (sunrise to sunset) on the current in-game date; NaN when
	 * sunrise/sunset are undefined (polar day/night).
	 */
	public double getDaylightHours() {
		return solarClock.getDaylightHours();
	}


	/**
	 * Advance the simulation by one day (one step).
	 */
	public void newDay() {
		// compute this day's dawn/sunrise/sunset/dusk before agents act, so they
		// can read the day's daylight when they act
		updateSolarTimes();

		for (Agent agent : agents) {
			agent.act();
			if (!agent.isAlive()) {
				deadAgents.add(agent);
			} else if (agent instanceof Household h) {
				// age each living household member's skills once per day: decay
				// ("forgetting") runs whether or not they worked this step
				for (Member member : h.getMembers())
					member.skills().tick();
			}
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
				log.info(h.getHead().fullName() + " ("
						+ h.role().toLowerCase(Locale.ROOT) + ", "
						+ h.getHead().skills() + ") died at age " + h.getAgeYears());
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
	 * Recompute the colony's CPI and inflation for this step (delegates to the
	 * {@link InflationTracker}).
	 */
	private void updateInflation() {
		inflationTracker.update(timeStep == 0);
	}

	/**
	 * Return the average inflation within <tt>INFLATION_TIME_WIN</tt>
	 *
	 * @return the average inflation within <tt>INFLATION_TIME_WIN</tt>
	 */
	public double getInflation() {
		return inflationTracker.getAvgInflation();
	}

	/**
	 * Return the latest consumer price index (the mean of the consumer-good
	 * market prices), as last computed by {@code updateInflation()}.
	 *
	 * @return the latest CPI
	 */
	public double getCPI() {
		return inflationTracker.getCPI();
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
	 * Return the colony's banks (insertion-ordered), e.g. for the Ruler to tax
	 * each bank's distributable profit.
	 *
	 * @return the colony's banks
	 */
	public Collection<Bank> getBanks() {
		return banks;
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
