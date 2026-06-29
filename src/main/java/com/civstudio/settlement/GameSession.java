package com.civstudio.settlement;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.civstudio.agent.Retinue;
import com.civstudio.agent.Caravan;
import com.civstudio.calendar.LiturgicalCalendar;
import com.civstudio.era.Era;
import com.civstudio.geo.Province;
import com.civstudio.geo.TerrainRegistry;
import com.civstudio.geo.WorldMap;
import com.civstudio.mortality.Demography;
import com.civstudio.name.DynastyPool;
import com.civstudio.name.NameRegistry;
import com.civstudio.name.NameTable;
import com.civstudio.race.Race;
import com.civstudio.tech.TechTree;
import com.civstudio.util.Rng;
import com.civstudio.util.RngSeed;
import lombok.Getter;

/**
 * A game session owns the random-number seed and creates {@link Settlement}
 * instances from it. Each colony it creates gets its <b>own</b> economic
 * generator, derived from the session seed and the colony's index, so several
 * colonies in one session run on <em>independent</em> economic random streams
 * and never interleave; the same seed yields an identical run. The first colony
 * (index 0) uses the bare seed, so a single-colony run is byte-identical to one
 * with a single shared generator.
 * <p>
 * The session owns the shared, immutable given-name tables and the session-wide
 * {@link DynastyPool} of surnames, but each colony it creates gets its <b>own</b>
 * {@link NameRegistry} and {@link Demography}, on per-colony naming/mortality/skill
 * generators (salted copies of the seed) so these draws are deterministic yet
 * never perturb the economic random stream. This per-colony partitioning is what
 * lets the colonies run on <b>separate threads</b>: each draws and recycles
 * surnames only within its own disjoint slice of the {@code DynastyPool} (so they
 * stay unique across the whole session) and rolls its own mortality/skill, without
 * sharing mutable state. Colony 0's mortality and skill generators reuse the bare
 * salted seeds, so a single-colony run's economics are unchanged by the
 * partitioning (only which surnames land where shifts — surnames are cosmetic).
 * <p>
 * The session is also the home of the realm's <b>colony-less</b> bands: a {@link
 * Caravan} (a wandering following with a leader but no settlement) belongs to no
 * {@code Settlement}, so the session tracks it (see {@link #addCaravan} / {@link
 * #getCaravans()}) and is where a band <b>re-founds</b> — {@link
 * #newSettlement(Caravan, String, LocalDate, double, double, double, double)}
 * raises a fresh colony at the band's position, taking the next colony index so the
 * run stays deterministic (see {@code docs/caravan.md}).
 *
 * @author zhihongx
 */
public class GameSession {

	// surnames dealt to each colony's NameRegistry as its initial slice (and the
	// size of each refill). Sized well above a colony's peak living-household count
	// (a default colony peaks at a few hundred) so a colony rarely needs a refill,
	// while leaving the 151k-surname master pool enough for many colonies.
	private static final int DYNASTY_SLICE_SIZE = 4096;

	// random-number seed for this session
	@Getter
	private final long seed;

	// derives all of the session's decorrelated random streams off the seed (and owns
	// the decorrelation salts); see RngSeed.
	private final RngSeed rngSeed;

	// the era every colony in this session founds in — the session's place on the
	// civilizational ladder. Drives the economic tuning and the research baseline (a
	// colony knows every tech up to the era below this and researches this era's
	// frontier). Medieval by default, so all simulations start Medieval.
	@Getter
	private final Era era;

	// the shared, immutable given-name tables, keyed by Race (stateless to draw from,
	// so safe to share across colonies — only the generator differs per colony). The
	// HUMAN tables are loaded eagerly in the constructor; a non-human race's tables are
	// loaded lazily on first use, falling back to the human file when its own is absent
	// (see givenNames). EnumMap, so a mono-cultural session only ever holds HUMAN.
	private final Map<Race, NameTable> maleNamesByRace = new EnumMap<>(Race.class);
	private final Map<Race, NameTable> femaleNamesByRace = new EnumMap<>(Race.class);

	// the session-wide surname pool each colony's NameRegistry draws a disjoint slice
	// from (so surnames stay unique across colonies on separate threads), keyed by Race
	// — one pool per race, so the uniqueness invariant is per race. HUMAN eager (built
	// exactly as before), others lazy (see dynastyPool(Race)).
	private final Map<Race, DynastyPool> dynastyPoolByRace = new EnumMap<>(Race.class);

