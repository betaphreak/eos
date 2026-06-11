package eos.mortality;

/**
 * An age-specific mortality schedule (an abridged life table). Holds, per age
 * group, the probability of dying within the group ({@code nqx}); from this it
 * derives an annual mortality hazard and a per-day death probability.
 * <p>
 * The bundled {@link #WEST_LEVEL_3} schedule is the Coale-Demeny West family,
 * Level 3 (female), with life expectancy at birth e0 = 25 — the classic
 * pre-modern / "Roman" high-mortality regime, appropriate for the model's 1444
 * setting. Source: Coale, Demeny &amp; Vaughn (1983), <i>Regional Model Life
 * Tables and Stable Populations</i>, as reproduced in B. Frier's Roman model
 * life table.
 */
public final class LifeTable {

	/** Coale-Demeny West, Level 3 (female), e0 = 25. */
	public static final LifeTable WEST_LEVEL_3 = new LifeTable(
			new int[] { 0, 1, 5, 10, 15, 20, 25, 30, 35, 40, 45, 50, 55, 60, 65,
					70, 75, 80, 85, 90, 95 },
			new double[] { .3056, .2158, .0606, .0474, .0615, .0766, .0857,
					.0965, .1054, .1123, .1197, .1529, .1912, .2715, .3484,
					.4713, .6081, .7349, .8650, .9513, 1.000 });

	private static final double DAYS_PER_YEAR = 365.25;

	// hazard for the open-ended top age group (nqx = 1): ~1 year of life left
	private static final double OPEN_GROUP_HAZARD = 1.0;

	// lower bound of each age group (ascending); last group is open-ended
	private final int[] ageStart;

	// probability of dying within each age group
	private final double[] nqx;

	private LifeTable(int[] ageStart, double[] nqx) {
		this.ageStart = ageStart;
		this.nqx = nqx;
	}

	// index of the age group containing ageYears
	private int groupIndex(int ageYears) {
		for (int i = ageStart.length - 1; i >= 0; i--)
			if (ageYears >= ageStart[i])
				return i;
		return 0;
	}

	/**
	 * Return the annual mortality hazard (central death rate) at the given age,
	 * derived from the group's nqx and width assuming a constant hazard within
	 * the interval.
	 *
	 * @param ageYears
	 *            age in whole years
	 * @return the annual mortality hazard
	 */
	public double annualHazard(int ageYears) {
		int i = groupIndex(ageYears);
		if (i == ageStart.length - 1 || nqx[i] >= 1.0)
			return OPEN_GROUP_HAZARD;
		int width = ageStart[i + 1] - ageStart[i];
		return -Math.log(1 - nqx[i]) / width;
	}

	/**
	 * Return the probability of dying on any single day at the given age.
	 *
	 * @param ageYears
	 *            age in whole years
	 * @return the per-day death probability
	 */
	public double dailyDeathProb(int ageYears) {
		return 1 - Math.exp(-annualHazard(ageYears) / DAYS_PER_YEAR);
	}
}
