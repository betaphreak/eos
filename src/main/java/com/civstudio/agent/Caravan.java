package com.civstudio.agent;

import com.civstudio.agent.firm.NFirm;
import com.civstudio.agent.ruler.Ruler;
import com.civstudio.bank.Bank;
import com.civstudio.good.Good;
import com.civstudio.tech.ResearchSnapshot;
import com.civstudio.good.RationSize;
import com.civstudio.settlement.Settlement;
import lombok.Getter;

/**
 * A <b>wandering band</b> — the mobile state of a household and its following while
 * <b>not settled</b>: before it founds a settlement, or after one collapses (see
 * {@code docs/caravan.md}). A {@code Caravan} is the {@link Rank#CARAVAN} rung made
 * concrete, but it is <b>not a household type</b>: it is a colony-less
 * <b>aggregate</b> holding a {@link #getLeader() leader} (the band's Captain, a
 * {@link Member}, not a distinct class), the {@link #getFollowing() following} it
 * commands, a carried {@link #getHoard() hoard} of money held outside any bank, and a
 * <b>position</b> on the map.
 * <p>
 * The {@link Retinue} is the band's persistent core (the same following-asset whether
 * settled or wandering); the {@code Caravan} adds what only a <em>mobile</em> band
 * needs — its {@link #getLeader() leader}, its {@link #getHoard() hoard}, its
 * {@link #getLatitude() position}, and the lean {@link #WANDERING_RATION} its following
 * eats from the carried larder while it cannot restock on a market.
 * <p>
 * A Caravan is produced by <b>dissolution</b> ({@link #dissolve(Settlement)}): a
 * failing settlement crosses the <em>hinge</em> from settled to mobile — its
 * circulating money nets into the hoard, its surviving households collapse into the
 * following, and the sovereign leads the band out as its Captain. That transform is a
 * bespoke settle/unsettle operation, deliberately <b>not</b> a {@link RankLadder}
 * reform, so the rank engine never sees a bankless household (see {@code
 * docs/caravan.md}). The reverse — a band re-founding a fresh colony — is the foundry's
 * job (see {@code docs/village-founding.md}).
 */
public class Caravan {

	/**
	 * The daily ration a wandering band eats from its carried larder while not
	 * settled — {@link RationSize#SNACK}, leaner even than poor relief, because a band
	 * on the move cannot restock on a market.
	 */
	public static final RationSize WANDERING_RATION = RationSize.SNACK;

	// the band's leader: the dynasty Member that commands it (the Captain, the title
	// Rank.CARAVAN carries) and becomes the holder/Ruler if the band re-founds. Not a
	// household class — just the Member, carried across the settle/unsettle hinge.
	@Getter
	private final Member leader;

	// the band's following: its unranked people and their larder, detached from any
	// settlement (the same Retinue that is a settled colony's labour reserve)
	@Getter
	private final Retinue following;

	// the band's carried hoard — its money, a copper amount held outside any bank
	// (the colony's circulating money, conserved into one figure on dissolution)
	@Getter
	private double hoard;

	// the band's geographic position in decimal degrees (north / east positive),
	// mirroring a Settlement's; mutable because a caravan moves
	@Getter
	private double latitude;
	@Getter
	private double longitude;

	// the tech tree the band carries out of its abandoned settlement (null if it never
	// had research): what it knows and was researching, restored onto the colony it
	// re-founds so progress is not lost (see Caravan.dissolve / ResearchState.restore)
	@Getter
	private ResearchSnapshot research;

	/**
	 * Create a wandering band.
	 *
	 * @param leader
	 *            the band's leader (its Captain) — the dynasty {@link Member} that
	 *            commands it
	 * @param following
	 *            the band's following (its people and carried larder)
	 * @param hoard
	 *            the band's carried money, in copper, held outside any bank
	 * @param latitude
	 *            the band's latitude in decimal degrees (north positive)
	 * @param longitude
	 *            the band's longitude in decimal degrees (east positive)
	 */
	public Caravan(Member leader, Retinue following, double hoard, double latitude,
			double longitude) {
		this.leader = leader;
		this.following = following;
		this.hoard = hoard;
		this.latitude = latitude;
		this.longitude = longitude;
		// a band on the move eats from its larder, marketless — put the following into
		// its wandering mode (the {@link #WANDERING_RATION}) for as long as it is a caravan
		following.detach();
	}

	/**
	 * <b>Dissolve</b> a failing settlement into a wandering Caravan — the {@code
	 * HOLDING → CARAVAN} hinge (see {@code docs/caravan.md}). The colony's circulating
	 * money is <b>conserved</b> into the band's hoard (every account and bank equity
	 * drained, no haircut), every surviving household disbands into the following (its
	 * members joining the pool, its larder folding into the band's), and the sovereign
	 * leads the band out as its Captain. The following is detached into the wandering
	 * mode.
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
	 * @return the wandering Caravan the colony becomes
	 */
	public static Caravan dissolve(Settlement colony) {
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

		Caravan band = new Caravan(leader, following, hoard, colony.getLatitude(),
				colony.getLongitude());
		// the band carries its tech tree out with it, so a re-founded colony resumes
		// research where this one left off rather than starting over
		if (colony.getResearch() != null)
			band.research = colony.getResearch().snapshot();
		return band;
	}

	/**
	 * Move the band to a new position (the seam the future caravan-trade geography
	 * will drive).
	 *
	 * @param latitude
	 *            the new latitude in decimal degrees (north positive)
	 * @param longitude
	 *            the new longitude in decimal degrees (east positive)
	 */
	public void moveTo(double latitude, double longitude) {
		this.latitude = latitude;
		this.longitude = longitude;
	}
}