	// the per-race liturgical calendar, cached. A colony keys its rest calendar off its
	// founding race (see docs/race.md); until per-race feast files ship (Phase 3) every
	// race falls back to the shared human calendar. HUMAN seeded eagerly below.
	private final Map<Race, LiturgicalCalendar> calendarByRace = new EnumMap<>(Race.class);

	// the curated terrain/feature/improvement definitions, loaded once at session
	// start and shared by every colony (pure reference data — independent of seed
	// and location). A colony generates its plot terrain through it (see Plot /
	// TerrainGenerator / docs/plots.md).
	@Getter
	private final TerrainRegistry terrainRegistry;

	// the liturgical calendar (curated universal feast list), loaded once and
	// shared by every colony — like the terrain registry it is independent of seed and
	// location, classifying any date as workday/weekend/holiday
	@Getter
	private final LiturgicalCalendar liturgicalCalendar;

	// the technology graph, shared by every colony (pure reference data). Loaded
	// lazily on first request rather than in the constructor: the ~428KB parse is
	// only paid by a session that actually uses tech, so tech-less runs and tests
	// are unaffected. See getTechTree().
	private TechTree techTree;

	// the world map (province graph), shared by every colony (pure reference data).
	// Loaded lazily on first request like the tech tree, so geography-less runs and
	// tests do not pay the ~1MB parse. See getWorldMap() and docs/geography.md.
	private WorldMap worldMap;

	// per-race tech trees: the same graph (techs.json) under a race's effect overlay
	// (/tech-effects-<id>.json). A race with no overlay file reuses the shared techTree
	// (the default empty overlay), so the human path is unchanged. See getTechTree(Race).
	private final Map<Race, TechTree> techTreeByRace = new EnumMap<>(Race.class);

	// number of colonies created so far; each gets an economic generator seeded
	// from (seed, index), so colonies don't share an economic random stream
	private int colonyCount = 0;

	// the session's wandering bands — colony-less Caravans that belong to no
	// Settlement (a band on the road after a collapse, or before it re-founds)
	private final List<Caravan> caravans = new ArrayList<>();

	// the session-level random stream all bands draw on for movement and settle
	// decisions (see getBandRng). Built lazily on first request and never advanced
	// until a band actually ticks, so a session with no bands draws nothing — keeping
	// runs without caravans on the map byte-identical to before.
	private Rng bandRng;

	/**
	 * Create a new game session with the given random-number seed, founding in the
	 * {@link Era#MEDIEVAL Medieval} era (the default for all simulations).
	 *
	 * @param seed
	 *            the random-number seed for runs created from this session
	 */
	public GameSession(long seed) {
		this(seed, Era.MEDIEVAL);
	}

	/**
	 * Create a new game session with the given random-number seed and founding
	 * {@link Era}.
	 *
	 * @param seed
	 *            the random-number seed for runs created from this session
	 * @param era
	 *            the era every colony in this session founds in
	 */
	public GameSession(long seed, Era era) {
		this.seed = seed;
		this.rngSeed = new RngSeed(seed);
		this.era = era;
		// human name tables / surname pool eager (built exactly as before, so a
		// mono-cultural run is byte-identical); non-human races load lazily on demand
		this.maleNamesByRace.put(Race.HUMAN, NameTable.load("/names/human/male.json"));
		this.femaleNamesByRace.put(Race.HUMAN, NameTable.load("/names/human/female.json"));
		this.dynastyPoolByRace.put(Race.HUMAN,
				new DynastyPool(NameTable.load("/names/human/dynasty.json"),
						rngSeed.forDynastyPool(Race.HUMAN.ordinal())));
		this.terrainRegistry = TerrainRegistry.load();
		this.liturgicalCalendar = LiturgicalCalendar.load();
		this.calendarByRace.put(Race.HUMAN, liturgicalCalendar);
	}

	/**
	 * The session's shared {@link TechTree}, loaded from {@code /techs.json} on first
	 * request and cached thereafter. Loaded lazily (not in the constructor) so a
	 * session that never touches tech does not pay the parse cost — every existing
	 * run and test is unaffected until something reads the tree.
	 *
	 * @return the shared tech tree
	 */
	public synchronized TechTree getTechTree() {
		if (techTree == null)
			techTree = TechTree.load();
		return techTree;
	}

