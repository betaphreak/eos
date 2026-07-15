package com.civstudio.settlement;

/**
 * The rung a settlement occupies on the unified settle&hairsp;&rlarr;&hairsp;unsettle growth
 * ladder — the single source of truth for how settled/developed a band is, from the transient
 * camp a caravan sleeps in up to a full metropolis. A settlement founds low and climbs; on
 * decline it descends and, at the foot, departs as a wandering caravan again. See {@code
 * docs/settlement-tier-ladder-plan.md} and {@code docs/settlement-tiers.md}.
 * <p>
 * The rungs are ordered {@code CAMP < COTTAGE < HAMLET < SMALLHOLDING < TOWN < METROPOLIS}, and
 * {@link #atLeast(SettlementTier)} compares that order. The names are deliberately distinct from
 * the household-command {@link com.civstudio.agent.Rank} rungs — the old <em>Village</em> and
 * <em>City</em> rungs were renamed to {@link #SMALLHOLDING} and {@link #METROPOLIS} — so the two
 * axes never collide: this tier is the <em>place's</em> development, while the head household's
 * {@code Rank} (Captain&rarr;Ruler&rarr;Mayor) is <em>derived from</em> it.
 * <p>
 * Capability thresholds read off the order: a settlement {@linkplain Settlement#hasDistricts()
 * has districts} and is {@linkplain Settlement#isPermanent() permanent} from {@link #TOWN} up.
 * <p>
 * <b>Growth is food-driven (Civ4-style).</b> A settlement banks its daily net food surplus in a
 * {@linkplain Settlement#getFoodBox() food box}; when the box clears the current rung's {@link
 * #foodToChange()} it grows one rung (a sustained deficit that drains the box below {@code
 * -foodToChange()} shrinks it one rung — starvation). Two extra gates guard growth: a rung of
 * {@linkplain #size() size} <var>N</var> needs at least {@link #minHouseholds() N&sup2; households},
 * and reaching {@link #METROPOLIS} additionally needs &ge;&thinsp;{@value #METROPOLIS_POP_GATE}
 * people. The costs are size-scaled and deliberately <b>uncalibrated</b> (a tuning lever).
 * <p>
 * <b>Deferred.</b> {@code SUBURBS} (a province-level merge of several towns into one metropolis)
 * is a separate future operation, not a linear rung, so it is intentionally absent here.
 */
public enum SettlementTier {

	/** The transient plot a caravan sleeps in — no buildings; the ladder's foot. */
	CAMP,

	/** A camp that has put down its first building. */
	COTTAGE,

	/** A very limited settlement — a handful of buildings, still no districts. */
	HAMLET,

	/** A single-urban-plot centre with no districts (the old {@code Village}). */
	SMALLHOLDING,

	/** The first district-bearing rung — districts under a size cap. */
	TOWN,

	/**
	 * The top rung — uncapped districts and permanent; additionally gated on
	 * &ge;&thinsp;{@value #METROPOLIS_POP_GATE} people (see the growth advance). The old
	 * {@code City}.
	 */
	METROPOLIS;

	/**
	 * The people-count a {@link #TOWN} must reach before it can advance to {@link #METROPOLIS}
	 * (the population gate that makes a town a metropolis, beyond food and households alone).
	 */
	public static final int METROPOLIS_POP_GATE = 1000;

	// the food (net-surplus units) that changing this rung costs, per unit of size — provisional
	// and UNCALIBRATED (a tuning lever). Size-scaled so a bigger settlement both grows and starves
	// more slowly, Civ4-style. foodToChange() = size() * this.
	private static final int FOOD_PER_SIZE = 1000;

	/**
	 * Whether this tier is at least {@code other} on the ladder — i.e. this rung is {@code other}
	 * or higher. The capability tests key off this (districts and permanence begin at {@link
	 * #TOWN}).
	 *
	 * @param other
	 *            the rung to compare against
	 * @return {@code true} if this tier is {@code other} or higher
	 */
	public boolean atLeast(SettlementTier other) {
		return ordinal() >= other.ordinal();
	}

	/**
	 * This rung's <b>size</b> — its 1-based position on the ladder ({@link #CAMP} = 1 &hellip;
	 * {@link #METROPOLIS} = 6). Drives the size-scaled {@link #minHouseholds()} and {@link
	 * #foodToChange()}.
	 *
	 * @return the tier's size (1..6)
	 */
	public int size() {
		return ordinal() + 1;
	}

	/**
	 * The minimum number of <b>households</b> a settlement must hold to reach (or hold) this rung
	 * — {@link #size() size}&sup2; (Civ4-flavoured: bigger places demand quadratically more
	 * households). A growth advance is gated on the <em>target</em> rung's value.
	 *
	 * @return the household floor for this rung
	 */
	public int minHouseholds() {
		return size() * size();
	}

	/**
	 * The food (net-surplus units) that <b>changing</b> this rung costs — the {@linkplain
	 * Settlement#getFoodBox() food box} must clear {@code +foodToChange()} to grow out of this
	 * rung, or drop below {@code -foodToChange()} to starve down from it. Size-scaled and
	 * uncalibrated.
	 *
	 * @return the food cost to grow past / starve out of this rung
	 */
	public int foodToChange() {
		return size() * FOOD_PER_SIZE;
	}

	/**
	 * The rung one step up the ladder, or empty at the terminal {@link #METROPOLIS}.
	 *
	 * @return the next-higher tier, or empty at the top
	 */
	public java.util.Optional<SettlementTier> next() {
		SettlementTier[] all = values();
		return ordinal() + 1 < all.length ? java.util.Optional.of(all[ordinal() + 1])
				: java.util.Optional.empty();
	}

	/**
	 * The rung one step down the ladder, or empty at the foot {@link #CAMP}.
	 *
	 * @return the next-lower tier, or empty at the bottom
	 */
	public java.util.Optional<SettlementTier> previous() {
		return ordinal() > 0 ? java.util.Optional.of(values()[ordinal() - 1])
				: java.util.Optional.empty();
	}
}
