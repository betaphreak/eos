package com.civstudio.agent;

import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;

import com.civstudio.agent.firm.NFirm;
import com.civstudio.agent.march.Camp;
import com.civstudio.agent.march.March;
import com.civstudio.agent.march.MarchConfig;
import com.civstudio.agent.march.MarchDay;
import com.civstudio.agent.march.MarchFlavor;
import com.civstudio.agent.march.MarchReport;
import com.civstudio.agent.ruler.Ruler;
import com.civstudio.bank.Bank;
import com.civstudio.geo.LandRouter;
import com.civstudio.geo.Province;
import com.civstudio.geo.Route;
import com.civstudio.geo.WorldMap;
import com.civstudio.good.Good;
import com.civstudio.good.RationSize;
import com.civstudio.settlement.GameSession;
import com.civstudio.settlement.Plot;
import com.civstudio.settlement.PlotCorridor;
import com.civstudio.settlement.ProvincePlotPool;
import com.civstudio.settlement.Settlement;
import com.civstudio.settlement.SolarClock;
import com.civstudio.tech.ResearchSnapshot;
import com.civstudio.util.Rng;
import lombok.Getter;

/**
 * The <b>dissolution-born</b> wandering band: a {@link Caravan} that carries a
 * {@link #getFollowing() following} (its people and larder) and the {@link
 * #getResearch() research} of its abandoned settlement, <b>wanders</b> the province
 * graph to a viable site, and <b>re-founds</b> a fresh colony there (see {@code
 * docs/caravan.md}, {@code docs/caravan-trade.md}). The universal band state (leader,
 * hoard, position, movement) lives on the {@link Caravan} base; here lives only what a
 * <em>settler</em> band needs.
 * <p>
 * A {@code MigrantCaravan} is produced by <b>dissolution</b> ({@link
 * #dissolve(Settlement)}): a failing settlement crosses the <em>hinge</em> from
 * settled to mobile — its circulating money nets into the hoard, its surviving
 * households collapse into the following, and the sovereign leads the band out as its
 * Captain.
 * <p>
 * <b>Wandering and settling.</b> Each day ({@link #tick(LocalDate, Rng)}) an on-graph band eats
 * the lean {@link #WANDERING_RATION} from its carried larder (a decaying asset — the
 * unfed starve) and steps one hop toward the <b>nearest viable site</b> — a
 * settleable {@link Province} large enough to found into, that is not the one it just
 * abandoned. On reaching such a site with a workforce still in hand it stops and marks
 * itself {@linkplain #isReadyToSettle() ready to settle}; a runner then re-founds it
 * into that province (see {@code SimulationHarness.reFoundStandardColony} /
 * {@code GameSession.newSettlement(Caravan, …)}). All movement and the site choice
 * ride a session-level RNG (passed to {@link #tick(LocalDate, Rng)}), never a colony's economic
 * stream, so bands on the map stay reproducible.
 */
public class MigrantCaravan extends Caravan {

	/**
	 * The daily ration a wandering band eats from its carried larder while not
	 * settled — {@link RationSize#SNACK}, leaner even than poor relief, because a band
	 * on the move cannot restock on a market.
	 */
	public static final RationSize WANDERING_RATION = RationSize.SNACK;

	// the band will not re-found with fewer than this many followers in hand — too few
	// to promote a workforce into the new colony (a viable founding needs a labor pool).
	private static final int MIN_SETTLERS = 10;

	// the band's following: its unranked people and their larder, detached from any
	// settlement (the same Retinue that is a settled colony's labour reserve)
	@Getter
	private final Retinue following;

	// the province the band set out from (the one its colony just abandoned): it will
	// not re-found here, only at a fresh site. OFF_GRAPH for an off-graph band.
	private final int originProvinceId;

	// the viable site the band is currently walking toward (OFF_GRAPH = none chosen yet
	// / none reachable); recomputed when unset or reached.
	private int targetProvinceId = OFF_GRAPH;

	// set once the band reaches a viable site and decides to settle; from then it stops
	// moving and awaits re-founding (see isReadyToSettle).
	@Getter
	private boolean readyToSettle = false;

	// the tech tree the band carries out of its abandoned settlement (null if it never
	// had research): what it knows and was researching, restored onto the colony it
	// re-founds so progress is not lost (see MigrantCaravan.dissolve / ResearchState.restore)
	@Getter
	private ResearchSnapshot research;

