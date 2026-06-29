package com.civstudio.agent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;

import com.civstudio.agent.firm.NFirm;
import com.civstudio.agent.ruler.Ruler;
import com.civstudio.bank.Bank;
import com.civstudio.geo.Province;
import com.civstudio.geo.WorldMap;
import com.civstudio.good.Good;
import com.civstudio.good.RationSize;
import com.civstudio.settlement.Settlement;
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
 * <b>Wandering and settling.</b> Each day ({@link #tick(Rng)}) an on-graph band eats
 * the lean {@link #WANDERING_RATION} from its carried larder (a decaying asset — the
 * unfed starve) and steps one hop toward the <b>nearest viable site</b> — a
 * settleable {@link Province} large enough to found into, that is not the one it just
 * abandoned. On reaching such a site with a workforce still in hand it stops and marks
 * itself {@linkplain #isReadyToSettle() ready to settle}; a runner then re-founds it
 * into that province (see {@code SimulationHarness.reFoundStandardColony} /
 * {@code GameSession.newSettlement(Caravan, …)}). All movement and the site choice
 * ride a session-level RNG (passed to {@link #tick(Rng)}), never a colony's economic
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

	/**
	 * Create an <b>on-graph</b> migration band anchored at a province, able to wander
	 * the graph and settle.
	 *
	 * @param leader     the band's leader (its Captain)
	 * @param following  the band's following (its people and carried larder)
	 * @param hoard      the band's carried money, in copper, held outside any bank
	 * @param provinceId the band's starting node on the province graph
	 * @param worldMap   the province graph the band moves on
	 */
	public MigrantCaravan(Member leader, Retinue following, double hoard, int provinceId,
			WorldMap worldMap) {
		super(leader, hoard, provinceId, worldMap);
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
						colony.getSession().getWorldMap())
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
	 * @param rng the session-level band RNG (distinct from any colony's economic
	 *            stream), for the deterministic site choice
	 */
	@Override
	public void tick(Rng rng) {
		if (readyToSettle || !onGraph())
			return;
		// one day on the larder clock: consume the wandering ration; the unfed starve
		following.act();
		if (following.size() == 0)
			return; // a spent band — no one left to settle

		// settle if standing on a viable site that isn't the one just abandoned
		Province here = worldMap().province(getProvinceId());
		if (getProvinceId() != originProvinceId && isViable(here)
				&& following.size() >= MIN_SETTLERS) {
			readyToSettle = true;
			return;
		}

		// otherwise advance one hop toward the nearest viable site
		if (targetProvinceId == OFF_GRAPH || targetProvinceId == getProvinceId())
			targetProvinceId = chooseTargetProvince(rng).orElse(OFF_GRAPH);
		if (targetProvinceId == OFF_GRAPH)
			return; // nowhere viable to reach; the larder keeps draining
		step(worldMap().path(getProvinceId(), targetProvinceId));
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
