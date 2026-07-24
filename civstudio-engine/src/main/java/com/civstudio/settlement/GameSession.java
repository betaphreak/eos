package com.civstudio.settlement;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.civstudio.agent.Retinue;
import com.civstudio.agent.Caravan;
import com.civstudio.calendar.LiturgicalCalendar;
import com.civstudio.era.Era;
import com.civstudio.geo.Province;
import com.civstudio.geo.ProvinceRaster;
import com.civstudio.geo.TerrainRegistry;
import com.civstudio.geo.WorldMap;
import com.civstudio.mortality.Demography;
import com.civstudio.name.DynastyPool;
import com.civstudio.name.NameRegistry;
import com.civstudio.name.NameStore;
import com.civstudio.name.NameTable;
import com.civstudio.race.Race;
import com.civstudio.tech.TechTree;
import com.civstudio.util.Rng;
import com.civstudio.util.RngSeed;
import lombok.Getter;
import lombok.Setter;

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

	// keep this session's SimLog output in its own seed-scoped file (output/<seed>/<seed>.log)
	// even under the test tier's -Dcivstudio.printers.skip (which otherwise routes every session
	// to the shared output/sim.log). Opt-in, per session — the log-routing guard sets it to
	// verify per-seed separation without a global toggle that parallel test classes would race.
	@Getter
	@Setter
	private boolean seedScopedLog = false;

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

	// per-province claimable plot pools — the shared province plot field (see
	// docs/province-plots.md), generated lazily on first request and cached, with the
	// raster reader loaded on demand too. Only a province-founded colony reaching for
	// its pool pays the BMP read; geography-less runs and tests are unaffected.
	private ProvinceRaster provinceRaster;
	private final Map<Integer, ProvincePlotPool> plotPoolByProvince = new HashMap<>();

	// provinces whose route layer changed since the render loop last drained this — the "refetch
	// your in-view routes here" signal the snapshot advertises (docs/route-rendering.md §Viewport-
	// windowed route persistence). Fed by markRouteDirty (a band laying a trail; a pool created
	// with urban trails), drained each emit by the host. The pool holds the authoritative layer; this is
	// only the change notification, so a drop just delays a refetch until the next change or a
	// viewport re-entry.
	private final Set<Integer> dirtyRouteProvinces = new LinkedHashSet<>();

	// provinces whose barony-level plot map has already been claimed by a settlement,
	// so a province shared by several settlements dumps its whole plot field once
	// rather than once per settlement (see firstPlotMapFor / PlotMapPrinter).
	private final Set<Integer> plotMapProvinces = new LinkedHashSet<>();

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
		this.maleNamesByRace.put(Race.HUMAN, NameTable.load("/human-names/male.json"));
		this.femaleNamesByRace.put(Race.HUMAN, NameTable.load("/human-names/female.json"));
		this.dynastyPoolByRace.put(Race.HUMAN,
				new DynastyPool(NameTable.load("/human-names/dynasty.json"),
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
	 * The shared, claimable {@link ProvincePlotPool plot pool} of a province — its
	 * land pixels realised as occupiable plots, generated once per province (off a
	 * per-province terrain stream) and cached, so every settlement founded into the
	 * province claims from the same field. Built lazily on first request (loading the
	 * raster reader on demand), so only a province-founded colony that reaches for its
	 * pool pays the cost; geography-less runs and tests are unaffected. See {@code
	 * docs/province-plots.md}.
	 *
	 * @param province the province whose pool is wanted
	 * @return the province's shared plot pool
	 */
	/**
	 * Whether this is the <b>first</b> request to own the barony-level plot map of a
	 * province — true once per province, false thereafter. A province's whole plot
	 * field belongs to the barony, not to any one settlement, so only the first
	 * settlement founded into a province registers a {@link
	 * com.civstudio.io.printer.PlotMapPrinter}; settlements sharing the province do
	 * not, and the field is dumped once. See {@code docs/province-plots.md}.
	 *
	 * @param province the province whose plot map is being claimed
	 * @return {@code true} if no settlement has yet claimed this province's plot map
	 */
	public synchronized boolean firstPlotMapFor(Province province) {
		return plotMapProvinces.add(province.id());
	}

	/**
	 * Whether this province's plot pool <b>already exists</b> — without building it.
	 * <p>
	 * This is the cheap <b>frontier test</b>. A pool is materialised exactly when someone reaches
	 * into the province's ground: a band that camps or lays a trail there ({@code
	 * MarchingCaravan.tick}'s {@code campingEnabled || laysTrail()} corridor), or a colony founded
	 * on it. So a pool's <b>absence is proof no band has ever been there</b> — the one side a
	 * frontier-seeking explorer needs certainty on ({@code ExplorerCaravan.chooseWanderTarget}).
	 * <p>
	 * Its presence is the weaker half: a province can own a pool without a band having walked it
	 * (a colony founded on it, a plot-map printer). That asymmetry is <b>fine for frontier-seeking
	 * and arguably right</b> — a settled province is not a frontier either.
	 * <p>
	 * Note why the obvious test — scanning the pool's plots for a {@code routeType} — is <b>not</b>
	 * used: {@code ProvincePlotPool.trailUrbanPlots} trails every urban plot at construction, so
	 * every city province would report itself explored on day zero with nobody having set foot in
	 * it. And reading a candidate's plots at all would force the very generation this avoids —
	 * {@link #provincePlotPool} is {@code computeIfAbsent}, so asking is building.
	 *
	 * @param provinceId the province to test
	 * @return {@code true} if the province's plot pool has already been built this session
	 */
	public synchronized boolean hasPlotPool(int provinceId) {
		return plotPoolByProvince.containsKey(provinceId);
	}

	public synchronized ProvincePlotPool provincePlotPool(Province province) {
		return plotPoolByProvince.computeIfAbsent(province.id(), id -> {
			try {
				if (provinceRaster == null)
					provinceRaster = ProvinceRaster.load();
				// seed-independent terrain stream: the field is a property of the map, so it is
				// generated once, cached (.map/v<MAP_VERSION>/<id>.json.gz — shared with the
				// server's PlotService), and reused every run regardless of seed (see
				// ProvincePlotStore / docs/province-plots.md)
				Rng terrainRng = rngSeed.forProvinceCanonical(RngSeed.Stream.TERRAIN, id);
				ProvincePlotPool pool = ProvincePlotPool.loadOrGenerate(province, terrainRegistry,
						provinceRaster, terrainRng);
				// a pool born with routes (urban city-core trails) is a change a client viewing
				// this province must pick up — mark it dirty so the next snapshot tells them to fetch.
				if (pool.hasRoutes())
					dirtyRouteProvinces.add(id);
				return pool;
			} catch (IOException e) {
				throw new UncheckedIOException("failed to build plot pool for province " + id, e);
			}
		});
	}

	/**
	 * The province's plot pool <b>if it already exists</b>, else {@code null} — a non-generating
	 * lookup (unlike {@link #provincePlotPool}, which builds on demand). The route feed uses this: a
	 * province with no pool has no routes, and answering must never pay the pool's generation cost.
	 *
	 * @param provinceId the province to look up
	 * @return the existing pool, or {@code null} if none has been built this session
	 */
	public synchronized ProvincePlotPool plotPoolIfPresent(int provinceId) {
		return plotPoolByProvince.get(provinceId);
	}

	/**
	 * Flag a province's route layer as changed, so the next render snapshot tells clients to refetch
	 * its routes (docs/route-rendering.md). Called on the session thread when a band pioneers a trail
	 * ({@link com.civstudio.agent.MarchingCaravan#layTrail}) or a pool is born trailed. Idempotent.
	 *
	 * @param provinceId the province whose route layer changed
	 */
	public synchronized void markRouteDirty(int provinceId) {
		dirtyRouteProvinces.add(provinceId);
	}

	/**
	 * Take and clear the set of provinces whose route layer changed since the last drain — the
	 * snapshot's route dirty-signal. Called once per emit on the session thread.
	 *
	 * @return the changed province ids (a copy the caller owns), empty if none
	 */
	public synchronized List<Integer> drainRouteDirty() {
		if (dirtyRouteProvinces.isEmpty())
			return List.of();
		List<Integer> drained = new ArrayList<>(dirtyRouteProvinces);
		dirtyRouteProvinces.clear();
		return drained;
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

	// mortality service for wandering-band LEADERS. A colony-less band has no Demography of its own
	// (the leader is a bare Member on the Caravan, ticked by nothing), so its old-age death roll — the
	// one that drives leader succession (docs/caravan.md) — reads this session-level one. Lazy (a run
	// with no bands builds none) and on the salted LEADER stream, so it perturbs no existing draw.
	private Demography leaderDemography;

	/**
	 * The mortality service that rolls a wandering band's {@linkplain Caravan#getLeader() leader}'s
	 * old-age death (which triggers succession to the ablest survivor). Session-scoped because a band
	 * has no colony; on its own salted stream so it never perturbs an existing draw.
	 *
	 * @return the band-leader demography (built once, reused)
	 */
	public synchronized Demography leaderDemography() {
		if (leaderDemography == null)
			leaderDemography = new Demography(rngSeed.forSession(RngSeed.Stream.LEADER));
		return leaderDemography;
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
		return techTreeByRace.computeIfAbsent(race,
				r -> TechTree.loadWithRaceOverlay(overlay));
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

	// the given-name table for a race (lazily loaded); non-human races are generated on demand from
	// Anbennar and cached (NameStore), falling back to the human table when a race is absent/sparse in
	// the source. HUMAN is pre-loaded in the constructor, so this only runs for non-human races.
	private synchronized NameTable givenNames(Race race, boolean male) {
		Map<Race, NameTable> by = male ? maleNamesByRace : femaleNamesByRace;
		return by.computeIfAbsent(race, r -> {
			NameTable t = NameStore.table(r.id(), male ? "male" : "female");
			return t != null ? t : by.get(Race.HUMAN);
		});
	}

	// the surname pool for a race (lazily built). Non-human dynasty tables are generated on demand
	// (NameStore), falling back to the human surname list when the race is absent/sparse. Each race's
	// pool is shuffled on its own decorrelated generator; HUMAN (ordinal 0) uses no race salt.
	private synchronized DynastyPool dynastyPool(Race race) {
		return dynastyPoolByRace.computeIfAbsent(race, r -> {
			NameTable t = NameStore.table(r.id(), "dynasty");
			if (t == null)
				t = NameTable.load("/human-names/dynasty.json");
			return new DynastyPool(t, rngSeed.forDynastyPool(r.ordinal()));
		});
	}

	// whether a classpath resource exists, for the per-race calendar fallback
	private static boolean resourceExists(String path) {
		return com.civstudio.data.WorldSources.current().exists(path);
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
	 * @return a fresh colony seated in {@code province}, peopled by whoever lives there
	 */
	public synchronized Settlement newSettlement(String name, LocalDate startDate,
			double meanInitAgeYears, double targetNStock, double meanSkillMale,
			double meanSkillFemale, Province province) {
		// Who lives here is not a parameter — the imported world already says so. A province has a
		// culture, a culture has a group, and that group IS a race id (both from anb_cultures.txt),
		// so founding into Rubyhold makes a dwarven colony and Dancers Retreat an elven one without
		// a scenario having to know. See WorldMap#raceOf(Province).
		//
		// This needed DynastyPool to stop refusing to repeat a surname: only HUMAN has hand-authored
		// name tables, so a full-size colony of any other race outran its authored list and died
		// mid-founding. Now the pool wraps, and 400 households share surnames the way 400 medieval
		// households actually would.
		Race race = getWorldMap().raceOf(province);
		return newSettlement(name, startDate, meanInitAgeYears, targetNStock,
				meanSkillMale, meanSkillFemale, province, race, Map.of(race, 1.0));
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
		// one Settlement per site: it derives its founding tier from the province
		// (docs/settlement-tiers.md) — an Anbennar city_terrain province (several urban plots)
		// founds at METROPOLIS (districts + permanence); an ordinary or bare site at a
		// single-centre SMALLHOLDING. Growth (Phase B) climbs the ladder from there.
		Settlement colony = new Settlement(name, startDate, colonyRng, colonyNames,
				colonyDemography, terrainRegistry, terrainRng,
				getLiturgicalCalendar(foundingRace), meanInitAgeYears, targetNStock,
				meanSkillMale, meanSkillFemale, latitude, longitude, foundingRace,
				raceMix, province,
				// the colony's own economy: the (era, race) cell for whoever founded it. Resolved
				// here because a Settlement outlives any harness and a session may seat several
				// races — see Settlement#getEconomy().
				era.economy(foundingRace));
		// the colony knows its session, so on dissolution it can register the band it
		// departs as (colony-less bands live at the session level — see docs/caravan.md)
		colony.setSession(this);
		// its own decorrelated stream for the explorer levies it musters and ticks
		// (docs/explorer-caravan.md), salted apart from every economic/naming/mortality
		// stream so a colony with no excursions draws nothing and stays byte-identical
		colony.setExcursionRng(rngSeed.forColony(RngSeed.Stream.EXCURSION, idx));
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

	/**
	 * Drop every {@linkplain Caravan#isSpent() spent} band — the session's counterpart to the
	 * colony-level prune in {@code Settlement.tickExcursions}. A band that starves out on the
	 * road leaves a corpse behind: it can never march, settle or be re-founded again, but
	 * nothing removed it, so it was re-ticked every day for the rest of the run and still shipped
	 * to the client as a live marker with a head-count of zero. The drivers call this once a day,
	 * after ticking the bands.
	 * <p>
	 * The band's hoard and cargo die with it, exactly as they already did — the money was
	 * unreachable the moment the last member did, since a hoard is only ever spent by a living
	 * band. This deletes the corpse, not the assets.
	 *
	 * @return the bands removed, in registration order (empty when none died) — the caller logs
	 *         them, since the session has no colony prefix of its own to log under
	 */
	public synchronized List<Caravan> pruneSpentCaravans() {
		// synchronized with addCaravan: a colony dissolving into a band on another thread must
		// not race the prune of the same lockstep day
		List<Caravan> dead = new ArrayList<>();
		for (Caravan band : caravans)
			if (band.isSpent())
				dead.add(band);
		caravans.removeAll(dead);
		return dead;
	}
}