	// the daylight-bounded march (docs/caravan-march.md): the calibration constants, the
	// distance-accurate route the band spends its daily distance along, and where it is
	// on that route (which boundary hop, and how far into it — carried across days so a
	// long hop takes several days).
	private final MarchConfig marchConfig = MarchConfig.DEFAULT;
	private LandRouter router; // lazily built over the band's world map
	private Route route;       // the current route toward the chosen target
	private int legIndex;      // the hop of `route` the band is currently crossing
	private double progressKm; // km already covered into the current leg's cost
	// the current leg's cost = the plot-corridor distance across the province the band is
	// in (KM_PER_PLOT × corridor cost) + the boundary hop into the next; and the river
	// fords on that corridor (each a full day). Computed lazily per leg (docs/caravan-march.md §6).
	private double legKmCost;
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

	// directed travel: when set (see setDestination), the band marches the route to a
	// fixed destination and settles only on arriving there, rather than wandering to the
	// nearest viable site. OFF_GRAPH = the default nearest-viable wander.
	private int destination = OFF_GRAPH;
	private boolean directed;

	/**
	 * Create an <b>on-graph</b> migration band anchored at a province, able to wander
	 * the graph and settle.
	 *
	 * @param leader     the band's leader (its Captain)
	 * @param following  the band's following (its people and carried larder)
	 * @param hoard      the band's carried money, in copper, held outside any bank
	 * @param provinceId the band's starting node on the province graph
	 * @param session    the session the band belongs to (its world map is the graph the
	 *                   band moves on; its plot pools host the nightly camp)
	 */
	public MigrantCaravan(Member leader, Retinue following, double hoard, int provinceId,
			GameSession session) {
		super(leader, hoard, provinceId, session);
		this.following = following;
		this.originProvinceId = provinceId;
		// a band on the move eats from its larder, marketless — put the following into
		// its wandering mode (the WANDERING_RATION) for as long as it is a caravan
		following.detach();
	}

	/**
	 * Create an <b>off-graph</b> migration band at bare coordinates (no province graph):
	 * it cannot wander or settle on its own (legacy bare-coordinate scenarios re-found
	 * it explicitly).
	 *
	 * @param leader    the band's leader (its Captain)
	 * @param following the band's following (its people and carried larder)
	 * @param hoard     the band's carried money, in copper, held outside any bank
	 * @param latitude  the band's latitude in decimal degrees (north positive)
	 * @param longitude the band's longitude in decimal degrees (east positive)
	 */
	public MigrantCaravan(Member leader, Retinue following, double hoard, double latitude,
			double longitude) {
		super(leader, hoard, latitude, longitude);
		this.following = following;
		this.originProvinceId = OFF_GRAPH;
		following.detach();
	}

