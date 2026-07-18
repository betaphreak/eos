package com.civstudio.agent;

import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.IntPredicate;

import com.civstudio.geo.WorldMap;
import com.civstudio.settlement.GameSession;
import com.civstudio.settlement.Settlement;
import com.civstudio.util.Rng;

/**
 * An <b>explorer</b> band: a foraging <b>levy</b> a colony musters under food pressure, which
 * marches out to gather provisions and returns to feed the settlement — the C2C
 * {@code UNITAI_EXPLORE} flavor, repurposed as the food-import expedition of
 * {@code docs/explorer-caravan.md}.
 * <p>
 * Unlike the settler band, an explorer does not own its people: its {@link DraftBand following}
 * is a <b>drafted levy</b> whose members stay accounted in their home households/pool (flagged
 * {@link Member#isDrafted() drafted}, so the colony neither works nor feeds them) and are only
 * referenced. The caravan feeds them from its carried larder, foraging as it marches — and
 * because its whole purpose is to bring food home, it forages at a <b>net-positive</b> cap
 * ({@link #forageCapFraction()} &gt; 1), unlike a decaying wandering band.
 * <p>
 * <b>The round trip.</b> The band {@link Phase#OUTBOUND marches out}, foraging; when its haul is
 * worth carrying — a full larder, or a time/low-larder cap — it {@link Phase#RETURNING turns
 * home}, and on arrival <b>deposits its surplus food into the colony's granary</b> (the strategic
 * store that feeds the starving pool and releases into the necessity market in scarcity) and
 * <b>undrafts</b> its people, who return to work, market and marriage. The paid cash-out to the
 * draftees' households and the re-entry into the wedding market (decisions 14, 19) are a later
 * cut; Phase 2 lands the food loop.
 */
public final class ExplorerCaravan extends MarchingCaravan {

	// the expedition turns home once its larder holds this many necessity units per head — a
	// haul worth carrying back (a placeholder, calibrated in the trigger phase)
	private static final double HAUL_TARGET_PER_HEAD = 30.0;

	// ...or after this many days out (so a band that never finds food still comes home)...
	private static final int MAX_DAYS_OUT = 120;

	// ...or once the larder falls to what the march home will eat — the FLOOR under that reserve,
	// in days of rations (see homewardReserve: the reserve is the road home priced in days, and
	// this is the least it may ever be, covering the last province and the day's slack)
	private static final int MIN_LARDER_DAYS = 20;

	// days a band takes to cross one province boundary — it advances at most one per day (Civ4's
	// move-to-a-new-region rule) and measures ~24 over real terrain at MarchConfig.baseMovePoints
	// 3.0, so this converts a hop count into the days the march home will take. A placeholder tied
	// to the march speed: it moves with baseMovePoints (docs/explorer-caravan.md §Phase 3).
	private static final double DAYS_PER_HOP = 24.0;

	// the margin on the priced road home — the trip home is an ESTIMATE (its terrain is the band's
	// own luck, and it may ford rivers), and running out of food one province short of home kills
	// the band, so the error is deliberately paid for on the safe side
	private static final double HOMEWARD_SAFETY = 1.25;

	// an explorer forages to NET-GROW its larder (bring food home), so its cap is above 1 —
	// unlike a wandering band, whose sub-1 cap only slows its larder's decline
	private static final double EXPLORER_FORAGE_CAP = 3.0;

	/** Where the band is in its round trip. */
	private enum Phase {
		/** marching out, foraging */
		OUTBOUND,
		/** heading back to the home settlement, its haul aboard */
		RETURNING,
		/** home: food deposited, people undrafted — the expedition is over */
		DONE
	}

	// the settlement that mustered the band and it returns to
	private final Settlement home;
	private final int homeProvinceId;
	// hops home, memoised against the province they were measured from (see homewardHops)
	private int hopsFromProvince = Integer.MIN_VALUE;
	private int cachedHomewardHops = -1;
	// the band's following, narrowed to the draft band for undrafting / draining on return
	private final DraftBand draftBand;

	// round-trip limits (defaults from the constants above; tunable for calibration/tests via
	// setTripLimits)
	private double haulTargetPerHead = HAUL_TARGET_PER_HEAD;
	private int maxDaysOut = MAX_DAYS_OUT;
	private int minLarderDays = MIN_LARDER_DAYS;

