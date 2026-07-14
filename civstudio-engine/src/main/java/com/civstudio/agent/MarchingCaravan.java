package com.civstudio.agent;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;

import com.civstudio.agent.march.Camp;
import com.civstudio.agent.march.March;
import com.civstudio.agent.march.MarchConfig;
import com.civstudio.agent.march.MarchDay;
import com.civstudio.agent.march.MarchFlavor;
import com.civstudio.agent.march.MarchReport;
import com.civstudio.era.Era;
import com.civstudio.geo.Bonus;
import com.civstudio.geo.LandRouter;
import com.civstudio.geo.Route;
import com.civstudio.good.RationSize;
import com.civstudio.good.ResourceType;
import com.civstudio.settlement.Plot;
import com.civstudio.settlement.PlotCorridor;
import com.civstudio.settlement.ProvincePlotPool;
import com.civstudio.settlement.SolarClock;
import com.civstudio.tech.ResearchSnapshot;
import com.civstudio.tech.Tech;
import com.civstudio.tech.TechTree;
import com.civstudio.util.Rng;
import lombok.Getter;

/**
 * A <b>marching band</b>: the {@link Caravan} that carries a {@link #getFollowing()
 * following} (its people and their larder) and walks the province graph under the
 * daylight-bounded <b>march</b> — spending a daily distance along a distance-accurate
 * route, pitching a nightly {@link Camp camp}, and <b>foraging</b>/<b>gathering</b> the
 * land it crosses (see {@code docs/caravan-march.md}). This is the shared home of
 * everything that makes a band "forage like today": the {@link Retinue} following and its
 * larder clock, the march logistics, forage + gather, the tech-gated resource
 * identification, and the nightly camp.
 * <p>
 * What differs between the flavors of marching band is only their <b>goal</b> — where they
 * march and what they do on arrival (mirroring a Caveman2Cosmos unit's
 * {@code <DefaultUnitAI>}/mission). Concrete subclasses specialize just that seam:
 * {@link SettlerCaravan} (found a colony — {@code UNITAI_SETTLE}); a worker (build map
 * infrastructure — {@code UNITAI_WORKER}), an explorer (scout/identify —
 * {@code UNITAI_EXPLORE}) and a military band (project force — the combat AIs) slot in the
 * same way, each overriding {@link #arrive(LocalDate, Rng)} (its mission) and optionally
 * {@link #marchFlavor()} (its order of march) and {@link #chooseWanderTarget(Rng)} (its
 * un-directed target). See {@code docs/caravan.md} §Phase 5.
 * <p>
 * <b>Directed vs. wandering.</b> A band may be pointed at a fixed {@link
 * #setDestination(int) destination} (it marches the route there) or left to wander, in
 * which case {@link #chooseWanderTarget(Rng)} picks its next target each time it needs a
 * route. All movement and target choice ride the session-level band RNG passed to
 * {@link #tick(LocalDate, Rng)}, so bands on the map stay reproducible.
 */
public abstract class MarchingCaravan extends Caravan {

	/**
	 * The daily ration a wandering band eats from its carried larder while not settled —
	 * {@link RationSize#SNACK}, leaner even than poor relief, because a band on the move
	 * cannot restock on a market.
	 */
	public static final RationSize WANDERING_RATION = RationSize.SNACK;

	/**
	 * The tech a band departs with by default — a <b>medieval lifestyle</b>. Its era's
	 * pre-known set is the band's {@linkplain #knownTechs() tech state} when it carries no
	 * research of its own, so a fresh band identifies only the resources a medieval people
	 * would know (not, say, an oil or uranium deposit locked behind far-future techs).
	 */
	public static final String DEFAULT_TECH = "TECH_MEDIEVAL_LIFESTYLE";

	// the band's following: its people and their carried larder — a full Retinue for a
	// settler/dissolution band (its transferred labour reserve) or a lean DraftBand for an
	// explorer's referenced levy (docs/explorer-caravan.md). The march reads only the
	// MarchFollowing slice; a band that carries a Retinue exposes it via a covariant
	// getFollowing() override (see SettlerCaravan).
	@Getter
	protected final MarchFollowing following;