	/**
	 * <b>Dissolve</b> a failing settlement into a wandering migration band — the {@code
	 * HOLDING → CARAVAN} hinge (see {@code docs/caravan.md}). The colony's circulating
	 * money is <b>conserved</b> into the band's hoard (every account and bank equity
	 * drained, no haircut), every surviving household disbands into the following (its
	 * members joining the pool, its larder folding into the band's), and the sovereign
	 * leads the band out as its Captain. The following is detached into the wandering
	 * mode.
	 * <p>
	 * The band is anchored to the colony's {@link Settlement#getProvince() province}
	 * when it has one (the normal path for a province-founded colony), so it starts on
	 * the graph and can wander it; a colony founded at bare coordinates (no province, or
	 * no owning session) instead yields an {@link #onGraph() off-graph} band at the
	 * colony's coordinates.
	 * <p>
	 * This <b>mutates</b> {@code colony} — it drains its banks and empties its
	 * households' larders — so the colony is expected to be discarded (it vanishes)
	 * afterward; this operation does not itself tear down the settlement or fire any
	 * trigger (that is the collapse-as-decline wiring, a later phase). The colony must
	 * have a living {@link Ruler} (the band's leader) and a {@link Retinue} (its
	 * following).
	 *
	 * @param colony
	 *            the settled colony to dissolve into a band
	 * @return the wandering migration band the colony becomes
	 */
	public static MigrantCaravan dissolve(Settlement colony) {
		Ruler ruler = colony.getRuler();
		if (ruler == null || !ruler.isAlive())
			throw new IllegalStateException(
					"a colony dissolves into a band led by its ruler, but it has none");
		Member leader = ruler.getHead();

		// the band's following is the colony's existing labour reserve
		Retinue following = null;
		for (Agent a : colony.getAgents())
			if (a instanceof Retinue r) {
				following = r;
				break;
			}
		if (following == null)
			throw new IllegalStateException(
					"a colony dissolves around its Retinue, but it has none");

		// conserve all circulating money into the hoard (accounts + equity, drained)
		double hoard = 0;
		for (Bank bank : colony.getBanks())
			hoard += bank.drainAllMoney();

		// every surviving household disbands into the following, and the colony's
		// remaining food travels with the band into its larder
		for (Agent a : colony.getAgents()) {
			if (!a.isAlive())
				continue;
			if (a instanceof Household h) {
				// the household's members (bar the leader) become pool peasants, and
				// its larder folds into the band's carried larder
				for (Member m : h.getMembers())
					if (m != leader)
						following.absorb(m);
				Good food = a.getGood("Necessity");
				if (food != null)
					following.stockLarder(food.decrease(food.getQuantity()));
				// a disbanded household's dynasty surname returns to the session-wide
				// pool (it is a household no longer — its people are now unranked
				// following). Only the leader's dynasty survives, leading the band.
				if (a != ruler)
					colony.getNames().releaseDynastyName(h.getHead().surname());
			} else if (a instanceof NFirm) {
				// an abandoned necessity firm's unsold food stores travel with the band
				// rather than being lost (its enjoyment counterpart, an EFirm's stock,
				// is simply abandoned — see docs/caravan.md)
				Good food = a.getGood("Necessity");
				if (food != null)
					following.stockLarder(food.decrease(food.getQuantity()));
			}
		}

		// anchor the band to the colony's province when it has one (so it starts on the
		// graph and can wander it); otherwise it is an off-graph band at the colony's
		// bare coordinates
		Province province = colony.getProvince();
		MigrantCaravan band = (province != null && colony.getSession() != null)
				? new MigrantCaravan(leader, following, hoard, province.id(),
						colony.getSession())
				: new MigrantCaravan(leader, following, hoard, colony.getLatitude(),
						colony.getLongitude());
		// the band carries its tech tree out with it, so a re-founded colony resumes
		// research where this one left off rather than starting over
		if (colony.getResearch() != null)
			band.research = colony.getResearch().snapshot();
		return band;
	}

