package com.civstudio.settlement;

import com.civstudio.good.Necessity;

/**
 * A hamlet's <b>larder</b> — the shared Necessity food pool a village's peasant households eat from
 * (city-of-hamlets V2, {@code docs/city-of-hamlets-plan.md}). A refactor of the per-household
 * necessity stock into one per-village balance: the village's food production drops in, its households
 * draw their provisioned rations out, and the leader buys the deficit from the shared market to hold
 * it at the floor.
 * <p>
 * This is the <b>provisioned floor</b> (the decided model): the lord feeds his vassals regardless of
 * their ability to pay — a peasant does not buy its own bread; it eats from this pool. The larder
 * holds and moves <b>food only</b>, never money (like the food box); the money side (the leader
 * paying for imports) rides the leader's bank account when the market clears.
 * <p>
 * V2 lands the pool and the flag-gated subsystem that holds one per hamlet; the storeys that route
 * eating and market imports through it are wired incrementally behind the {@code villageLarder} flag.
 */
public final class Larder {

	// the food held, as a Necessity good — the same unit households eat and the market trades, so a
	// purchased import can be delivered straight in when the market clears (V2 slice 2)
	private final Necessity food = new Necessity(0);

	/** The food currently in the larder (necessity units). */
	public double available() {
		return food.getQuantity();
	}

	/**
	 * Drop food into the larder — a day's village production (home-plot subsistence, later the
	 * leader's Necessity firm) or a purchased import. A non-positive quantity is a no-op.
	 *
	 * @param qty the food to add
	 */
	public void stock(double qty) {
		if (qty > 0)
			food.increase(qty);
	}

	/**
	 * The underlying {@link Necessity} good — the single seam every food flow touches: the market
	 * delivers a purchased import into it (the leader's buy offer targets it when the village imports
	 * its deficit), and a provisioned household eats from it directly ({@code increase} is the
	 * home-plot drop, {@code decrease} the ration draw). Package-visible: only the settlement's food
	 * machinery reaches it.
	 *
	 * @return the larder's Necessity pool
	 */
	Necessity good() {
		return food;
	}
}