	// the tech tree the band carries out of its abandoned settlement (null if it never
	// had research): what it knows and was researching, restored onto the colony it
	// re-founds so progress is not lost (see SettlerCaravan.dissolve / ResearchState.restore)
	@Getter
	protected ResearchSnapshot research;

	// the province the band set out from: it will not re-found here, only at a fresh site.
	// OFF_GRAPH for an off-graph band. Generic (every band has an origin).
	protected final int originProvinceId;

	// the province the band is currently routing toward (OFF_GRAPH = none chosen yet / none
	// reachable); recomputed when unset or reached.
	private int targetProvinceId = OFF_GRAPH;

	// directed travel: when set (see setDestination), the band marches the route to a fixed
	// destination rather than choosing a wander target. OFF_GRAPH = the default wander.
	private int destination = OFF_GRAPH;
	private boolean directed;

	// the band's tech state: the set of tech ids it knows, which gates which resource
	// bonuses it can identify (forage / gather / report). Lazily defaulted (see knownTechs)
	// from the carried research, else the DEFAULT_TECH baseline; overridable via setKnownTechs.
	private Set<String> knownTechs;

	// fractional gathering progress per good (keyed by bonus type): cargo goods are
	// discrete, so a day's part-unit work accrues here and only whole units are
	// deposited into the carried cargo (see gather)
	private final Map<String, Double> gatherProgress = new LinkedHashMap<>();

	// the daylight-bounded march (docs/caravan-march.md): the calibration constants, the
	// distance-accurate route the band spends its daily distance along, and where it is
	// on that route (which boundary hop, and how far into it — carried across days so a
	// long hop takes several days).
	private final MarchConfig marchConfig = MarchConfig.DEFAULT;
	private LandRouter router; // lazily built over the band's world map
	private Route route;       // the current route toward the chosen target
	private int legIndex;          // the hop of `route` the band is currently crossing
	private double progressPoints; // Civ4 move-points already spent into the current leg's cost
	// the current leg's cost, in Civ4 move-points = the plot corridor's summed Civ4 move cost
	// across the province the band is in (docs/explorer-caravan.md §5 — the per-plot terrain/
	// feature/hills/slope ladder, no KM_PER_PLOT dilution) + the boundary hop into the next;
	// and the river fords on that corridor (each a full day). Lazily computed per leg.
	private double legMoveCost;
	private int legFords;
	private boolean legReady;

	// the last day's computed march and its print-ready report (exposed for the journal
	// and tests); null until the band has ticked at least once on the graph
	@Getter
	private MarchDay lastMarchDay;
	@Getter
	private MarchReport lastReport;

	// the plot the band is camped on tonight (a transient PlotOccupant), or null. Camping
	// generates the province's plot field, so it is opt-in (the reporting drivers enable
	// it); a bare wander leaves it off and pays no generation cost.
	private boolean campingEnabled;
	private Plot campPlot;
	// the province the band crossed into its current one from (the corridor entry side);
	// OFF_GRAPH until it has made a hop (then the entry portal falls back to the centroid)
	private int enteredFrom = OFF_GRAPH;

	/**
	 * Create an <b>on-graph</b> marching band anchored at a province, able to walk the
	 * graph. The following is put into its wandering (marketless, larder-fed) mode.
	 *
	 * @param leader     the band's leader (its Captain)
	 * @param following  the band's following (its people and carried larder)
	 * @param hoard      the band's carried money, in copper, held outside any bank
	 * @param provinceId the band's starting node on the province graph
	 * @param session    the session the band belongs to (its world map is the graph the
	 *                   band moves on; its plot pools host the nightly camp)
	 */
	protected MarchingCaravan(Member leader, MarchFollowing following, double hoard, int provinceId,
			com.civstudio.settlement.GameSession session) {
		super(leader, hoard, provinceId, session);
		this.following = following;
		this.originProvinceId = provinceId;
		// a band on the move eats from its larder, marketless — put the following into
		// its wandering mode (the WANDERING_RATION) for as long as it is a caravan
		following.detach();
	}