	/**
	 * Advance the band by one day: eat the {@link #WANDERING_RATION} from the carried
	 * larder, then either <b>settle</b> (if standing on a viable site that is not the
	 * one just abandoned, with a workforce still in hand) or <b>step one hop</b> toward
	 * the nearest viable site. Once it decides to settle it stops moving and reports
	 * {@link #isReadyToSettle()} {@code true}, awaiting re-founding. An off-graph band,
	 * or one already ready to settle, does nothing.
	 *
	 * @param date the current in-game date (unused until the daylight-bounded march of
	 *             {@code docs/caravan-march.md} lands; threaded now so the band-tick
	 *             signature is stable)
	 * @param rng  the session-level band RNG (distinct from any colony's economic
	 *             stream), for the deterministic site choice
	 */
	@Override
	public MarchReport tick(LocalDate date, Rng rng) {
		if (readyToSettle || !onGraph())
			return null;
		// one day on the larder clock: consume the wandering ration; the unfed starve
		following.act();
		if (following.size() == 0) {
			releaseCamp();
			return null; // a spent band — no one left to settle
		}
		// dawn: strike last night's camp before deciding today's move
		releaseCamp();

		// settle on arrival: at the fixed destination in directed mode, else at any viable
		// site that isn't the one just abandoned
		Province here = worldMap().province(getProvinceId());
		boolean arrived = directed ? (getProvinceId() == destination)
				: (getProvinceId() != originProvinceId && isViable(here)
						&& following.size() >= MIN_SETTLERS);
		if (arrived) {
			readyToSettle = true;
			return null; // settled today — no march row (and none on any later day)
		}

		// the daylight-bounded day: the net distance D the band can relocate camp, from
		// the daylight at its current position and its own column length (docs/caravan-march.md)
		SolarClock clock = new SolarClock(getLatitude(), getLongitude());
		clock.update(date);
		MarchDay day = March.compute(date, following.size(), MarchFlavor.SETTLER,
				clock.getDaylightHours(), clock.getSunrise(), marchConfig);
		lastMarchDay = day;

		// ensure a distance-accurate route toward the nearest viable site
		ensureRoute(rng);

		// spend D along that route: each leg is the plot corridor across the current
		// province (KM_PER_PLOT × its plot cost — so rough/wild ground is slower) plus the
		// boundary hop into the next, with river fords costing a full day. Partial progress
		// carries across days, so a big/rough province takes several days to cross and a
		// lean band in a long day may clear several short legs (docs/caravan-march.md §6).
		List<Integer> traversed = new ArrayList<>();
		traversed.add(getProvinceId());
		double budget = day.netMarchKm();
		while (budget > 1e-9 && route != null && legIndex < route.hops()) {
			if (!legReady)
				computeLeg();
			if (legFords > 0) {
				// fording a river on this leg's corridor halts the day's advance
				legFords--;
				budget = 0;
				break;
			}
			double need = legKmCost - progressKm;
			if (budget + 1e-9 >= need) {
				budget -= need;
				progressKm = 0;
				legReady = false;
				int next = route.provinces().get(legIndex + 1);
				enteredFrom = getProvinceId(); // remember the side we cross in from
				moveTo(next);
				legIndex++;
				traversed.add(next);
			} else {
				progressKm += budget;
				budget = 0;
			}
		}

		// resolve the plot corridor across the province the day ended in (entry portal
		// from the side we came in, exit portal toward the next province) — the Level-2
		// land route (docs/land-routing.md); camp on one of its plots and report them
		PlotCorridor corridor = campingEnabled ? currentCorridor() : null;
		Plot camp = campingEnabled ? claimCampOn(corridor) : null;
		lastReport = buildReport(date, day, traversed, corridor, camp);
		return lastReport;
	}