	/**
	 * The session's shared {@link WorldMap}, loaded from {@code /provinces.json}
	 * on first request and cached thereafter. Loaded lazily (not in the
	 * constructor), like the tech tree, so a session that never touches geography
	 * does not pay the ~1MB parse cost — every existing run and test is unaffected
	 * until something reads the map. See {@code docs/geography.md}.
	 *
	 * @return the shared world map
	 */
	public synchronized WorldMap getWorldMap() {
		if (worldMap == null)
			worldMap = WorldMap.load();
		return worldMap;
	}

	/**
	 * The session-level random stream the realm's {@link Caravan wandering bands}
	 * draw on for movement and settle decisions — a single shared stream salted
	 * <b>distinct</b> from every per-colony economic stream and from the
	 * naming/mortality/skill streams, so bands on the map are deterministic per seed
	 * yet never perturb any colony's economics. Built lazily on first request and
	 * advanced only when a band ticks, so a session with no bands draws nothing (runs
	 * without caravans on the map stay byte-identical). Synchronized: bands on
	 * different colony threads may register and be ticked around the same day.
	 *
	 * @return the shared band RNG for this session
	 */
	public synchronized Rng getBandRng() {
		if (bandRng == null)
			bandRng = rngSeed.forSession(RngSeed.Stream.BAND);
		return bandRng;
	}

	/**
	 * The seed this session was created with — the single source of its
	 * reproducibility. Used to scope a run's output: every colony of this session
	 * writes its CSVs and the shared event log under {@code output/<seed>/} (see
	 * {@code Settlement.setSession} / {@link com.civstudio.io.SimLog}).
	 *
	 * @return the session seed
	 */
	public long getSeed() {
		return seed;
	}

	/**
	 * The {@link TechTree} for a colony of the given founding {@code race}. The graph
	 * itself is shared and identical for all races (so prerequisite routing is
	 * unchanged); only the per-race <em>effects overlay</em> would differ (a race
	 * giving some shared techs no, or weaker, effect — see {@code docs/race.md}). The
	 * overlay is not yet wired, so this currently returns the one shared tree for every
	 * race; the per-race seam lives here.
	 *
	 * @param race
	 *            the colony's founding race
	 * @return the tech tree under that race's effect overlay (the shared tree when the
	 *         race has no {@code /tech-effects-<id>.json})
	 */
	public synchronized TechTree getTechTree(Race race) {
		String overlay = "/tech-effects-" + race.id() + ".json";
		if (!resourceExists(overlay))
			return getTechTree(); // no per-race overlay -> shared graph + default overlay
		return techTreeByRace.computeIfAbsent(race, r -> TechTree.load(overlay));
	}

	/**
	 * The liturgical calendar for a colony of the given founding {@code race} — the
	 * single colony-wide rest calendar its firms observe (see {@code docs/race.md}).
	 * Cached per race; until per-race feast files (e.g. {@code /feasts-harimari.json})
	 * ship, every race falls back to the shared human calendar.
	 *
	 * @param race
	 *            the colony's founding race
	 * @return the founding race's liturgical calendar
	 */
	public synchronized LiturgicalCalendar getLiturgicalCalendar(Race race) {
		return calendarByRace.computeIfAbsent(race, r -> {
			String feasts = "/feasts-" + r.id() + ".json";
			// a race with its own feast file keeps its own calendar; the rest fall back
			// to the shared human calendar
			return resourceExists(feasts) ? LiturgicalCalendar.load(feasts)
					: liturgicalCalendar;
		});
	}

	// the given-name table for a race (lazily loaded, human fallback when the
	// race-specific file is absent); male == true for the male table, else female
	private synchronized NameTable givenNames(Race race, boolean male) {
		Map<Race, NameTable> by = male ? maleNamesByRace : femaleNamesByRace;
		return by.computeIfAbsent(race, r -> {
			String kind = male ? "male" : "female";
			String racePath = "/names/" + r.id() + "/" + kind + ".json";
			return NameTable.load(
					resourceExists(racePath) ? racePath : "/names/human/" + kind + ".json");
		});
	}

	// the surname pool for a race (lazily built, human fallback when the race-specific
	// dynasty file is absent). Each race's pool is shuffled on its own decorrelated
	// generator; HUMAN (ordinal 0) uses no race salt, so its pool is unchanged.
	private synchronized DynastyPool dynastyPool(Race race) {
		return dynastyPoolByRace.computeIfAbsent(race, r -> {
			String racePath = "/names/" + r.id() + "/dynasty.json";
			String path = resourceExists(racePath) ? racePath : "/names/human/dynasty.json";
			return new DynastyPool(NameTable.load(path),
					rngSeed.forDynastyPool(r.ordinal()));
		});
	}

