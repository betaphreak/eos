package com.civstudio.agent;

import java.util.ArrayList;
import java.util.List;

import com.civstudio.good.Necessity;

/**
 * The lean {@link MarchFollowing following} of an {@link ExplorerCaravan}: the <b>drafted</b>
 * levy it carries. Unlike a {@link Retinue}, a {@code DraftBand} does <b>not own</b> its people —
 * they stay accounted in their home households/pool, only <em>referenced</em> here and flagged
 * {@link Member#isDrafted() drafted} so their colony neither works nor feeds them (see {@code
 * docs/explorer-caravan.md}). So the band holds no bank, runs no market, and applies no
 * mortality (its people age and die at home): it is purely a <b>head-count + a carried larder</b>
 * that the march reads. The caravan <b>feeds</b> the levy from that larder each day (the lean
 * {@link MarchingCaravan#WANDERING_RATION}), topping it up by foraging as it marches; the food
 * it brings home is the expedition's yield.
 * <p>
 * A draftee that dies at home (its household/pool settles its estate) is pruned from the band on
 * the next {@link #act()}, so the band never carries a corpse.
 */
public final class DraftBand implements MarchFollowing {

	// the drafted people — references to Members that remain in their home households/pool
	// (flagged drafted), never owned here
	private final List<Member> draftees;

	// the food the band carries and eats from: muster provisions plus what it forages
	private final Necessity larder;

	// food eaten last act() (for the march journal)
	private double lastConsumed;

	/**
	 * Create a draft band over the given people and an opening larder.
	 *
	 * @param draftees      the drafted people (referenced, already flagged drafted by the muster)
	 * @param initialLarder the provisions loaded at muster (necessity units)
	 */
	public DraftBand(List<Member> draftees, double initialLarder) {
		this.draftees = new ArrayList<>(draftees);
		this.larder = new Necessity(Math.max(0, initialLarder));
	}

	@Override
	public void act() {
		// a draftee that died at home is settled there; drop it so the band never carries a
		// corpse (its ration is not eaten and it no longer counts toward the column)
		draftees.removeIf(m -> !m.isAlive());
		// feed the levy the lean wandering ration from the carried larder; a short larder
		// simply leaves the band hungry (no death on the march in this cut — the explorer
		// turns home on a low larder before it starves; see ExplorerCaravan)
		double wanted = draftees.size() * MarchingCaravan.WANDERING_RATION.perDay();
		lastConsumed = larder.decrease(wanted);
	}

	@Override
	public int size() {
		return draftees.size();
	}

	@Override
	public double getLarder() {
		return larder.getQuantity();
	}

	@Override
	public void stockLarder(double amount) {
		larder.increase(amount);
	}

	@Override
	public double getLastConsumed() {
		return lastConsumed;
	}

	@Override
	public void detach() {
		// a draft band is always on the road (never a settled reserve) — nothing to switch
	}

	/**
	 * The drafted people, an unmodifiable snapshot — for the caravan to <b>undraft</b> them on
	 * return (clear the flag) and to reach their home households/pool.
	 *
	 * @return the band's drafted people
	 */
	public List<Member> draftees() {
		return List.copyOf(draftees);
	}

	/** {@inheritDoc} The band's drafted people (living — dead-at-home ones are pruned each {@link
	 *  #act() step}). */
	@Override
	public List<Member> members() {
		return draftees();
	}

	/** {@inheritDoc} The promoted levy leaves the ranks to lead (its home household still accounts it
	 *  — a draftee is only referenced here). */
	@Override
	public void remove(Member member) {
		draftees.remove(member);
	}

	/**
	 * Remove and return the band's entire carried larder — the surplus the caravan deposits
	 * into its home colony's food store on return (see {@code docs/explorer-caravan.md}).
	 *
	 * @return the food drained from the larder (the larder is left empty)
	 */
	public double drainLarder() {
		return larder.decrease(larder.getQuantity());
	}
}
