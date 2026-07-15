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
 * <b>Deferred.</b> {@code SUBURBS} (a province-level merge of several towns into one metropolis)
 * is a separate future operation, not a linear rung, so it is intentionally absent here. Per-rung
 * <b>growth data</b> — the Caveman2Cosmos {@code iUpgradeTime} chain read as days — and the
 * &ge;&thinsp;1000-people {@link #METROPOLIS} gate arrive with the growth accumulator (Phase B).
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
	 * The top rung — uncapped districts and permanent; additionally gated on &ge;&thinsp;1000
	 * people once growth is wired (Phase B). The old {@code City}.
	 */
	METROPOLIS;

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
}