	// whether a classpath resource exists, for the per-race resource fallback
	private static boolean resourceExists(String path) {
		return GameSession.class.getResource(path) != null;
	}

	/**
	 * Create a new colony named <tt>name</tt> whose step 0 falls on
	 * <tt>startDate</tt>, drawing economic randomness from a fresh per-colony
	 * {@link Rng} (so colonies in this session are independent), and names and
	 * mortality from the session's shared {@link NameRegistry} and {@link
	 * Demography} (so surnames stay unique across colonies).
	 *
	 * @param name
	 *            the settlement's name (a display label)
	 * @param startDate
	 *            the in-game date of step 0
	 * @param meanInitAgeYears
	 *            mean initial age (years) of founding household heads
	 * @param targetNStock
	 *            target necessity stock every laborer tries to accumulate
	 * @param meanSkillMale
	 *            mean of the colony's male skill distribution
	 * @param meanSkillFemale
	 *            mean of the colony's female skill distribution
	 * @param latitude
	 *            the colony's geographic latitude in decimal degrees (north positive)
	 * @param longitude
	 *            the colony's geographic longitude in decimal degrees (east positive)
	 * @return a fresh colony
	 */
	public synchronized Settlement newSettlement(String name, LocalDate startDate,
			double meanInitAgeYears, double targetNStock, double meanSkillMale,
			double meanSkillFemale, double latitude, double longitude) {
		// a mono-cultural human colony (every current scenario): founding race HUMAN
		// and a degenerate race-mix, so no extra races are registered and no race roll
		// is ever taken — byte-identical to before
		return newSettlement(name, startDate, meanInitAgeYears, targetNStock,
				meanSkillMale, meanSkillFemale, latitude, longitude, Race.HUMAN,
				Map.of(Race.HUMAN, 1.0));
	}

	/**
	 * Create a colony as {@link #newSettlement(String, LocalDate, double, double,
	 * double, double, double, double)} but with an explicit {@link Race founding
	 * race} (selecting the colony's calendar and tech overlay) and a per-person
	 * <tt>raceMix</tt> (the weights every generated person is rolled against; see
	 * {@code docs/race.md}). The colony's {@link NameRegistry} is given the tables
	 * and a disjoint surname slice for every race it may hold — its founding race
	 * plus each race in the mix — so a resident of any of those races is named from
	 * its own tables. A pure-human {@code raceMix} registers no extra race and rolls
	 * nothing, so the mono-cultural path stays byte-identical.
	 *
	 * @param name
	 *            the settlement's name (a display label)
	 * @param startDate
	 *            the in-game date of step 0
	 * @param meanInitAgeYears
	 *            mean initial age (years) of founding household heads
	 * @param targetNStock
	 *            target necessity stock every laborer tries to accumulate
	 * @param meanSkillMale
	 *            mean of the colony's male skill distribution
	 * @param meanSkillFemale
	 *            mean of the colony's female skill distribution
	 * @param latitude
	 *            the colony's geographic latitude in decimal degrees (north positive)
	 * @param longitude
	 *            the colony's geographic longitude in decimal degrees (east positive)
	 * @param foundingRace
	 *            the colony's founding (ruler's) race
	 * @param raceMix
	 *            race &rarr; weight every generated person is rolled against
	 * @return a fresh colony
	 */
	public synchronized Settlement newSettlement(String name, LocalDate startDate,
			double meanInitAgeYears, double targetNStock, double meanSkillMale,
			double meanSkillFemale, double latitude, double longitude,
			Race foundingRace, Map<Race, Double> raceMix) {
		return buildSettlement(name, startDate, meanInitAgeYears, targetNStock,
				meanSkillMale, meanSkillFemale, latitude, longitude, foundingRace,
				raceMix, null);
	}