	/**
	 * Create an <b>off-graph</b> marching band at bare coordinates (no province graph): it
	 * cannot walk or settle on its own (legacy bare-coordinate scenarios drive it
	 * explicitly).
	 *
	 * @param leader    the band's leader (its Captain)
	 * @param following the band's following (its people and carried larder)
	 * @param hoard     the band's carried money, in copper, held outside any bank
	 * @param latitude  the band's latitude in decimal degrees (north positive)
	 * @param longitude the band's longitude in decimal degrees (east positive)
	 */
	protected MarchingCaravan(Member leader, MarchFollowing following, double hoard, double latitude,
			double longitude) {
		super(leader, hoard, latitude, longitude);
		this.following = following;
		this.originProvinceId = OFF_GRAPH;
		following.detach();
	}

	// ---- the goal seam: what a flavor specializes (its C2C "mission") --------------------

	/**
	 * Whether the band's journey is <b>over</b> — it has reached its goal and should stop
	 * ticking (no more marching, foraging or camping). The base band never finishes on its
	 * own; a {@link SettlerCaravan} overrides this to stop once it is ready to settle.
	 *
	 * @return {@code true} to end the band's daily activity
	 */
	protected boolean journeyComplete() {
		return false;
	}

	/**
	 * Check whether the band has <b>arrived</b> at its goal this day and, if so, act on it
	 * (the band's mission — settle, build, reveal, attack). Called at the top of the day,
	 * before the march. Returning {@code true} ends the day with no march (the band acted);
	 * returning {@code false} lets the day's march proceed.
	 *
	 * @param date the current in-game date
	 * @param rng  the session-level band RNG
	 * @return {@code true} if the band reached and acted on its goal today
	 */
	protected abstract boolean arrive(LocalDate date, Rng rng);

	/**
	 * The band's <b>order of march</b> flavor — which {@link MarchFlavor} column it fields
	 * (see {@code docs/caravan-march.md} §5), derived from its {@link #role() role}: a lean
	 * admin column for the civilian roles, the full order for a military band.
	 *
	 * @return the march flavor
	 */
	protected final MarchFlavor marchFlavor() {
		return role().marchFlavor();
	}

	/**
	 * Choose the band's next target province when it is <b>not</b> {@linkplain
	 * #isDirected() directed} at a fixed destination — the goal-specific "where to wander."
	 * The base band wanders nowhere (it only moves when directed); a {@link SettlerCaravan}
	 * overrides this to seek the nearest viable settling site.
	 *
	 * @param rng the session-level band RNG (for deterministic tie-breaks)
	 * @return the chosen target province, or empty to stay put
	 */
	protected OptionalInt chooseWanderTarget(Rng rng) {
		return OptionalInt.empty();
	}

	/**
	 * The journey label that names the band's journal file: {@code "<Origin>-<Destination>"}
	 * (province names) for a directed band; an un-directed band has no fixed destination, so
	 * its leader's name stands in — {@code "<Origin>-<Leader>"} — keeping journals unique.
	 *
	 * @return the journey label
	 */
	protected String journeyLabel() {
		if (!onGraph())
			return getLeader().fullName();
		String origin = worldMap().province(originProvinceId).name();
		String dest = directed ? worldMap().province(destination).name()
				: getLeader().fullName();
		return origin + "-" + dest;
	}

	/**
	 * Whether the band has <b>reached its goal</b> and stopped (settled, built, scouted,
	 * engaged) — the public read of {@link #journeyComplete()} for a caller that only needs
	 * the arrived/still-marching bit (e.g. the render snapshot).
	 *
	 * @return {@code true} if the band's journey is over
	 */
	public boolean hasArrived() {
		return journeyComplete();
	}

	/** Whether the band is marching to a fixed {@link #setDestination(int) destination}. */
	protected boolean isDirected() {
		return directed;
	}

	/** Whether a directed band has reached its fixed destination province. */
	protected boolean atDestination() {
		return directed && onGraph() && getProvinceId() == destination;
	}