	// the date the levy should start heading home, so it arrives by mid-autumn — set at muster
	// from the home colony's season (docs/explorer-caravan.md): a band mustered in winter forages
	// through spring and summer and turns home in late summer to be back before the cold, when it
	// rejoins the settlement and works until it can go out again the next winter. Null = no
	// seasonal deadline (the caravan then turns home on haul / days-out / low-larder only).
	private LocalDate returnStartDate;

	private Phase phase = Phase.OUTBOUND;
	private int daysOut;

	// the reward handler invoked on a live return — sells the haul, ennobles the ablest returnee,
	// and founds households from the proceeds so each peasant becomes banked (the renewal loop,
	// docs/explorer-caravan.md). Wired at muster by ExplorerProvisioner; null for a directly-driven
	// band (e.g. a test), which then simply undrafts its people on return.
	private ExpeditionReturn onReturn;

	private ExplorerCaravan(Member leader, DraftBand band, Settlement home, GameSession session) {
		super(leader, band, 0, home.getProvince().id(), session);
		this.home = home;
		this.homeProvinceId = home.getProvince().id();
		this.draftBand = band;
		// the explorer forages for its haul, so it pitches a camp each night (which enables
		// the forage/gather window) as it marches
		setCampingEnabled(true);
		// embody the best explorer unit the colony can currently field (identity/art overlay only —
		// deterministic, no RNG, no march/effectiveness change; null leaves the default identity)
		Set<String> known = home.getResearch() != null ? home.getResearch().getKnown() : Set.of();
		embody(UnitCatalog.get().pickBest(CaravanRole.EXPLORER, home.getGrantedTechTokens(), known));
	}

	/**
	 * <b>Muster</b> an explorer levy from a home colony's people: flag each draftee, take the
	 * ablest as the band's leader, and set out with the given provisions. The {@code draftees}
	 * must be living working-age members already selected by the caller (the trigger — a mix of
	 * pool peasants and unmarried household adults; see {@code docs/explorer-caravan.md}); this
	 * only forms the band around them.
	 *
	 * @param home     the mustering colony (province-founded), which the band returns to
	 * @param draftees the people to draft (each is flagged {@link Member#setDrafted drafted})
	 * @param larder   the provisions loaded at muster (necessity units)
	 * @return the mustered explorer band, at the home province, marching out
	 */
	public static ExplorerCaravan muster(Settlement home, List<Member> draftees, double larder) {
		if (draftees.isEmpty())
			throw new IllegalArgumentException("an explorer levy needs at least one draftee");
		if (home.getProvince() == null || home.getSession() == null)
			throw new IllegalStateException(
					"an explorer musters from a province-founded colony in a session");
		Member leader = draftees.get(0);
		for (Member m : draftees) {
			m.setDrafted(true);
			if (m.skills().overallLevel() > leader.skills().overallLevel())
				leader = m; // the ablest leads
		}
		DraftBand band = new DraftBand(draftees, larder);
		return new ExplorerCaravan(leader, band, home, home.getSession());
	}

	@Override
	public CaravanRole role() {
		return CaravanRole.EXPLORER;
	}

	// an explorer forages to bring food home, so it net-grows its larder on food-rich ground
	@Override
	protected double forageCapFraction() {
		return EXPLORER_FORAGE_CAP;
	}

	// the explorer is the pioneer: it may cross unimproved ground and stamps a ROUTE_TRAIL on
	// every plot it walks, so the ground it explores becomes routable for the caravans that
	// follow (docs/explorer-caravan.md §Phase 3 — the explored map)
	@Override
	protected boolean laysTrail() {
		return true;
	}

	@Override
	protected boolean journeyComplete() {
		return phase == Phase.DONE;
	}

	@Override
	protected boolean arrive(LocalDate date, Rng rng) {
		switch (phase) {
			case OUTBOUND -> {
				daysOut++;
				if (shouldTurnHome(date)) {
					setDestination(homeProvinceId); // march the shortest route back
					phase = Phase.RETURNING;
				}
				return false; // either way the band marches today (out, or the first leg home)
			}
			case RETURNING -> {
				if (atDestination()) { // back at the home province
					returnHome();
					phase = Phase.DONE;
					return true; // the expedition is over — no march
				}
				return false;
			}
			default -> {
				return true;
			}
		}
	}