	/**
	 * Create a colony founded <b>into a {@link Province}</b> of the session's
	 * {@link #getWorldMap() world map}: the province supplies the colony's
	 * geography — its {@link Province#latitude() latitude}/{@link
	 * Province#longitude() longitude} drive the solar/daylight system and its
	 * {@link Province#plots() plots} cap how large the settlement may grow (see
	 * {@code docs/geography.md}). Otherwise identical to {@link #newSettlement(
	 * String, LocalDate, double, double, double, double, double, double, Race,
	 * Map)}.
	 *
	 * @param name             the settlement's name
	 * @param startDate        the in-game date of step 0
	 * @param meanInitAgeYears mean initial age (years) of founding heads
	 * @param targetNStock     target necessity stock every laborer accumulates
	 * @param meanSkillMale    mean of the male skill distribution
	 * @param meanSkillFemale  mean of the female skill distribution
	 * @param province         the province to found into (its lat/long/plots are used)
	 * @param foundingRace     the colony's founding (ruler's) race
	 * @param raceMix          race &rarr; weight every generated person is rolled against
	 * @return a fresh colony seated in {@code province}
	 */
	public synchronized Settlement newSettlement(String name, LocalDate startDate,
			double meanInitAgeYears, double targetNStock, double meanSkillMale,
			double meanSkillFemale, Province province, Race foundingRace,
			Map<Race, Double> raceMix) {
		return buildSettlement(name, startDate, meanInitAgeYears, targetNStock,
				meanSkillMale, meanSkillFemale, province.latitude(),
				province.longitude(), foundingRace, raceMix, province);
	}

	/**
	 * Create a mono-cultural human colony founded into a {@link Province} (see
	 * {@link #newSettlement(String, LocalDate, double, double, double, double,
	 * Province, Race, Map)}); the human path registers no extra race and rolls
	 * nothing.
	 *
	 * @param name             the settlement's name
	 * @param startDate        the in-game date of step 0
	 * @param meanInitAgeYears mean initial age (years) of founding heads
	 * @param targetNStock     target necessity stock every laborer accumulates
	 * @param meanSkillMale    mean of the male skill distribution
	 * @param meanSkillFemale  mean of the female skill distribution
	 * @param province         the province to found into
	 * @return a fresh human colony seated in {@code province}
	 */
	public synchronized Settlement newSettlement(String name, LocalDate startDate,
			double meanInitAgeYears, double targetNStock, double meanSkillMale,
			double meanSkillFemale, Province province) {
		return newSettlement(name, startDate, meanInitAgeYears, targetNStock,
				meanSkillMale, meanSkillFemale, province, Race.HUMAN,
				Map.of(Race.HUMAN, 1.0));
	}

	// the shared founding body: builds the colony's per-colony Rng / NameRegistry /
	// Demography and the Settlement itself, optionally into a province (null = bare
	// coordinates). Every newSettlement overload funnels through here; callers hold
	// the monitor (the public overloads are synchronized).
	private Settlement buildSettlement(String name, LocalDate startDate,
			double meanInitAgeYears, double targetNStock, double meanSkillMale,
			double meanSkillFemale, double latitude, double longitude,
			Race foundingRace, Map<Race, Double> raceMix, Province province) {
		// index 0 -> bare seed (byte-identical to the old single shared rng);
		// later colonies get a distinct, decorrelated seed. synchronized so several
		// threads founding/re-founding colonies don't race on the colony index or the
		// dynasty pool.
		int idx = colonyCount++;
		// index 0 -> bare seed per stream (byte-identical to the old single shared rng);
		// later colonies get distinct, decorrelated streams. The salts live in RngSeed.
		Rng colonyRng = rngSeed.forColony(RngSeed.Stream.ECONOMIC, idx);
		// each colony gets its own NameRegistry (per-race disjoint surname slices +
		// shared immutable given-name tables, on its own naming generator) and its own
		// Demography (own mortality/skill generators). Colony 0 reuses the bare salted
		// seeds, so a single-colony run's mortality/skill — hence its economics — is
		// unchanged; only which surnames land where shifts.
		Rng nameRng = rngSeed.forColony(RngSeed.Stream.NAME, idx);
		NameRegistry colonyNames = new NameRegistry(givenNames(Race.HUMAN, true),
				givenNames(Race.HUMAN, false), dynastyPool(Race.HUMAN),
				dynastyPool(Race.HUMAN).deal(DYNASTY_SLICE_SIZE), DYNASTY_SLICE_SIZE,
				nameRng);
		// register every non-human race this colony may hold (its founding race and
		// each race in the mix), dealing each its own disjoint slice; none for a
		// mono-cultural human colony, so that path is unchanged
		Set<Race> races = new LinkedHashSet<>();
		races.add(foundingRace);
		races.addAll(raceMix.keySet());
		for (Race r : races)
			if (r != Race.HUMAN)
				colonyNames.registerRace(r, givenNames(r, true), givenNames(r, false),
						dynastyPool(r), dynastyPool(r).deal(DYNASTY_SLICE_SIZE),
						DYNASTY_SLICE_SIZE);
		Demography colonyDemography = new Demography(
				rngSeed.forColony(RngSeed.Stream.MORTALITY, idx),
				rngSeed.forColony(RngSeed.Stream.SKILL, idx));
		// the terrain stream is salted apart from the economic/naming/mortality/skill
		// streams, so plot generation is deterministic per seed yet perturbs none of them
		Rng terrainRng = rngSeed.forColony(RngSeed.Stream.TERRAIN, idx);
		Settlement colony = new Settlement(name, startDate, colonyRng, colonyNames,
				colonyDemography, terrainRegistry, terrainRng,
				getLiturgicalCalendar(foundingRace), meanInitAgeYears, targetNStock,
				meanSkillMale, meanSkillFemale, latitude, longitude, foundingRace,
				raceMix, province);
		// the colony knows its session, so on dissolution it can register the band it
		// departs as (colony-less bands live at the session level — see docs/caravan.md)
		colony.setSession(this);
		return colony;
	}

