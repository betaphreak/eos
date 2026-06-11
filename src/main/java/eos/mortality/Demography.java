package eos.mortality;

import eos.util.Rng;

/**
 * The demographic service for a game session: a {@link LifeTable} plus the
 * random-number generator used for mortality draws. Owned by a
 * {@code GameSession} and shared with its economies, on a generator separate
 * from the economic one so mortality is deterministic yet does not perturb the
 * economic random stream.
 */
public final class Demography {

	private static final int DAYS_PER_YEAR = 365;

	// founding-cohort initial ages are drawn from a normal distribution centered
	// on a working age, truncated below so every founding head is of working age
	private static final double INIT_AGE_MEAN_YEARS = 35;
	private static final double INIT_AGE_STDDEV_YEARS = 10;
	private static final int MIN_INIT_AGE_YEARS = 15;

	private final Rng rng;
	private final LifeTable table;

	/**
	 * Create a demographic service drawing from <tt>rng</tt> and the default
	 * {@link LifeTable#WEST_LEVEL_3} schedule.
	 *
	 * @param rng
	 *            the generator used for all mortality draws
	 */
	public Demography(Rng rng) {
		this(rng, LifeTable.WEST_LEVEL_3);
	}

	/**
	 * Create a demographic service drawing from <tt>rng</tt> and <tt>table</tt>.
	 *
	 * @param rng
	 *            the generator used for all mortality draws
	 * @param table
	 *            the mortality schedule
	 */
	public Demography(Rng rng, LifeTable table) {
		this.rng = rng;
		this.table = table;
	}

	/**
	 * Draw an initial age (in days) for a founding household head from a normal
	 * distribution centered on a working age (mean
	 * {@value #INIT_AGE_MEAN_YEARS}, sd {@value #INIT_AGE_STDDEV_YEARS} years),
	 * truncated below at {@value #MIN_INIT_AGE_YEARS} so every founding head is
	 * of working age.
	 *
	 * @return an age in days
	 */
	public int sampleInitialAgeDays() {
		int years;
		do {
			years = (int) Math.round(
					rng.gaussian(INIT_AGE_MEAN_YEARS, INIT_AGE_STDDEV_YEARS));
		} while (years < MIN_INIT_AGE_YEARS);
		return years * DAYS_PER_YEAR + rng.uniform(DAYS_PER_YEAR);
	}

	/**
	 * Decide whether a household head of the given age dies of old age today.
	 *
	 * @param ageDays
	 *            the head's age in days
	 * @return true if the head dies of old age this step
	 */
	public boolean diesOfOldAge(int ageDays) {
		return rng.uniform() < table.dailyDeathProb(ageDays / DAYS_PER_YEAR);
	}
}