	// turn home when: the season says so (arrive by mid-autumn — the primary, seasonal rule), the
	// haul is a full load, the time cap is hit, or the larder is down to what the march home will
	// eat
	private boolean shouldTurnHome(LocalDate date) {
		int n = draftBand.size();
		if (n == 0)
			return true;
		// seasonal deadline: start heading home so the band is back by mid-autumn
		if (returnStartDate != null && !date.isBefore(returnStartDate))
			return true;
		double larder = draftBand.getLarder();
		double haulTarget = n * haulTargetPerHead;
		return larder >= haulTarget || daysOut >= maxDaysOut || larder <= homewardReserve(n);
	}

	/**
	 * The food the band must keep back to <b>reach home alive</b> — the reserve that turning home is
	 * triggered on.
	 * <p>
	 * This used to be a flat {@code minLarderDays} (20) of rations, which asked "have I got much
	 * food left?" and never "can I still get home?". That was safe only by accident: a random-walking
	 * levy never went far, so 20 days always covered the trip. <b>Frontier-seeking breaks that</b> —
	 * it sends bands deliberately outward, away from home, which is exactly how the
	 * {@code Dhenijansar → Wexkeep} band starved to death mid-route when its provision was sized to
	 * a shorter journey (docs/explorer-caravan.md §Phase 3).
	 * <p>
	 * So price the actual road home: a band crosses at most <b>one province boundary per day</b>
	 * (Civ4's move-to-a-new-region rule), and at {@code MarchConfig.baseMovePoints} 3.0 a crossing
	 * measures ~24 days over real terrain — so {@code hops × DAYS_PER_HOP} is the trip home, and the
	 * flat {@code minLarderDays} becomes the <b>floor</b> under it rather than the whole rule. When
	 * the route home is unknown (off-graph, no land path), fall back to that floor.
	 *
	 * @param n the band's head-count
	 * @return the necessity units the band must hold back for the march home
	 */
	private double homewardReserve(int n) {
		double perDay = n * WANDERING_RATION.perDay();
		double floor = perDay * minLarderDays;
		// the hop count only changes when the band crosses a border, so this is not re-routed daily
		int hops = homewardHops();
		if (hops < 0)
			return floor;
		return Math.max(floor, perDay * hops * DAYS_PER_HOP * HOMEWARD_SAFETY);
	}

	// hops home, cached against the province the band stands in (a LandRouter search per province
	// crossing — ~once every few weeks — rather than once a day)
	private int homewardHops() {
		if (getProvinceId() != hopsFromProvince) {
			hopsFromProvince = getProvinceId();
			cachedHomewardHops = hopsTo(homeProvinceId);
		}
		return cachedHomewardHops;
	}

	/**
	 * Set the date the levy should start heading home (so it arrives by mid-autumn) — the
	 * seasonal return deadline, set at muster from the home colony's season (see {@code
	 * docs/explorer-caravan.md}). Overrides the time/haul caps as the primary turn-home rule.
	 *
	 * @param returnStartDate the date to begin the march home
	 */
	public void setReturnBy(LocalDate returnStartDate) {
		this.returnStartDate = returnStartDate;
	}

	/**
	 * Tune the round-trip limits — when the band turns home: a per-head <b>haul target</b>
	 * (larder full), a <b>time cap</b> (days out), and a <b>low-larder</b> floor (days of food
	 * left). Placeholders pending the trigger-phase calibration; also lets a test force a short
	 * trip.
	 *
	 * @param haulTargetPerHead necessity per head that counts as a full haul
	 * @param maxDaysOut        the most days the band stays out before heading home
	 * @param minLarderDays     turn home once fewer than this many days of food remain
	 */
	public void setTripLimits(double haulTargetPerHead, int maxDaysOut, int minLarderDays) {
		this.haulTargetPerHead = haulTargetPerHead;
		this.maxDaysOut = maxDaysOut;
		this.minLarderDays = minLarderDays;
	}

	/**
	 * Set the {@link ExpeditionReturn reward handler} invoked when this band arrives home alive —
	 * the colony-side social mobility that sells the haul, ennobles the ablest returnee, and founds
	 * households from the proceeds (each landless peasant becomes banked). Wired by {@link
	 * ExplorerProvisioner} at muster; left unset for a directly-driven band, which then simply
	 * undrafts its people on return.
	 *
	 * @param onReturn the reward handler
	 */
	public void setExpeditionReturn(ExpeditionReturn onReturn) {
		this.onReturn = onReturn;
	}

