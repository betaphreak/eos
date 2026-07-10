package com.civstudio.settlement;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

import com.civstudio.agent.Agent;
import com.civstudio.agent.Granary;
import com.civstudio.agent.MigrantCaravan;
import com.civstudio.geo.Province;
import com.civstudio.geo.TerrainRegistry;
import com.civstudio.agent.Household;
import com.civstudio.agent.Member;
import com.civstudio.agent.Retinue;
import com.civstudio.calendar.DayType;
import com.civstudio.calendar.LiturgicalCalendar;
import com.civstudio.agent.firm.BuilderFirm;
import com.civstudio.agent.firm.Firm;
import com.civstudio.agent.firm.FirmFactory;
import com.civstudio.agent.firm.StrategicFirm;
import com.civstudio.agent.ruler.Ruler;
import com.civstudio.tech.ResearchState;
import com.civstudio.bank.Bank;
import com.civstudio.bank.CurrencyType;
import com.civstudio.io.printer.Printer;
import com.civstudio.io.sink.CsvRowSinkFactory;
import com.civstudio.io.sink.RowSinkFactory;
import com.civstudio.market.ConsumerGoodMarket;
import com.civstudio.market.Market;
import com.civstudio.mortality.Demography;
import com.civstudio.name.Gender;
import com.civstudio.name.NameRegistry;
import com.civstudio.race.Race;
import com.civstudio.tech.Sector;
import com.civstudio.tech.TechEffect;
import com.civstudio.util.Rng;
import com.civstudio.agent.laborer.FertilityConfig;
import lombok.Getter;
import lombok.Setter;
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

	/**
	 * Workforce floor below which a ruler-bearing colony <b>dissolves into a
	 * Caravan</b> (the {@code HOLDING → CARAVAN} hinge — see {@code docs/caravan.md}):
	 * once fewer than this many laborer households remain, the colony's settled life
	 * ends and its survivors depart as a wandering band. Kept {@code > 0} so survivors
	 * always remain to form a viable band. An <b>uncalibrated placeholder</b> (the
	 * per-rung floors are a deferred design question).
	 */
	public static final int DISSOLUTION_WORKFORCE_FLOOR = 10;

	/**
	 * The fewest plots a province must have for a colony to be founded into it — the
	 * minimum viable settlement footprint (and the threshold a {@link MigrantCaravan}
	 * judges a site by). Matches the disc model's old founding floor: a size-3 disc's
	 * total plot footprint (so a province too small to hold the floor is rejected
	 * exactly as before). See {@code docs/plots.md}.
	 */
	public static final int MIN_FOUNDING_PLOTS = 28;

	/**
	 * The fixed working day, in hours, a sunless underground ({@link
	 * com.civstudio.geo.ProvinceType#CAVERN cavern}) colony runs on in place of solar
	 * daylight — the lamplit "sweatshop" schedule. It is longer than the surface
	 * full-output reference ({@code LaborMarket.FULL_OUTPUT_DAYLIGHT_HOURS = 8}), so
	 * underground labor is correspondingly more productive per worker. See {@code
	 * docs/underworld.md}.
	 */
	public static final double CAVERN_WORK_HOURS = 14.0;

	// the fixed "lights on" time of the underground working day; chosen so sunrise +
	// CAVERN_WORK_HOURS stays within the day (05:00 + 14h = 19:00, no midnight wrap)
	private static final LocalTime CAVERN_SUNRISE = LocalTime.of(5, 0);

	/**
	 * The plot-count ceiling for a colony founded at <b>bare coordinates</b> (no
	 * province): an effectively-unbounded cap, since such analytical colonies never
	 * approach it (they collapse first). A province-founded colony is capped at its
	 * {@link Province#plots()} instead. See {@code docs/plots.md}.
	 */
	public static final int PROVINCE_LESS_PLOT_CAP = 4096;

	/********************************************************/

	// banks in the colony
	private final LinkedHashSet<Bank> banks = new LinkedHashSet<Bank>();

	// agents in the colony (who are still alive)
	private final LinkedHashSet<Agent> agents = new LinkedHashSet<Agent>();

	// agents who die in the current step
	private final LinkedHashSet<Agent> deadAgents = new LinkedHashSet<Agent>();

	// the colony's persons of interest: every noble and every notable household
	// (skill above the threshold). Registered at creation, removed on death; this
	// roster confines death logging to them (a POI's death is logged at FINE, an
	// ordinary laborer's not) and feeds the annual digest's POI-death count.
	private final LinkedHashSet<Household> personsOfInterest = new LinkedHashSet<Household>();

	// symbol table mapping good names to their markets
	private final LinkedHashMap<String, Market> markets = new LinkedHashMap<String, Market>();

	// consumer goods market
	private final LinkedHashSet<ConsumerGoodMarket> consumerGoodMarkets = new LinkedHashSet<ConsumerGoodMarket>();

	// printers
	private final ArrayList<Printer> printers = new ArrayList<Printer>();

	// the factory that creates the sink each printer writes to; defaults to CSV
	// (so plain main() runs and tests are unaffected), overridden by a launcher
	// that wants database persistence
	private RowSinkFactory sinkFactory = new CsvRowSinkFactory();

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

	// the colony's founding race — its ruler's race (default HUMAN). Used *only* to
	// pick the colony-wide mechanics that have no per-person form: the liturgical
	// calendar and the tech effect overlay (see docs/race.md). Individuals vary
	// freely per person via raceMix; this is the single race those colony-wide
	// systems key off.
	@Getter
	private final Race foundingRace;

	// the race-mix weights every *generated* person is rolled against (pool seeding,
	// founding draws, immigration), on the demographic RNG (see Demography.sampleRace).
	// Default {HUMAN: 1.0} — a degenerate distribution that skips the roll entirely,
	// so a mono-cultural colony draws no extra randomness and stays byte-identical.
	// Inherited and married-in people keep their own line's race (not rolled).
	@Getter
	private final Map<Race, Double> raceMix;

	// the colony's spatial subsystem — its build plots, the shared province plot pool
	// it claims from, terrain generation and the builder's clearance queue. Extracted
	// from this class (see PlotField); the plot API below delegates to it. Assigned in
	// the constructor (it needs the terrain/province inputs).
	private final PlotField plotField;

	// the liturgical calendar (shared with the owning game session): classifies
	// the current in-game date as a workday/weekend/holiday. A pure date lookup,
	// independent of seed and location. See getDayType.
	private final LiturgicalCalendar liturgicalCalendar;

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

	// the colony's fertility parameters — when a married household bears a child (see
	// docs/births.md). A colony-wide demographic property read live each step by every
	// household type (Laborer/Noble/Ruler) via AbstractHousehold.bearChildIfFertile;
	// settable so a run can tune or disable births. FertilityConfig.DEFAULT enables
	// births (non-zero dailyBirthProb); a run sets dailyBirthProb 0 to suppress them.
	@Getter
	@Setter
	private FertilityConfig fertilityConfig = FertilityConfig.DEFAULT;

	// the colony's technology state — the per-sector productivity multipliers and the
	// capability tokens researched tech effects accumulate. Extracted (see TechState);
	// the tech API below delegates to it. Neutral (all 1.0, no tokens) until a tech
	// effect is applied.
	private final TechState techState = new TechState();

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

	// the province this colony was founded into, or null if it was founded at bare
	// coordinates (every scenario that does not opt into the world map). When set, it
	// is the source of the colony's latitude/longitude and bounds its growth (the
	// plot field's maxPlots). Carried for the dependent geography features
	// (caravan/founding), and read for the agriculture climate multiplier.
	@Getter
	private final Province province;

	// the colony's solar clock for its (fixed) location: computes the day's
	// dawn/sunrise/sunset/dusk and daylight length, refreshed for the current
	// in-game date at the top of every newDay (and seeded in the constructor).
	// See getDawn/getSunrise/getSunset/getDusk/getDaylightHours, which delegate
	// to it.
	private final SolarClock solarClock;

	// the colony's lifecycle — started/died/dissolution state and the transition into
	// a wandering band when its workforce drains. Extracted (see SettlementLifecycle);
	// the lifecycle API below delegates to it.
	private final SettlementLifecycle lifecycle = new SettlementLifecycle(this);

	// persons of interest who died since the last annual digest, summarized and reset
	// once a year by logAnnualDigest (the per-death log itself is FINE, off by default)
	private int poiDeathsThisYear = 0;

	// the session that owns this colony, set by GameSession.newSettlement; a colony
	// constructed directly (some tests) has none, and then a dissolved band is held
	// only on the colony (getDepartedBand), not registered session-wide. Package
	// private: only GameSession sets it.
	private GameSession session;

	// the colony's research progress (the tech tree it is climbing), or null when
	// research is disabled — e.g. a bare colony with no export sector to fuel it. Set
	// by the harness when it enables research (see SimulationHarness). Fed by the
	// strategic sector's intellectual labor and reviewed monthly by the ruler.
	@Getter
	private ResearchState research;

	// the colony's single export firm, if any (see StrategicFirm). At most one
	// per colony; set via setStrategicFirm, which guards against a second.
	@Getter
	private StrategicFirm strategicFirm;

	// the colony's builder, if any (see BuilderFirm). At most one per colony; set
	// via setBuilder. Once a colony is live (started), this is the *only* way it
	// can grow: claimPlot routes a plot demand it cannot satisfy through the
	// builder. A colony without one cannot grow during the run.
	@Getter
	private BuilderFirm builder;

	// the colony's sovereign, if any (see Ruler). Recorded for succession and taxation.
	@Getter
	private Ruler ruler;

	// the colony's ever-normal granary, if any (see Granary). Recorded so relief
	// holders — the peasant pool, and later children — can draw their ration from the
	// colony's strategic food store rather than each bidding the necessity market (see
	// docs/granary.md §4). At most one per colony; null leaves relief on the market.
	@Getter
	private Granary granary;

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

	// firms chartered / dissolved mid-step by the ruler's dynamic firm provisioning,
	// applied at the end of newDay (see applyScheduledAgentChanges) so the agent set
	// is never mutated while it is being iterated in the act phase.
	private final List<Agent> agentsToAdd = new ArrayList<Agent>();
	private final List<Agent> agentsToRemove = new ArrayList<Agent>();

	// arbitrary actions deferred to the end of newDay (after this step's market
	// clearing), e.g. ennobling a laborer to own a freshly chartered firm — which
	// must wait until the laborer's offers have cleared before its account can move.
	private final List<Runnable> endOfStepActions = new ArrayList<Runnable>();

	// the colony's dynamic firm provisioning service, if installed (see FirmFactory
	// and the ruler's monthly sector review); null leaves the firm count fixed.
	@Getter
	private FirmFactory firmFactory;

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
	 * @param terrainRegistry
	 *            the curated terrain/feature/improvement definitions (shared across
	 *            the session)
	 * @param terrainRng
	 *            the dedicated terrain-generation random stream (salted apart from
	 *            the economic one)
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
			NameRegistry names, Demography demography, TerrainRegistry terrainRegistry,
			Rng terrainRng, LiturgicalCalendar liturgicalCalendar,
			double meanInitAgeYears, double targetNStock, double meanSkillMale,
			double meanSkillFemale, double latitude, double longitude) {
		this(name, startDate, rng, names, demography, terrainRegistry, terrainRng,
				liturgicalCalendar, meanInitAgeYears, targetNStock, meanSkillMale,
				meanSkillFemale, latitude, longitude, Race.HUMAN,
				Map.of(Race.HUMAN, 1.0), null);
	}

	/**
	 * Create a colony as {@link #Settlement(String, LocalDate, Rng, NameRegistry,
	 * Demography, TerrainRegistry, Rng, LiturgicalCalendar, double, double, double,
	 * double, double, double)} but with an explicit {@link Race founding race} and
	 * per-person race-mix (see {@code docs/race.md}). The other overload defaults
	 * both to human, so a mono-cultural colony is unaffected.
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
	 * @param terrainRegistry
	 *            the curated terrain/feature/improvement definitions (shared across
	 *            the session)
	 * @param terrainRng
	 *            the dedicated terrain-generation random stream
	 * @param liturgicalCalendar
	 *            the founding race's liturgical calendar (shared across the session)
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
	 * @param foundingRace
	 *            the colony's founding (ruler's) race
	 * @param raceMix
	 *            race &rarr; weight every generated person is rolled against
	 */
	public Settlement(String name, LocalDate startDate, Rng rng,
			NameRegistry names, Demography demography, TerrainRegistry terrainRegistry,
			Rng terrainRng, LiturgicalCalendar liturgicalCalendar,
			double meanInitAgeYears, double targetNStock, double meanSkillMale,
			double meanSkillFemale, double latitude, double longitude,
			Race foundingRace, Map<Race, Double> raceMix, Province province) {
		this.name = name;
		this.startDate = startDate;
		this.rng = rng;
		this.names = names;
		this.demography = demography;
		this.foundingRace = foundingRace;
		this.raceMix = raceMix;
		this.liturgicalCalendar = liturgicalCalendar;
		this.meanInitAgeYears = meanInitAgeYears;
		this.targetNStock = targetNStock;
		this.meanSkillMale = meanSkillMale;
		this.meanSkillFemale = meanSkillFemale;
		this.latitude = latitude;
		this.longitude = longitude;
		this.province = province;
		// the spatial subsystem: the plots, the shared province pool, terrain generation
		// and the builder's queue (it caps growth at the province's plots, and rejects a
		// province too small to hold the founding floor). See PlotField.
		this.plotField = new PlotField(this, terrainRegistry, terrainRng, province);
		// underground (cavern) colonies have no sun: they run a fixed lamplit work
		// schedule instead of solar daylight (see FixedDaylightClock, docs/underworld.md)
		this.solarClock = (province != null && province.isUnderground())
				? new FixedDaylightClock(latitude, longitude, CAVERN_SUNRISE, CAVERN_WORK_HOURS)
				: new SolarClock(latitude, longitude);
		// every household knows how to produce its own heir; register one built-in
		// policy that asks each dead household to do so, tried before any the
		// simulation adds. Self-replacing types (nobles, the ruler) are succeeded
		// automatically with no per-simulation wiring; a household that needs
		// colony-level seeding the colony does not hold (a laborer's replacement
		// stock) returns null here and is covered by a policy the harness registers.
		addReplacementPolicy(dead -> dead instanceof Household h
				? h.successor(this) : null);
		// the colony starts with no plots; firms are seated (and the plot list grown)
		// on demand as they claim plots — see claimPlot.
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
		lifecycle.start();
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
		return lifecycle.isStarted();
	}

	/**
	 * Whether the colony is alive: it has started and has not died.
	 *
	 * @return true if the colony is alive
	 */
	public boolean isAlive() {
		return lifecycle.isAlive();
	}

	/**
	 * Whether the colony has died — a started colony that lost its last laborer
	 * (it has no workforce left). The transition is terminal.
	 *
	 * @return true if the colony has died
	 */
	public boolean isDead() {
		return lifecycle.isDead();
	}

	/** The date the colony died, or null if it is still alive. */
	public LocalDate getDeathDate() {
		return lifecycle.getDeathDate();
	}

	/** The wandering band a dissolved colony departed as, or null. */
	public MigrantCaravan getDepartedBand() {
		return lifecycle.getDepartedBand();
	}

	// detect the end of the colony's settled life, called each newDay once the
	// population settles (delegates to SettlementLifecycle). A ruler-bearing colony
	// that can form a band flags itself for dissolution once its workforce falls below
	// DISSOLUTION_WORKFORCE_FLOOR; the band departs in finishRun.
	void updateLifecycle() {
		lifecycle.update();
	}

	// a dead colony no longer holds territory: return all its claimed plots to the
	// shared province pool (delegates to the plot field). Called by the lifecycle when
	// the colony dies; a no-op for a province-less colony.
	void releasePlotsToPool() {
		plotField.releasePlotsToPool();
	}

	// set the session that owns this colony (only GameSession calls this, when it
	// creates the colony), so a dissolved band can be registered session-wide.
	void setSession(GameSession session) {
		this.session = session;
		// scope this run's CSV output to output/<seed>/ so a whole session (every
		// colony's files plus the shared event log) lands in one folder. Only when
		// still on the default CSV backend — a launcher that installed its own sink
		// factory keeps it (it carries its own run identity).
		if (session != null && sinkFactory instanceof CsvRowSinkFactory)
			this.sinkFactory = new CsvRowSinkFactory("output/" + session.getSeed());
	}

	/**
	 * The {@link GameSession} that owns this colony (holds the shared name pool,
	 * demography, calendar and tech tree), or {@code null} for a colony constructed
	 * directly without one (some tests).
	 *
	 * @return the owning session, or {@code null}
	 */
	public GameSession getSession() {
		return session;
	}

	/**
	 * Enable research on this colony with the given {@link ResearchState}.
	 * Called by the harness when a colony has the strategic export sector that fuels
	 * research; leaves {@link #getResearch()} non-null thereafter.
	 *
	 * @param research
	 *            the colony's research state
	 */
	public void setResearch(ResearchState research) {
		this.research = research;
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
	 * The hard ceiling on the colony's plot count: its province's plots (build slots
	 * are plots — see {@code docs/plots.md}), or {@link #PROVINCE_LESS_PLOT_CAP} for a
	 * bare-coordinate colony. A colony cannot grow past this.
	 *
	 * @return the maximum plot count
	 */
	public int getMaxPlots() {
		return plotField.getMaxPlots();
	}

	/**
	 * The colony's current plot count — how many build plots it has laid out
	 * (occupied or vacant). Founded with none and grown on demand as firms claim
	 * plots (see {@link #claimPlot}), up to {@link #getMaxPlots()}.
	 *
	 * @return the current plot count
	 */
	public int getPlotCount() {
		return plotField.getPlotCount();
	}

	/**
	 * The colony's build plots — occupied and vacant — in claim order, as an
	 * unmodifiable view.
	 *
	 * @return the colony's plots
	 */
	public List<Plot> getPlots() {
		return plotField.getPlots();
	}

	/**
	 * Place <tt>occupant</tt> on a vacant plot (delegates to the {@link PlotField}).
	 * At founding (before {@link #start()}) it appends a fresh developed plot and
	 * seats the occupant, returning it; while live it queues the plot's clearance for
	 * the {@link BuilderFirm} and returns {@code null} (the occupant is seated once the
	 * plot is built). Plot placement moves no money and consumes no randomness.
	 *
	 * @param occupant
	 *            the occupant to place (today, a firm)
	 * @return the plot it was placed on, or {@code null} if a live colony has queued
	 *         the demand for its builder to build
	 * @throws IllegalStateException
	 *             if the colony cannot make room (full at max plots while founding,
	 *             or full with no builder while live)
	 */
	public Plot claimPlot(PlotOccupant occupant) {
		return plotField.claimPlot(occupant);
	}

	/**
	 * Whether the colony can seat another plot occupant — either a plot is vacant now,
	 * or it can still grow into one (below {@link #getMaxPlots() max plots} and it has
	 * a {@link #setBuilder builder} to do the growing). When {@code false} the dynamic
	 * firm provisioning must not charter another firm — there is nowhere to put it.
	 * Delegates to the {@link PlotField}. See {@code docs/plots.md}.
	 *
	 * @return {@code true} if another occupant could be seated (now or after growth)
	 */
	public boolean hasRoomToExpand() {
		return plotField.hasRoomToExpand();
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
	 * Record the colony's sovereign. Set by the harness when it creates the default
	 * ruler. Storing the reference moves no money, so it leaves runs byte-identical.
	 *
	 * @param ruler
	 *            the colony's ruler
	 */
	public void setRuler(Ruler ruler) {
		this.ruler = ruler;
	}

	/**
	 * Register the colony's {@link Granary ever-normal granary} — its single strategic
	 * food store, which relief holders draw their ration from (see {@code
	 * docs/granary.md} §4). Set by the harness when it creates the default granary; at
	 * most one per colony, so this throws if one is already registered.
	 *
	 * @param granary
	 *            the colony's granary
	 * @throws IllegalStateException
	 *             if the colony already has a granary
	 */
	public void setGranary(Granary granary) {
		if (this.granary != null)
			throw new IllegalStateException("Settlement already has a granary");
		this.granary = granary;
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

	/**
	 * Install the colony's <b>dynamic firm provisioning</b> service: how the ruler's
	 * monthly sector review charters and dissolves consumer-good firms. Absent one,
	 * the review is a no-op and the firm count stays fixed. Set by the harness.
	 *
	 * @param firmFactory
	 *            the provisioning service (see {@link FirmFactory})
	 */
	public void setFirmFactory(FirmFactory firmFactory) {
		this.firmFactory = firmFactory;
	}

	/**
	 * Schedule <tt>agent</tt> to join the colony at the <em>end</em> of the current
	 * step. Safe to call mid-step (e.g. from another agent's {@code act()}), unlike
	 * {@link #addAgent(Agent)} which mutates the live agent set immediately and so
	 * would corrupt the in-progress act-phase iteration. Used to seat a firm the
	 * ruler charters during its own turn.
	 *
	 * @param agent
	 *            the agent to admit at end of step
	 */
	public void scheduleAddAgent(Agent agent) {
		agentsToAdd.add(agent);
	}

	/**
	 * Schedule <tt>agent</tt>'s removal from the colony at the <em>end</em> of the
	 * current step: its plot is freed and its account settled into the bank's equity
	 * (debt absorbed), as for a deceased estate. Deferred so the agent's final
	 * market offers still clear this step and the agent set is not mutated mid-act.
	 *
	 * @param agent
	 *            the agent to remove at end of step
	 */
	public void scheduleRemoveAgent(Agent agent) {
		agentsToRemove.add(agent);
	}

	/**
	 * Schedule <tt>action</tt> to run at the <em>end</em> of the current step, after
	 * this step's market clearing and printing (and so after every agent's offers
	 * have settled). Used for changes that must wait for the market to clear — e.g.
	 * ennobling a laborer, whose account can only be moved once its buy offers have
	 * been paid. The action may itself call {@link #scheduleAddAgent}/{@link
	 * #scheduleRemoveAgent}; those are processed immediately after.
	 *
	 * @param action
	 *            the deferred action
	 */
	public void scheduleEndOfStepAction(Runnable action) {
		endOfStepActions.add(action);
	}

	/**
	 * Free the plot occupied by <tt>occupant</tt> (or drop it from the pending queue
	 * if it was awaiting a plot a growing colony had not yet built). The plot itself
	 * stays in the colony — vacant and ready to be reseated. A no-op if the occupant
	 * holds neither.
	 *
	 * @param occupant
	 *            the occupant whose plot to free
	 */
	public void vacatePlot(PlotOccupant occupant) {
		plotField.vacatePlot(occupant);
	}

	/**
	 * The <b>terrain yield factor</b> the given occupant's plot applies to its TFP in
	 * the given sector — the channel by which land quality varies a firm's
	 * productivity per plot (see {@code docs/plots.md}). Returns the neutral
	 * {@code 1.0} when the coupling does not apply:
	 * <ul>
	 * <li>a <b>province-less</b> colony bypasses the whole plot coupling (its plots
	 * are uniform baseline anyway);</li>
	 * <li>only <b>food</b> ({@link Sector#NECESSITY}) is live this cut — production
	 * and commerce are fully plumbed but dormant until a mine / trading-post firm
	 * actually sits on a plot (Phase 3+);</li>
	 * <li>a <b>center-grouped or not-yet-seated</b> occupant (no plot) is
	 * land-independent.</li>
	 * </ul>
	 * Folded into {@link com.civstudio.agent.firm.ConsumerGoodFirm#effectiveA()} (and
	 * stacked with the retained climate multiplier for {@link
	 * com.civstudio.agent.firm.NFirm}).
	 *
	 * @param occupant the firm whose plot to read
	 * @param sector   the firm's sector
	 * @return the plot's yield factor (1.0 when the coupling does not apply)
	 */
	public double plotYieldFactor(PlotOccupant occupant, Sector sector) {
		return plotField.plotYieldFactor(occupant, sector);
	}

	/**
	 * The <b>round-trip commute</b>, in seconds, a worker pays to reach the given
	 * occupant's plot — {@code 2·T(index)} on the {@link TravelLadder travel-time
	 * ladder}. The labor market folds this into each worker's {@code workFactor}
	 * (see {@link com.civstudio.market.LaborMarket#clear()}). Returns {@code 0} when
	 * the occupant pays no commute: a <b>province-less</b> colony (the whole travel
	 * coupling is bypassed), or a <b>center-grouped / not-yet-seated</b> firm (no
	 * plot — it works in town). See {@code docs/plots.md}.
	 *
	 * @param occupant the firm whose plot commute to read
	 * @return the round-trip commute in seconds (0 when none applies)
	 */
	public double plotTravelTime(PlotOccupant occupant) {
		return plotField.plotTravelTime(occupant);
	}

	/**
	 * The day's <b>work window</b> in seconds — the sunrise→sunset span the labor
	 * market measures the commute and clearing overhead against. Falls back to
	 * {@link #getDaylightHours()}{@code × 3600} when the sunrise/sunset times are
	 * undefined (polar day/night), and to {@code 0} if even that is non-finite.
	 *
	 * @return the work window in seconds
	 */
	public double getWorkWindowSeconds() {
		LocalTime sr = getSunrise();
		LocalTime ss = getSunset();
		if (sr != null && ss != null) {
			double secs = java.time.Duration.between(sr, ss).getSeconds();
			if (secs > 0)
				return secs;
		}
		double h = getDaylightHours();
		return Double.isFinite(h) ? Math.max(0, h) * 3600 : 0;
	}

	/**
	 * The unfinished construction tasks the builder is working through — the plots
	 * still queued to open (delegates to the {@link PlotField}). The {@link BuilderFirm}
	 * applies its build-units to these each step (lowest-index first).
	 *
	 * @return the outstanding plot-prep tasks (an empty list when idle)
	 */
	public List<BuildProject> activeProjects() {
		return plotField.activeProjects();
	}

	/**
	 * Append a plot for each completed clearance task and seat the occupants that
	 * were waiting on them (delegates to the {@link PlotField}). Called by the {@link
	 * BuilderFirm} each step after it applies its work.
	 */
	public void completeFinishedPlots() {
		plotField.completeFinishedPlots();
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
		// stop early once the colony's settled life has ended (workforce drained): a
		// dead colony produces nothing more of interest, so running on only burns compute
		for (int i = 0; i < steps && !lifecycle.isDead(); i++) {
			printAnnualProgress();
			newDay();
		}
		finishRun();
	}

	/**
	 * Run the simulation with <b>no step horizon</b> — until the colony's settled life
	 * ends (its workforce drains and it dies / dissolves into a wandering band). Used
	 * by scenarios and tests that are bounded by collapse rather than an end date (e.g.
	 * the caravan re-found tests); a colony that never dies would loop forever, so this is
	 * only for runs guaranteed to collapse (the closed default config).
	 */
	public void run() {
		start();
		while (!lifecycle.isDead()) {
			printAnnualProgress();
			newDay();
		}
		finishRun();
	}

	/**
	 * Print the current in-game year to stdout on January 1st — a once-a-year
	 * progress tick (kept off the event log, which goes to stderr). Exposed so a
	 * concurrent multi-colony runner can drive the day loop itself yet keep the
	 * same progress output.
	 */
	public void printAnnualProgress() {
		LocalDate date = getDate();
		if (date.getMonthValue() == 1 && date.getDayOfMonth() == 1)
			System.out.println(date);
	}

	// emit a once-a-year summary of the colony to the event log on January 1st: its
	// population, aristocracy, firm count, peasant pool, the persons of interest lost
	// over the year, and the CPI. At INFO it is the compact, always-on alternative to
	// the high-frequency per-event logs (charters, promotions, POI deaths), which are
	// demoted to FINE/FINER — so a run of many colonies can be followed at a glance.
	// Resets the year's counters.
	private void logAnnualDigest() {
		LocalDate date = getDate();
		if (date.getMonthValue() != 1 || date.getDayOfMonth() != 1)
			return;
		int laborers = 0, children = 0, nobles = 0, firms = 0, pool = 0, poolKids = 0;
		for (Agent a : agents) {
			if (!a.isAlive())
				continue;
			if (a instanceof Retinue r) {
				pool += r.size();
				poolKids += r.childCount(date);
			} else if (a instanceof Firm)
				firms++;
			else if (a instanceof Household h) {
				if (h.isWorkforce()) {
					laborers++;
					// home-grown children: non-adult household members (births, see
					// docs/births.md) — counted across the head's whole household
					for (Member m : h.getMembers())
						if (!m.isAdult(date))
							children++;
				} else if ("Noble".equals(h.role()))
					nobles++;
			}
		}
		// pop is the workforce (laborer households); children breaks out the under-age
		// members it does not count (household-born + pool wards), so the digest shows
		// the next generation maturing toward the workforce
		log.info(String.format(
				"annual digest: pop=%d children=%d nobles=%d firms=%d pool=%d poolKids=%d "
						+ "POI deaths=%d CPI=%.1f",
				laborers, children, nobles, firms, pool, poolKids, poiDeathsThisYear,
				getInflation()));
		poiDeathsThisYear = 0;
	}

	/**
	 * Finalize a finished run. A ruler-bearing colony that crossed the workforce
	 * floor departs as a {@link MigrantCaravan} (the survivors take to the road) rather
	 * than simply vanishing — done here, after the last step's clearing/printing,
	 * since the dissolution drains the banks and folds the households (it must not
	 * run mid-step). Exposed so a concurrent runner can call it once its day loop
	 * ends, exactly as {@link #run(int)} does.
	 */
	public void finishRun() {
		lifecycle.finishRun();
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
			// registers itself afresh in its constructor); log its passing once — at
			// FINE (per-death demographic detail, off by default), and count it for the
			// year's digest
			if (agent instanceof Household h && personsOfInterest.remove(h)) {
				poiDeathsThisYear++;
				log.fine(h.getHead().fullName() + " ("
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

		// apply any firms the ruler chartered or dissolved this step (deferred from
		// mid-act so the agent set was not mutated during iteration)
		applyScheduledAgentChanges();

		// a once-a-year summary of the colony at INFO (the per-event chatter it
		// replaces is demoted to FINE/FINER), so a many-colony run stays followable
		logAnnualDigest();

		timeStep++;
	}

	// admit firms chartered this step (active from their constructor already; they
	// now join the step loop and first act() next step) and remove firms dissolved
	// this step (their final offers have cleared; free the plot and settle the
	// account into equity). Run at the end of newDay, after this step's market
	// clearing and printing.
	private void applyScheduledAgentChanges() {
		// run any deferred end-of-step actions first (e.g. ennobling a laborer to own
		// a freshly chartered firm); they may schedule agent adds/removes themselves,
		// which the two loops below then apply
		if (!endOfStepActions.isEmpty()) {
			// copy first, so an action that schedules another does not disturb this pass
			List<Runnable> due = new ArrayList<Runnable>(endOfStepActions);
			endOfStepActions.clear();
			for (Runnable action : due)
				action.run();
		}
		if (!agentsToAdd.isEmpty()) {
			agents.addAll(agentsToAdd);
			agentsToAdd.clear();
		}
		if (!agentsToRemove.isEmpty()) {
			for (Agent a : agentsToRemove) {
				agents.remove(a);
				vacatePlot(a);
				// a Household leaving this way (e.g. a laborer ennobled into a noble)
				// is no longer a person of interest; its successor re-registers itself
				if (a instanceof Household h)
					personsOfInterest.remove(h);
				// fold the agent's net worth into the bank's equity and close its
				// account, as a deceased estate is settled (a no-op if the caller has
				// already closed the account, e.g. a promoted laborer's)
				a.getBank().inheritAndClose(a.getID());
			}
			agentsToRemove.clear();
		}
	}

	/**
	 * The colony's technology multiplier for a given {@link Sector} — the live
	 * total-factor-productivity scaling that researched {@link
	 * TechEffect.SectorProductivity} effects accumulate. Defaults to {@code 1.0} (no
	 * effect applied), and returns {@code 1.0} for a {@code null} sector (a firm
	 * without one, e.g. the builder), so a firm's effective {@code A} equals its
	 * configured {@code A} until a tech raises this.
	 *
	 * @param sector
	 *            the sector to read, or {@code null} for "no sector"
	 * @return the sector's tech multiplier (1.0 if unset or {@code null})
	 */
	public double getTechMultiplier(Sector sector) {
		return techState.multiplier(sector);
	}

	/**
	 * The colony's <b>agricultural climate multiplier</b> — the combined effect of
	 * its founding province's {@link com.civstudio.geo.Climate climate band},
	 * {@link com.civstudio.geo.WinterSeverity winter} and {@link
	 * com.civstudio.geo.Monsoon monsoon} on food (necessity) total-factor
	 * productivity, as the product of their {@code agricultureFactor()}s. The
	 * {@link com.civstudio.agent.firm.NFirm} reads it into its effective {@code A},
	 * so a tropical, monsoon-fed colony grows more food per worker than an arid or
	 * hard-winter one. A colony founded at bare coordinates (no province — the
	 * analytical sims) has no climate, so this is {@code 1.0} and their economics
	 * are unchanged.
	 *
	 * @return the necessity-productivity multiplier (1.0 when there is no province)
	 */
	public double getAgricultureClimateMultiplier() {
		if (province == null)
			return 1.0;
		return province.climate().agricultureFactor()
				* province.winter().agricultureFactor()
				* province.monsoon().agricultureFactor();
	}

	/**
	 * Apply a researched {@link TechEffect} to this colony:
	 * <ul>
	 * <li>a {@link TechEffect.SectorProductivity} multiplies its sector's
	 *     {@linkplain #getTechMultiplier(Sector) tech multiplier} (cumulative);</li>
	 * <li>an {@link TechEffect.Unlock} or {@link TechEffect.SocialGate} records its
	 *     token in {@link #getGrantedTechTokens()} (read by nothing yet — the seam
	 *     for future content / rank / class consumers).</li>
	 * </ul>
	 * Called when a tech completes (the research that drives this is a later phase —
	 * see {@code docs/tech-tree.md}); nothing calls it during a normal run yet, so
	 * runs are unchanged.
	 *
	 * @param effect
	 *            the effect to apply
	 */
	public void applyTechEffect(TechEffect effect) {
		techState.apply(effect);
	}

	/**
	 * The tokens granted by researched {@link TechEffect.Unlock} / {@link
	 * TechEffect.SocialGate} effects (e.g. {@code "GOOD_PAPER"},
	 * {@code "CLASS_BURGHER"}). An unmodifiable view; empty until such an effect is
	 * applied.
	 *
	 * @return the granted tech tokens
	 */
	public Set<String> getGrantedTechTokens() {
		return techState.grantedTokens();
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
	 * Return the colony's consumer-good markets (enjoyment, necessity — in
	 * registration order), e.g. for a printer to report every consumer sector in a
	 * single CSV (the way {@link #getBanks()} backs the consolidated banks report).
	 *
	 * @return the colony's consumer-good markets
	 */
	public Collection<ConsumerGoodMarket> getConsumerGoodMarkets() {
		return consumerGoodMarkets;
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
	 * households. Their creation and death are recorded in the event log (see
	 * {@link com.civstudio.io.SimLog}); this roster is what confines death logging to
	 * them and is exposed for any caller that needs the living set.
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
		printer.bind(sinkFactory);
	}

	/**
	 * Set the factory that creates the {@link RowSink} each printer writes to.
	 * Must be called <b>before</b> any printer is registered (the sink is created
	 * at {@link #addPrinter} time). Defaults to a {@link CsvRowSinkFactory}; a
	 * launcher installs a database- or composite-backed factory here to persist
	 * the run.
	 *
	 * @param factory the sink factory
	 */
	public void setSinkFactory(RowSinkFactory factory) {
		assert (factory != null);
		this.sinkFactory = factory;
	}

	/**
	 * clean up printers
	 */
	public void cleanUpPrinters() {
		for (Printer printer : printers)
			printer.cleanup();
	}
}
