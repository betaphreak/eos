package eos.good;

/**
 * A named daily food ration, in {@link Necessity} units consumed per person per
 * day. The tiers run from a working person's full table down to bare poor relief:
 * <ul>
 * <li>{@link #GOURMET} (2.0) — a rich, indulgent table</li>
 * <li>{@link #LAVISH} (1.0) — a full working ration (what a laborer eats)</li>
 * <li>{@link #FINE} (0.5) — a comfortable but modest ration</li>
 * <li>{@link #SIMPLE} (0.25) — bare subsistence / poor relief</li>
 * </ul>
 * Used to express how much necessity an agent (or a pool of them) eats per day
 * without scattering magic numbers through the model.
 */
public enum RationSize {
	GOURMET(2.0),
	LAVISH(1.0),
	FINE(0.5),
	SIMPLE(0.25);

	private final double perDay;

	RationSize(double perDay) {
		this.perDay = perDay;
	}

	/** @return the ration in {@link Necessity} units consumed per person per day */
	public double perDay() {
		return perDay;
	}
}