	/**
	 * Direct the band to march to a <b>fixed destination</b> province, rather than choosing
	 * a wander target. It routes over the shortest-km land path ({@link LandRouter}) and
	 * marches it under the daylight-bounded model. Used to travel a band between two known
	 * places.
	 *
	 * @param provinceId the destination province to march to
	 */
	public void setDestination(int provinceId) {
		this.destination = provinceId;
		this.directed = true;
		this.targetProvinceId = provinceId;
		this.route = null; // force a fresh route to the new destination
		this.legReady = false;
	}

	// ---- the daily march (shared) -------------------------------------------------------

	/**
	 * Advance the band by one day: eat the {@link #WANDERING_RATION} from the carried
	 * larder, then either act on {@linkplain #arrive(LocalDate, Rng) arriving} at its goal
	 * or <b>march</b> its daily distance along the route (foraging and gathering the land it
	 * crosses, and camping where it stops). An off-graph band, a spent band (no followers
	 * left), or one whose {@linkplain #journeyComplete() journey is over} does nothing.
	 *
	 * @param date the current in-game date (drives the daily daylight budget)
	 * @param rng  the session-level band RNG (distinct from any colony's economic stream)
	 * @return the day's fresh {@link MarchReport}, or {@code null} on a day it produced none
	 */
	@Override
	public MarchReport tick(LocalDate date, Rng rng) {
		if (journeyComplete() || !onGraph())
			return null;
		// one day on the larder clock: consume the wandering ration; the unfed starve
		following.act();
		if (following.size() == 0) {
			releaseCamp();
			return null; // a spent band — no one left
		}
		// dawn: strike last night's camp before deciding today's move
		releaseCamp();

		// arrive at and act on the band's goal (settle / build / reveal / attack); if it
		// acted, the day is done with no march
		if (arrive(date, rng))
			return null;

		// the daylight-bounded day: the net distance D the band can relocate camp, from
		// the daylight at its current position and its own column length (docs/caravan-march.md)
		SolarClock clock = new SolarClock(getLatitude(), getLongitude());
		clock.update(date);
		MarchDay day = March.compute(date, following.size(), marchFlavor(),
				clock.getDaylightHours(), clock.getSunrise(), marchConfig);
		lastMarchDay = day;

		// ensure a distance-accurate route toward the current target
		ensureRoute(rng);

		// spend the day's move-point budget M traversing the current province's plot corridor
		// (the per-plot terrain/feature/hills/slope ladder — so rough/wild ground is dearer),
		// with river fords costing a full day (Civ4's ford-ends-movement rule, kept harsh until
		// bridges mitigate it). Reaching the exit border and CROSSING into the next province
		// ENDS the day's movement (Civ4's move-to-a-new-region rule), so a band advances at
		// most one province boundary per day; leftover budget still funds the day's forage.
		// Partial corridor progress carries across days, so a big/rough province takes several
		// days to cross. The daily budget is floored to minDailyMovePoints (the min-one-move
		// rule, applied in March.compute), so a marching band always advances at least one
		// plot/day and never freezes — even in polar winter.
		List<Integer> traversed = new ArrayList<>();
		traversed.add(getProvinceId());
		double startBudget = day.movePoints();
		double budget = startBudget;
		while (budget > 1e-9 && route != null && legIndex < route.hops()) {
			if (!legReady)
				computeLeg();
			if (legFords > 0) {
				// fording a river on this leg's corridor halts the day's advance
				legFords--;
				budget = 0;
				break;
			}
			double need = legMoveCost - progressPoints;
			if (budget + 1e-9 >= need) {
				budget -= need;
				progressPoints = 0;
				legReady = false;
				int next = route.provinces().get(legIndex + 1);
				enteredFrom = getProvinceId(); // remember the side we cross in from
				moveTo(next);
				legIndex++;
				traversed.add(next);
				break; // crossing into a new province ends the day's movement (Civ4 turn rule)
			} else {
				progressPoints += budget;
				budget = 0;
			}
		}

		// resolve the plot corridor across the province the day ended in (entry portal
		// from the side we came in, exit portal toward the next province) — the Level-2
		// land route (docs/land-routing.md); camp on one of its plots and report them
		PlotCorridor corridor = campingEnabled ? currentCorridor() : null;
		Plot camp = campingEnabled ? claimCampOn(corridor) : null;
		// the day's surplus daylight (what the capped march did not use) funds the
		// off-march work: forage food into the larder first (survival), then gather
		// non-food goods into the cargo with the hours foraging left over. The march spend
		// is now in move-points, so scale the day's km distance (still computed, for
		// reporting) by the fraction of the budget actually spent to recover the km marched.
		double spentFraction = startBudget > 1e-9 ? (startBudget - budget) / startBudget : 0;
		double surplusHours = campingEnabled ? surplusHours(day, spentFraction * day.netMarchKm()) : 0;
		double foraged = forage(surplusHours, corridor);
		int gathered = gather(surplusHours, foraged, corridor);
		lastReport = buildReport(date, day, traversed, corridor, foraged, gathered, camp);
		return lastReport;
	}

