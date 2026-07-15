package com.civstudio.agent;

import com.civstudio.bank.Bank;
import com.civstudio.good.Good;
import com.civstudio.race.Race;
import com.civstudio.settlement.Settlement;
import lombok.extern.java.Log;

/**
 * The <b>captain</b> of a caravan band — the head household of a settlement while it
 * sits on the sub-ruler {@link com.civstudio.settlement.SettlementTier tiers}
 * ({@code CAMP}/{@code COTTAGE}/{@code HAMLET}), commanding a
 * {@link Rank#CARAVAN caravan}. This realizes the long-reserved {@code CARAVAN} rung
 * (see {@code docs/rank-ladder.md} Phase 5): unlike a {@link com.civstudio.agent.ruler.Ruler}
 * (which leads a settled {@link Rank#VILLAGE}) the captain runs no economy — it holds
 * the band's <b>peasant pool</b> ({@link Retinue}) as its asset (the way a
 * {@link com.civstudio.agent.noble.Noble} holds its firms), and the band lives off the
 * pool's foraged larder rather than markets and firms (the Camp economy,
 * {@code docs/settlement-tier-ladder-plan.md} Phase D).
 * <p>
 * The captain <b>is the same entity</b> as the wandering/settler-caravan band's
 * {@linkplain Caravan#getLeader() leader}: a band that settles founds a camp led by its
 * captain, and when that camp grows to {@code SMALLHOLDING} the captain is reformed into
 * a {@link com.civstudio.agent.ruler.Ruler} (the economy boots — see the tier-advance
 * callback in {@code SimulationHarness}). It carries no goods of its own (the band's food
 * is the pool's larder), earns nothing, and — like every household — ages on the mortality
 * schedule and is succeeded by a same-dynasty heir who keeps command of the pool.
 * <p>
 * <b>Not a rank-ladder promotion target.</b> The captain rung is realized here as a
 * concrete type, but the settlement head's Captain&rarr;Ruler&rarr;Mayor changes are driven
 * from <em>tier crossings</em>, never {@link RankLadder#promote}: registering a
 * {@code CARAVAN} factory would make ennoblement (a {@code HOUSEHOLD}&rarr;{@code HOLDING}
 * promotion) land on a captain instead of a noble. The ladder therefore keeps <b>no</b>
 * factory for {@code CARAVAN}; it stays the laborer&rlarr;noble axis (see
 * {@code docs/settlement-tier-ladder-plan.md} decision D1).
 */
@Log
public class Captain extends AbstractHousehold {

	// the band's peasant pool — the captain's asset (its following), the way a Noble holds
	// its firms. The band forages its larder and lives off it; the captain commands it but
	// does not itself transact. Carried across succession to the heir. Set by the harness
	// after the pool is created (a captain and its pool are built together at founding).
	private Retinue following;

	/**
	 * Create the founding captain of a camp by <b>adopting a band's leader</b> as its head
	 * (the {@link Caravan}'s leader becomes the captain — the same bloodline that led the
	 * band settles it): the head keeps its identity (name, skills, age, dynasty), and the
	 * band's carried <tt>hoard</tt> seeds its account.
	 *
	 * @param leader
	 *            the band's leader, adopted as the camp's captain
	 * @param hoard
	 *            the band's carried money, in copper — the captain's opening balance
	 * @param bank
	 *            the (copper) bank the captain holds its account at
	 * @param colony
	 *            the colony (a camp) this captain heads
	 */
	public Captain(Member leader, double hoard, Bank bank, Settlement colony) {
		super(0, hoard, false, leader, bank, colony);
		setName("Captain");
		colony.addPersonOfInterest(this);
		log.fine(getHead().fullName() + " leads a caravan band camped to found a settlement.");
	}