	// home at last: deposit the foraged surplus into the colony's granary (which feeds the
	// starving pool and releases into the necessity market in scarcity) and undraft the levy,
	// so its people return to work, market and marriage
	private void returnHome() {
		double surplus = draftBand.drainLarder();
		if (home.getGranary() != null)
			home.getGranary().importStock(surplus);
		// the reward: sell the haul, ennoble the ablest, and found households from the proceeds so
		// each returned peasant becomes banked (docs/explorer-caravan.md — the renewal loop), handed
		// to the colony-side handler, which undrafts the people as it founds/promotes them. Without a
		// handler (a directly-driven band, e.g. a test) they are simply undrafted.
		if (onReturn != null)
			onReturn.rewardReturn(home, List.copyOf(draftBand.draftees()), getCargo().total());
		else
			for (Member m : draftBand.draftees())
				m.setDrafted(false);
		releaseCamp();
	}

	// OUTBOUND target: head for the nearest UNEXPLORED land province — the frontier. A layered BFS
	// outward from where the band stands, taking the first layer that holds ground no band has set
	// foot on; ties within a layer are broken by the band RNG, so two levies out of the same colony
	// do not file down the same corridor.
	//
	// This replaced a random walk (a uniform pick among the nearest land neighbours), which sent a
	// levy back over ground it had just trailed as readily as into new country — the band could not
	// tell explored from unexplored, because nothing recorded it.
	//
	// The frontier signal is GameSession.hasPlotPool: a pool exists precisely where someone has
	// reached into the ground (a band camped or trailed, a colony was founded), so its ABSENCE is
	// proof the province is untouched — see the note there for why the seemingly obvious test (scan
	// the plots for a route) is both wrong (urban plots are pre-paved) and expensive (asking builds
	// the field). The band still forages OPPORTUNISTICALLY once under way: it takes what its route
	// crosses (docs/explorer-caravan.md decision 11). Frontier-seeking chooses the DIRECTION, not
	// the prize — a band cannot target a bonus it has not discovered yet.
	//
	// Falls back to the old any-land-neighbour pick when nothing unexplored is reachable (a levy
	// deep inside settled country), so a band always has somewhere to go. Directed travel home
	// (RETURNING) does not use this.
	@Override
	protected OptionalInt chooseWanderTarget(Rng rng) {
		OptionalInt frontier = nearestMatching(rng, nb -> !isExplored(nb));
		return frontier.isPresent() ? frontier
				: nearestMatching(rng, nb -> nb != originProvinceId && nb != getProvinceId());
	}

	// whether a province has been reached into already (see GameSession.hasPlotPool). A session-less
	// band (a bare unit test) knows nothing, so treats everything as unexplored.
	private boolean isExplored(int provinceId) {
		return session() != null && session().hasPlotPool(provinceId);
	}

	// layered BFS over the land graph from the band's province, returning a uniform pick among the
	// NEAREST layer whose provinces satisfy `accept` (map.neighbors is precomputed, so the walk is
	// cheap; the predicate must not be, and isExplored is an O(1) map lookup)
	private OptionalInt nearestMatching(Rng rng, IntPredicate accept) {
		WorldMap map = worldMap();
		Set<Integer> visited = new HashSet<>();
		visited.add(getProvinceId());
		Deque<Integer> frontier = new ArrayDeque<>();
		frontier.add(getProvinceId());
		while (!frontier.isEmpty()) {
			List<Integer> nextLayer = new ArrayList<>();
			List<Integer> candidates = new ArrayList<>();
			while (!frontier.isEmpty()) {
				int cur = frontier.poll();
				for (int nb : map.neighbors(cur)) {
					if (!visited.add(nb) || !map.province(nb).isLand())
						continue;
					nextLayer.add(nb);
					if (nb != getProvinceId() && accept.test(nb))
						candidates.add(nb);
				}
			}
			if (!candidates.isEmpty())
				return OptionalInt.of(candidates.get(rng.uniform(candidates.size())));
			frontier.addAll(nextLayer);
		}
		return OptionalInt.empty();
	}
}