	/**
	 * Re-found a colony for a wandering {@link Caravan band}: a fresh colony where the
	 * band settled, taking the next colony index exactly as any {@link #newSettlement}
	 * call (so the band's new home runs on its own deterministic economic stream and
	 * "same seed → identical run" still holds). The geography is the band's — an
	 * {@linkplain Caravan#onGraph() on-graph} band re-founds <b>into its current
	 * {@link Province}</b> (Phase A of {@code docs/caravan-trade.md}), so the new colony
	 * inherits that province's latitude/longitude <em>and</em> its {@code plots} cap on
	 * settlement size, exactly like any province-founded colony; an off-graph band (born
	 * from a bare-coordinate colony) instead re-founds at its raw latitude/longitude with
	 * no province cap. Everything else is supplied by the caller as for a settlement
	 * founded from scratch.
	 * <p>
	 * This only raises the bare {@code Settlement}; binding the band's surviving people
	 * and carried hoard into it (seeding the new colony's {@link Retinue} from
	 * the band) is the foundry's job (see {@code docs/caravan.md} — the re-founding
	 * ascent {@code CARAVAN → HOLDING → VILLAGE}).
	 *
	 * @param band
	 *            the wandering band re-founding; its position becomes the new colony's
	 * @param name
	 *            the new settlement's name (a display label)
	 * @param startDate
	 *            the in-game date of the new colony's step 0
	 * @param meanInitAgeYears
	 *            mean initial age (years) of founding household heads
	 * @param targetNStock
	 *            target necessity stock every laborer tries to accumulate
	 * @param meanSkillMale
	 *            mean of the colony's male skill distribution
	 * @param meanSkillFemale
	 *            mean of the colony's female skill distribution
	 * @return a fresh colony positioned where the band settled
	 */
	public Settlement newSettlement(Caravan band, String name, LocalDate startDate,
			double meanInitAgeYears, double targetNStock, double meanSkillMale,
			double meanSkillFemale) {
		// an on-graph band re-founds into its province (inheriting its lat/long and
		// plots cap); an off-graph band re-founds at its raw coordinates as before
		if (band.onGraph())
			return newSettlement(name, startDate, meanInitAgeYears, targetNStock,
					meanSkillMale, meanSkillFemale,
					getWorldMap().province(band.getProvinceId()));
		return newSettlement(name, startDate, meanInitAgeYears, targetNStock,
				meanSkillMale, meanSkillFemale, band.getLatitude(),
				band.getLongitude());
	}

	/**
	 * Register a colony-less {@link Caravan} with the session — a band that has left
	 * (or not yet founded) a settlement, so it has no {@code Settlement} to live on.
	 * The session is the home of such wandering bands (the level at which they re-found;
	 * see {@link #newSettlement(Caravan, String, LocalDate, double, double, double, double)}).
	 *
	 * @param band
	 *            the wandering band to track
	 */
	public synchronized void addCaravan(Caravan band) {
		// synchronized: colonies on different threads can dissolve into bands in the
		// same lockstep day and register them concurrently
		caravans.add(band);
	}

	/**
	 * The session's wandering bands — the colony-less {@link Caravan}s it tracks (see
	 * {@link #addCaravan}). The returned list is unmodifiable.
	 *
	 * @return an unmodifiable view of the session's caravans
	 */
	public synchronized List<Caravan> getCaravans() {
		// a snapshot copy, so a caller can iterate it while another thread adds a band
		return Collections.unmodifiableList(new ArrayList<>(caravans));
	}
}