	/**
	 * Create the founding captain of a fresh geographic camp by <b>drawing a new head</b>
	 * (a freshly-named dynasty of the colony's founding race) — used when a camp is founded
	 * from scratch (not born from an existing band, which brings its own leader).
	 *
	 * @param hoard
	 *            the camp's opening money, in copper
	 * @param bank
	 *            the (copper) bank the captain holds its account at
	 * @param colony
	 *            the colony (a camp) this captain heads
	 */
	public Captain(double hoard, Bank bank, Settlement colony) {
		super(0, hoard, false, null, colony.getFoundingRace(), bank, colony);
		setName("Captain");
		colony.addPersonOfInterest(this);
		log.fine(getHead().fullName() + " founded a caravan band and camped to settle.");
	}

	/**
	 * Create the captain that succeeds <tt>predecessor</tt> when its head dies: a heir of
	 * the same dynasty, inheriting the estate (out of the bank's equity, as for any
	 * household succession) and keeping command of the band's {@link Retinue pool}.
	 *
	 * @param predecessor
	 *            the deceased captain whose estate, dynasty and pool are inherited
	 * @param colony
	 *            the colony this captain heads
	 */
	public Captain(Captain predecessor, Settlement colony) {
		super(predecessor.getEstateChecking(), predecessor.getEstateSavings(), true,
				predecessor.getHead().surname(), predecessor.getHead().race(),
				predecessor.getBank(), colony);
		this.following = predecessor.following;
		setName("Captain");
		colony.addPersonOfInterest(this);
		log.fine(getHead().fullName() + " succeeded as captain of the band.");
	}

	/**
	 * The band's peasant pool — the captain's asset (its following), or {@code null} before
	 * one is attached.
	 *
	 * @return the pool this captain commands, or {@code null}
	 */
	public Retinue getFollowing() {
		return following;
	}

	/**
	 * Attach the band's peasant pool as this captain's following (its asset). Wired by the
	 * harness at founding once both the captain and the pool exist.
	 *
	 * @param following
	 *            the pool this captain commands
	 */
	public void setFollowing(Retinue following) {
		this.following = following;
	}

	/** Called by {@code Settlement.newDay()} each step. */
	public void act() {
		// the head may die of old age; its estate folds into the bank's equity and a
		// same-dynasty heir inherits it (see successor). The captain runs no economy — no
		// taxes, no firms, no market offers: the band lives off the pool's foraged larder.
		if (checkOldAgeDeath())
			return;
		// if unmarried, seek a spouse (a no-op at a camp, which has no wedding market yet)
		seekSpouseIfSingle();
		resetIncomeAccumulators(getBank().getAcct(getID()));
	}

	/** The captain holds no goods of its own — the band's food is the pool's larder. */
	public Good getGood(String goodName) {
		return null;
	}

	/** The captain's income: always zero (it runs no economy). */
	public double getIncome() {
		return 0;
	}

	/** Role label used in the persons-of-interest roster and death log. */
	@Override
	public String role() {
		return "Captain";
	}

	/** A captain commands a {@link Rank#CARAVAN} — the wandering/camped band it leads. */
	@Override
	public Rank rank() {
		return Rank.CARAVAN;
	}

	/** The captain weds above laborers (its band's own), below a settled ruler. */
	@Override
	public int weddingPriority() {
		return 1;
	}

	/**
	 * The heir who succeeds this captain: a same-dynasty captain that inherits the estate
	 * and keeps command of the band's pool. The colony's built-in replacement policy calls
	 * this, so no simulation need wire a captain rule.
	 */
	@Override
	public Agent successor(Settlement colony) {
		return new Captain(this, colony);
	}

	/**
	 * A concise, debug-friendly summary using only cached fields, so it is safe even after
	 * the captain's account has been closed.
	 */
	@Override
	public String toString() {
		int band = following == null ? 0 : following.size();
		return String.format("Captain#%d %s [%s age=%d, band=%d]", getID(),
				getHead().fullName(), isAlive() ? "alive" : "dead", getAgeYears(), band);
	}
}