	// (re)compute the distance-accurate route toward the current target when the band has
	// none, has arrived, or has drifted off its current route (e.g. after a target reset).
	// The target is the fixed destination (directed) or the goal-specific wander target.
	private void ensureRoute(Rng rng) {
		boolean stale = route == null || legIndex >= route.hops()
				|| route.provinces().get(legIndex) != getProvinceId();
		if (targetProvinceId == OFF_GRAPH
				|| (!directed && targetProvinceId == getProvinceId()) || stale) {
			targetProvinceId = directed ? destination
					: chooseWanderTarget(rng).orElse(OFF_GRAPH);
			if (targetProvinceId == OFF_GRAPH) {
				route = Route.NONE;
				legIndex = 0;
				progressPoints = 0;
				return;
			}
			if (router == null)
				router = new LandRouter(worldMap());
			route = router.route(getProvinceId(), targetProvinceId);
			legIndex = 0;
			progressPoints = 0;
			legReady = false;
		}
	}

	// compute the current leg's cost (Civ4 move-points) and river fords: the plot corridor's
	// summed Civ4 move cost across the province the band is in (its plots' terrain/feature/
	// hills/slope ladder — spent directly, not scaled by KM_PER_PLOT). This is the cost to
	// reach the exit border; the crossing into the next province itself ends the day's
	// movement (see the tick loop — Civ4's move-to-a-new-region rule), so there is no separate
	// boundary-hop cost (docs/explorer-caravan.md §Phase 3, docs/land-routing.md)
	private void computeLeg() {
		int cur = getProvinceId();
		int next = route.provinces().get(legIndex + 1);
		PlotCorridor corr = corridorBetween(enteredFrom, cur, next);
		legMoveCost = corr == null ? 0 : corr.totalCost();
		legFords = corr == null ? 0 : corr.riverCrossings();
		legReady = true;
	}

	// the plot corridor across province `cur` from the border it was entered at (from) to
	// the border toward `to`; the anchors fall back to the plot centroid when a portal is
	// unknown. Null when the band is session-less.
	private PlotCorridor corridorBetween(int from, int cur, int to) {
		if (session() == null)
			return null;
		ProvincePlotPool pool = session().provincePlotPool(worldMap().province(cur));
		int[] entry = anchor(from, cur, pool);
		int[] exit = anchor(cur, to, pool);
		return pool.corridor(entry[0], entry[1], exit[0], exit[1]);
	}

	// the plot corridor across the province the band ended the day in: from the border
	// portal it crossed in at (enteredFrom) to the portal toward the next province on the
	// route (or the province centroid when either is unknown). Null when session-less.
	private PlotCorridor currentCorridor() {
		int next = (route != null && legIndex < route.hops())
				? route.provinces().get(legIndex + 1) : OFF_GRAPH;
		return corridorBetween(enteredFrom, getProvinceId(), next);
	}

	// the raster anchor for a corridor endpoint: the committed border portal from -> to,
	// or the province's plot centroid when the portal is unknown (no hop yet, or the band
	// has arrived so there is no next province)
	private int[] anchor(int from, int to, ProvincePlotPool pool) {
		if (from != OFF_GRAPH && to != OFF_GRAPH) {
			int[] portal = worldMap().portal(from, to);
			if (portal != null)
				return portal;
		}
		return new int[] { pool.centroidX(), pool.centroidY() };
	}

