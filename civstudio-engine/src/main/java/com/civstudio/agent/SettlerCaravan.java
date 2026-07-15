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
import com.civstudio.agent.ruler.Ruler;
import com.civstudio.bank.Bank;
import com.civstudio.geo.Province;
import com.civstudio.geo.WorldMap;
import com.civstudio.good.Good;
import com.civstudio.settlement.GameSession;
import com.civstudio.settlement.Settlement;
import com.civstudio.util.Rng;
import lombok.Getter;

/**
 * The <b>dissolution-born settler</b> band: a {@link MarchingCaravan} whose goal is to
 * <b>re-found a fresh colony</b> — the settler flavor (C2C {@code UNITAI_SETTLE}). It
 * carries the {@link #getFollowing() following} and {@link #getResearch() research} of its
 * abandoned settlement, <b>wanders</b> the province graph to a viable site (or marches to a
 * fixed {@link #setDestination(int) destination}), and <b>settles</b> there (see {@code
 * docs/caravan.md}, {@code docs/caravan-trade.md}). The shared journey machinery — the
 * following/larder, the daylight-bounded march, forage/gather and the nightly camp — lives
 * on {@link MarchingCaravan}; here lives only the <em>settle</em> goal.
 * <p>
 * A {@code SettlerCaravan} is produced by <b>dissolution</b> ({@link #dissolve(Settlement)}):
 * a failing settlement crosses the <em>hinge</em> from settled to mobile — its circulating
 * money nets into the hoard, its surviving households collapse into the following, and the
 * sovereign leads the band out as its Captain.
 */
public class SettlerCaravan extends MarchingCaravan {

	// the band will not re-found with fewer than this many followers in hand — too few
	// to promote a workforce into the new colony (a viable founding needs a labor pool).
	private static final int MIN_SETTLERS = 10;

	// set once the band reaches a viable site and decides to settle; from then it stops
	// moving and awaits re-founding (see isReadyToSettle).
	@Getter
	private boolean readyToSettle = false;

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
	public SettlerCaravan(Member leader, Retinue following, double hoard, int provinceId,
			GameSession session) {
		super(leader, following, hoard, provinceId, session);
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
	public SettlerCaravan(Member leader, Retinue following, double hoard, double latitude,
			double longitude) {
		super(leader, following, hoard, latitude, longitude);
	}

	/**
	 * The band's following as its full {@link Retinue} — a settler/dissolution band always
	 * carries a real labour reserve (its people transferred out of a vanished colony), so it
	 * narrows the base {@link MarchFollowing} back to the {@link Retinue} its re-founding and
	 * dissolution paths need (promotion, {@code getMembers()}, {@code isWandering()}). Safe:
	 * a {@code SettlerCaravan} is only ever constructed with a {@code Retinue}.
	 *
	 * @return the band's following as a {@link Retinue}
	 */
	@Override
	public Retinue getFollowing() {
		return (Retinue) super.getFollowing();
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
	 * afterward. The band is led by the settlement's <b>head</b>: its {@link Ruler} if it
	 * has booted a ruler economy ({@code SMALLHOLDING} and up), else its {@link Captain}
	 * (a sub-{@code SMALLHOLDING} foraging camp that never booted — the {@code CAMP →
	 * caravan} hand-off, {@code docs/settlement-tier-ladder-plan.md} Phase E). The colony
	 * must have a living head (ruler or captain) and a {@link Retinue} (its following).
	 *
	 * @param colony
	 *            the settled colony to dissolve into a band
	 * @return the wandering migration band the colony becomes
	 */
	public static SettlerCaravan dissolve(Settlement colony) {
		// the band is led by the settlement's head: its ruler if it booted a ruler economy,
		// else its captain (a camp that never booted). Both are Households whose head Member
		// leads the band out.
		Household head = colony.getRuler();
		if (head == null || !((Agent) head).isAlive()) {
			head = null;
			for (Agent a : colony.getAgents())
				if (a instanceof Captain c && c.isAlive()) {
					head = c;
					break;
				}
		}
		if (head == null)
			throw new IllegalStateException(
					"a colony dissolves into a band led by its head, but has neither ruler nor captain");
		Member leader = head.getHead();

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
				// following). Only the head's dynasty survives, leading the band.
				if (a != head)
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
		SettlerCaravan band = (province != null && colony.getSession() != null)
				? new SettlerCaravan(leader, following, hoard, province.id(),
						colony.getSession())
				: new SettlerCaravan(leader, following, hoard, colony.getLatitude(),
						colony.getLongitude());
		// the band carries its tech tree out with it, so a re-founded colony resumes
		// research where this one left off rather than starting over
		if (colony.getResearch() != null)
			band.research = colony.getResearch().snapshot();
		return band;
	}

	// ---- the settle goal (the settler flavor's mission) ---------------------------------

	@Override
	public CaravanRole role() {
		return CaravanRole.SETTLER;
	}

	@Override
	protected boolean journeyComplete() {
		return readyToSettle;
	}

	/**
	 * Settle on arrival: at the fixed destination in directed mode, else at any viable site
	 * that isn't the one just abandoned, with a workforce still in hand. On settling the
	 * band stops moving and reports {@link #isReadyToSettle()} {@code true}, awaiting
	 * re-founding.
	 */
	@Override
	protected boolean arrive(LocalDate date, Rng rng) {
		Province here = worldMap().province(getProvinceId());
		boolean arrived = isDirected() ? atDestination()
				: (getProvinceId() != originProvinceId && isViable(here)
						&& following.size() >= MIN_SETTLERS);
		if (arrived) {
			readyToSettle = true;
			return true; // settled today — no march (and none on any later day)
		}
		return false;
	}

	@Override
	protected OptionalInt chooseWanderTarget(Rng rng) {
		return chooseTargetProvince(rng);
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
					if (!map.province(nb).isLand())
						continue; // land-only: caravans cross neither water nor impassable wasteland
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
