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
	CAMP(10),

	/** A camp that has put down its first building. */
	COTTAGE(10),

	/** A very limited settlement — a handful of buildings, still no districts. */
	HAMLET(20),

	/** A single-urban-plot centre with no districts (the old {@code Village}). */
	SMALLHOLDING(30),

	/** The first district-bearing rung — districts under a size cap. */
	TOWN(40),

	/**
	 * The top rung — uncapped districts and permanent; additionally gated on
	 * &ge;&thinsp;{@value #METROPOLIS_POP_GATE} people (see the growth advance). The old
	 * {@code City}. Terminal: it has no {@linkplain #upgradeDays() upgrade cost} (the {@code -1}
	 * is the {@link #TERMINAL} sentinel, inlined here — an enum constant cannot forward-reference
	 * a static field).
	 */
	METROPOLIS(-1);

	/** {@link #upgradeDays()} sentinel for the terminal rung — it never advances. */
	private static final int TERMINAL = -1;

	/**
	 * The people-count a {@link #TOWN} must reach before it can advance to {@link #METROPOLIS}
	 * (the population gate that makes a town a metropolis, beyond mere development).
	 */
	public static final int METROPOLIS_POP_GATE = 1000;

	// the days of development this rung takes to advance to the next — the Caveman2Cosmos
	// iUpgradeTime chain (Cottage 10, Hamlet 20, Village[=Smallholding] 30, Town 40), read as
	// days; CAMP (no C2C analogue) mirrors COTTAGE; METROPOLIS is TERMINAL (never advances).
	private final int upgradeDays;

	SettlementTier(int upgradeDays) {
		this.upgradeDays = upgradeDays;
	}

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
	 * The days of accumulated development (people-days) this rung takes to grow into the next —
	 * the C2C {@code iUpgradeTime} read as days. The terminal {@link #METROPOLIS} has no next rung
	 * and throws.
	 *
	 * @return this rung's upgrade cost in development-days
	 * @throws IllegalStateException
	 *             if called on the terminal {@link #METROPOLIS}
	 */
	public int upgradeDays() {
		if (upgradeDays == TERMINAL)
			throw new IllegalStateException(this + " is terminal — it has no upgrade cost");
		return upgradeDays;
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
}