	// pitch a nightly camp on a plot of the day's corridor (preferring its exit end, where
	// the band actually stopped), falling back to any free plot of the province; a
	// transient occupancy, not a settlement claim. The claim is an atomic tryOccupy, so
	// bands ticked on different threads camping in the same province cannot double-claim
	// a plot (the loser tries the next). Null when session-less or no plot is free.
	private Plot claimCampOn(PlotCorridor corridor) {
		if (session() == null)
			return null;
		if (corridor != null && !corridor.isEmpty()) {
			List<Plot> path = corridor.path();
			for (int i = path.size() - 1; i >= 0; i--) {
				Plot p = path.get(i);
				if (p.owner() == null && p.isWorkable() && p.tryOccupy(new Camp())) {
					campPlot = p;
					return p;
				}
			}
		}
		ProvincePlotPool pool = session().provincePlotPool(worldMap().province(getProvinceId()));
		for (Plot p : pool.plots())
			if (p.owner() == null && p.isWorkable() && p.tryOccupy(new Camp())) {
				campPlot = p;
				return p;
			}
		return null;
	}

	// strike the camp at dawn: free the plot the band occupied overnight
	protected void releaseCamp() {
		if (campPlot != null) {
			campPlot.vacate();
			campPlot = null;
		}
	}

	/**
	 * Turn the nightly {@link Camp} on or off. Camping claims a plot from the band's current
	 * province's plot field, which <b>generates that field</b> — so it is opt-in: the
	 * reporting drivers (the session runner, the caravan-journey tests) enable it to fill the
	 * camp column of the march journal, while a bare wander leaves it off and pays no
	 * generation cost.
	 *
	 * @param enabled whether the band pitches a nightly camp
	 */
	@Override
	public void setCampingEnabled(boolean enabled) {
		this.campingEnabled = enabled;
		if (!enabled)
			releaseCamp();
	}

	// the daylight left after the (capped) march and the camp overhead — the window the
	// band forages and gathers in (docs/caravan-march.md)
	private double surplusHours(MarchDay day, double movedKm) {
		double hCamp = marchConfig.hCampBaseHours()
				+ marchConfig.hCampPerThousand() * following.size() / 1000.0;
		return Math.max(0, (day.daylightHours() - hCamp)
				- (movedKm + day.columnKm()) / day.speedKmh());
	}

	/**
	 * The daily forage constant: how much food a wandering band gathers depends on the
	 * <b>surplus daylight</b> (the daylight the capped march did not use) and the number of
	 * foragers, and only happens where the day's corridor crossed a <b>food</b> resource.
	 * The yield is added to the larder but capped below the daily ration, so foraging only
	 * <b>slows</b> the larder's decline — the band stays a decaying asset (see {@code
	 * docs/caravan.md}). Decisions per the design Q&amp;A.
	 *
	 * @param surplusHours the day's surplus daylight (0 halts foraging)
	 * @param corridor     the day's plot corridor (its plots' bonuses are the forageable land)
	 * @return the food foraged into the larder (0 if no surplus daylight or no food resource)
	 */
	/**
	 * The day's forage ceiling as a fraction of the band's daily ration. The default (from
	 * {@link MarchConfig}) is <b>below 1</b>, so a wandering band's foraging only <em>slows</em>
	 * its larder's decline — it stays a decaying asset (see {@code docs/caravan.md}). An
	 * {@link ExplorerCaravan}, whose whole purpose is to bring food home, raises this above 1 so
	 * its larder can <b>net-grow</b> on food-rich ground (see {@code docs/explorer-caravan.md}).
	 *
	 * @return the forage cap as a multiple of the daily ration
	 */
	protected double forageCapFraction() {
		return marchConfig.forageCapFraction();
	}