	/**
	 * Turn the nightly {@link Camp} on or off. Camping claims a plot from the band's
	 * current province's plot field, which <b>generates that field</b> — so it is opt-in:
	 * the reporting drivers (the session runner, {@code CaravanEconomy}) enable it to fill
	 * the camp column of the march journal, while a bare wander leaves it off and pays no
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

	// (re)compute the distance-accurate route toward a viable target when the band has
	// none, has arrived, or has drifted off its current route (e.g. after a target reset).
	// The target itself is the nearest viable site (the hop-BFS below); the route is the
	// shortest-km path to it (LandRouter).
	private void ensureRoute(Rng rng) {
		boolean stale = route == null || legIndex >= route.hops()
				|| route.provinces().get(legIndex) != getProvinceId();
		if (targetProvinceId == OFF_GRAPH
				|| (!directed && targetProvinceId == getProvinceId()) || stale) {
			targetProvinceId = directed ? destination
					: chooseTargetProvince(rng).orElse(OFF_GRAPH);
			if (targetProvinceId == OFF_GRAPH) {
				route = Route.NONE;
				legIndex = 0;
				progressKm = 0;
				return;
			}
			if (router == null)
				router = new LandRouter(worldMap());
			route = router.route(getProvinceId(), targetProvinceId);
			legIndex = 0;
			progressKm = 0;
			legReady = false;
		}
	}

	// compute the current leg's cost and river fords: the plot corridor across the province
	// the band is in (its plots' move cost × KM_PER_PLOT) plus the centroid-to-centroid
	// boundary hop into the next province (docs/caravan-march.md §6, docs/land-routing.md)
	private void computeLeg() {
		int cur = getProvinceId();
		int next = route.provinces().get(legIndex + 1);
		PlotCorridor corr = corridorBetween(enteredFrom, cur, next);
		double corridorKm = corr == null ? 0 : marchConfig.kmPerPlot() * corr.totalCost();
		legKmCost = corridorKm + route.hopKm()[legIndex];
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

	/**
	 * Direct the band to march to a <b>fixed destination</b> province, settling only on
	 * arriving there — rather than the default wander to the nearest viable site. It routes
	 * over the shortest-km land path ({@link LandRouter}) and marches it under the same
	 * daylight-bounded model. Used to travel a band between two known places (e.g. the
	 * Dhenijansar&rarr;Wexkeep motivating route of {@code docs/land-routing.md}).
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
	// transient occupancy, not a settlement claim. Null when session-less or no plot is free.
	private Plot claimCampOn(PlotCorridor corridor) {
		if (session() == null)
			return null;
		if (corridor != null && !corridor.isEmpty()) {
			List<Plot> path = corridor.path();
			for (int i = path.size() - 1; i >= 0; i--) {
				Plot p = path.get(i);
				if (p.owner() == null && p.isVacant() && p.isWorkable()) {
					p.occupy(new Camp());
					campPlot = p;
					return p;
				}
			}
		}
		ProvincePlotPool pool = session().provincePlotPool(worldMap().province(getProvinceId()));
		for (Plot p : pool.plots())
			if (p.owner() == null && p.isVacant() && p.isWorkable()) {
				p.occupy(new Camp());
				campPlot = p;
				return p;
			}
		return null;
	}

	// strike the camp at dawn: free the plot the band occupied overnight
	private void releaseCamp() {
		if (campPlot != null) {
			campPlot.vacate();
			campPlot = null;
		}
	}

	// compose the day's print-ready report (labels resolved against the province graph)
	private MarchReport buildReport(LocalDate date, MarchDay day, List<Integer> traversed,
			PlotCorridor corridor, Plot camp) {
		StringBuilder path = new StringBuilder();
		for (int id : traversed) {
			if (path.length() > 0)
				path.append(" > ");
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
		return new MarchReport(date, getLeader().fullName(), provLabel(traversed.get(0)),
				day, path.toString(), bonusesLabel, plotsEst,
				following.getLastConsumed(), following.getLarder(), campLabel);
	}

	// the notable resource bonuses encountered on the corridor — the distinct bonus names
	// (prefix-stripped) found on the crossed plots, in first-seen order; "-" when none
	private String bonusesLabel(PlotCorridor corridor) {
		if (corridor == null || corridor.isEmpty())
			return "-";
		java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>();
		for (Plot p : corridor.path())
			if (p.bonus() != null)
				seen.add(shortName(p.bonus().type()));
		return seen.isEmpty() ? "-" : String.join(" > ", seen);
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
	private static String shortName(String type) {
		int us = type.indexOf('_');
		return (us >= 0 ? type.substring(us + 1) : type).toLowerCase();
	}

	// whether a province can be founded into: settleable land with at least the
	// minimum founding footprint of plots (build slots are plots — see docs/plots.md).
	private boolean isViable(Province p) {
		return p.isSettleable() && p.plots() >= Settlement.MIN_FOUNDING_PLOTS;
	}

	// the nearest viable site to settle: a layered breadth-first walk out from the
	// band's current province, returning a viable province (settleable, enough plots,
	// not the abandoned origin) at the smallest hop distance. Among equally-near
	// candidates the choice is broken on the band RNG, so different seeds explore
	// differently while staying reproducible. Empty if no viable site is reachable.
	private OptionalInt chooseTargetProvince(Rng rng) {
		WorldMap map = worldMap();
		Set<Integer> visited = new HashSet<>();
		visited.add(getProvinceId());
		Deque<Integer> frontier = new ArrayDeque<>();
		frontier.add(getProvinceId());
		while (!frontier.isEmpty()) {
			// expand one whole layer, collecting the newly reached nodes and any viable
			// candidates among them
			List<Integer> nextLayer = new ArrayList<>();
			List<Integer> viableInLayer = new ArrayList<>();
			while (!frontier.isEmpty()) {
				int cur = frontier.poll();
				for (int nb : map.neighbors(cur)) {
					if (!visited.add(nb))
						continue;
					if (!map.province(nb).isPassable())
						continue; // caravans cannot cross impassable wasteland
					nextLayer.add(nb);
					if (nb != originProvinceId && isViable(map.province(nb)))
						viableInLayer.add(nb);
				}
			}
			if (!viableInLayer.isEmpty())
				return OptionalInt.of(viableInLayer.get(rng.uniform(viableInLayer.size())));
			frontier.addAll(nextLayer);
		}
		return OptionalInt.empty();
	}
}