	private double forage(double surplusHours, PlotCorridor corridor) {
		if (surplusHours <= 0 || !crossedFoodResource(corridor))
			return 0;
		double ration = following.size() * WANDERING_RATION.perDay();
		double foraged = Math.min(
				surplusHours * following.size() * marchConfig.forageRatePerHour(),
				forageCapFraction() * ration);
		if (foraged > 0)
			following.stockLarder(foraged);
		return foraged;
	}

	/**
	 * <b>Gather</b> the day's corridor for non-food goods — the ore, gem, luxury and other
	 * raw {@link Bonus} resources the band {@linkplain #identifies(Bonus) identifies} on the
	 * plots it crossed — into the band's carried {@link #getCargo() cargo} (the per-good
	 * inventory of {@code docs/manufactured-bonuses.md}; the food larder is its
	 * {@code NECESSITY} special case, filled by {@link #forage} first). Gathering spends the
	 * surplus daylight foraging left over, at the slower {@code gatherRatePerHour}, split
	 * evenly across the distinct resources encountered. These are <b>discrete goods</b> (no
	 * fractional elephants): the day's work accrues as per-good <i>progress</i> and only
	 * <b>whole units</b> enter the cargo. The total is capped by the band's carrying
	 * capacity ({@code cargoCapacityPerHead} × head-count) — a full band gathers nothing
	 * more (and accrues no progress it could not carry).
	 *
	 * @param surplusHours the day's surplus daylight (before foraging spent its share)
	 * @param foraged      the food foraged today (its hours are deducted from the surplus)
	 * @param corridor     the day's plot corridor (its plots' bonuses are the gatherable land)
	 * @return the whole units gathered into the cargo (0 if no time, no resource, or no room)
	 */
	private int gather(double surplusHours, double foraged, PlotCorridor corridor) {
		List<Bonus> gatherables = gatherableBonuses(corridor);
		if (gatherables.isEmpty())
			return 0;
		int room = (int) Math.floor(following.size() * marchConfig.cargoCapacityPerHead())
				- getCargo().total();
		if (room <= 0)
			return 0;
		// foraging worked part of the surplus: the hours its yield took at the forage rate
		double hoursLeft = surplusHours
				- foraged / (following.size() * marchConfig.forageRatePerHour());
		if (hoursLeft <= 0)
			return 0;
		double each = hoursLeft * following.size() * marchConfig.gatherRatePerHour()
				/ gatherables.size();
		int gathered = 0;
		for (Bonus b : gatherables) {
			double progress = gatherProgress.merge(b.type(), each, Double::sum);
			int whole = Math.min((int) progress, room - gathered);
			if (whole > 0) {
				getCargo().add(b.type(), whole);
				gatherProgress.put(b.type(), progress - whole);
				gathered += whole;
			}
		}
		return gathered;
	}

	// whether the day's corridor crossed a food (necessity-class) resource the band can
	// identify — the land a wandering band can forage for its larder (a resource locked
	// behind a tech the band lacks is invisible to it, so it cannot forage it)
	private boolean crossedFoodResource(PlotCorridor corridor) {
		if (corridor == null)
			return false;
		for (Plot p : corridor.path())
			if (identifies(p.bonus()) && p.bonus().resourceType() == ResourceType.NECESSITY)
				return true;
		return false;
	}

	// the distinct non-food resources on the day's corridor the band can identify (in
	// first-seen order) — the land it can gather for its cargo; food is the larder's
	// (see forage), and a resource locked behind an unknown tech is invisible
	private List<Bonus> gatherableBonuses(PlotCorridor corridor) {
		if (corridor == null)
			return List.of();
		LinkedHashMap<String, Bonus> seen = new LinkedHashMap<>();
		for (Plot p : corridor.path()) {
			Bonus b = p.bonus();
			if (identifies(b) && b.resourceType() != ResourceType.NECESSITY)
				seen.putIfAbsent(b.type(), b);
		}
		return List.copyOf(seen.values());
	}

	/**
	 * Set the band's <b>tech state</b> — the tech ids it knows, which gate the resource
	 * bonuses it can {@linkplain #identifies(Bonus) identify}. A band departs with a
	 * specific tech state (a dissolution band carries its colony's research; a fresh band
	 * defaults to {@link #DEFAULT_TECH}); this overrides it.
	 *
	 * @param known the tech ids the band knows (a defensive copy is taken)
	 */
	public void setKnownTechs(Set<String> known) {
		this.knownTechs = known == null ? null : Set.copyOf(known);
	}

	// the band's known-tech set: its carried research if it has any, else the DEFAULT_TECH
	// (medieval lifestyle) baseline — the pre-known set of that tech's era. Empty for an
	// off-graph band (no session, and it does not forage/report anyway). Computed once.
	private Set<String> knownTechs() {
		if (knownTechs == null) {
			if (research != null)
				knownTechs = Set.copyOf(research.known());
			else if (session() != null) {
				TechTree tree = session().getTechTree();
				Tech def = tree.get(DEFAULT_TECH);
				Era era = def != null ? def.era() : session().getEra();
				knownTechs = Set.copyOf(tree.preKnownThrough(era));
			} else
				knownTechs = Set.of();
		}
		return knownTechs;
	}

	// whether the band can identify a resource bonus: a bonus revealed by no tech, or by a
	// tech the band knows. A bonus locked behind an unknown tech is invisible to the band —
	// it can neither forage, gather nor report it (see setKnownTechs). Null-safe (no bonus
	// -> false).
	protected boolean identifies(Bonus bonus) {
		return bonus != null
				&& (bonus.techReveal() == null || knownTechs().contains(bonus.techReveal()));
	}

	// ---- reporting ----------------------------------------------------------------------

	// compose the day's print-ready report (labels resolved against the province graph)
	private MarchReport buildReport(LocalDate date, MarchDay day, List<Integer> traversed,
			PlotCorridor corridor, double foraged, int gathered, Plot camp) {
		StringBuilder path = new StringBuilder();
		for (int id : traversed) {
			if (path.length() > 0)
				path.append("; ");
			path.append(provLabel(id));
		}
		String bonusesLabel = bonusesLabel(corridor);
		int plotsEst = (corridor != null && !corridor.isEmpty()) ? corridor.plotCount()
				: (int) Math.round(day.netMarchKm() / marchConfig.kmPerPlot());
		// the camp column omits the province (it is already the row's Province) and the
		// TERRAIN_/FEATURE_ prefixes
		String campLabel = camp == null ? "-" : campLabel(camp);
		// the "Province" column reads where the day began (the first traversal entry),
		// not where it ended
		return new MarchReport(date, journeyLabel(), provLabel(traversed.get(0)),
				day, path.toString(), bonusesLabel, plotsEst,
				following.getLastConsumed(), foraged, following.getLarder(),
				gathered, getCargo().total(), getCargo().manifest(5), campLabel);
	}

	// the notable resource bonuses encountered on the corridor — the distinct bonus names
	// (prefix-stripped) found on the crossed plots, in first-seen order; "-" when none
	private String bonusesLabel(PlotCorridor corridor) {
		if (corridor == null || corridor.isEmpty())
			return "-";
		LinkedHashSet<String> seen = new LinkedHashSet<>();
		for (Plot p : corridor.path())
			if (identifies(p.bonus()))
				seen.add(shortName(p.bonus().type()));
		return seen.isEmpty() ? "-" : String.join("; ", seen);
	}

	// "Name (id)" for a province
	private String provLabel(int id) {
		return worldMap().province(id).name() + " (" + id + ")";
	}

	// a concise descriptor of a camp plot: relief, then terrain, then feature if any — all
	// lower-case with the verbose TERRAIN_/FEATURE_ prefixes dropped (e.g. "flat plains")
	private String campLabel(Plot p) {
		String s = p.plotType().name().toLowerCase() + " " + shortName(p.terrain().type());
		if (p.feature() != null)
			s += " " + shortName(p.feature().type());
		return s;
	}

	// drop the Civ4 TERRAIN_/FEATURE_ prefix and lower-case the rest ("TERRAIN_GRASSLAND"
	// -> "grassland"), so the journal reads compactly
	protected static String shortName(String type) {
		int us = type.indexOf('_');
		return (us >= 0 ? type.substring(us + 1) : type).toLowerCase();
	}
}
