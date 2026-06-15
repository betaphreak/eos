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

	// group index by whole-year age, for ages [0, ageStart[last]] (ages above
	// clamp to the open-ended top group); precomputed so the per-step mortality
	// draw is an O(1) lookup rather than a scan of the age bands
	private final int[] groupByAge;

	// per-day death probability by whole-year age, same range and clamping;
	// precomputed so dailyDeathProb avoids the per-call log/exp on the hot path
	private final double[] dailyByAge;

	private LifeTable(int[] ageStart, double[] nqx) {
		this.ageStart = ageStart;
		this.nqx = nqx;
		int topAge = ageStart[ageStart.length - 1];
		groupByAge = new int[topAge + 1];
		for (int age = 0, i = 0; age <= topAge; age++) {
			while (i + 1 < ageStart.length && age >= ageStart[i + 1])
				i++;
			groupByAge[age] = i;
		}
		dailyByAge = new double[topAge + 1];
		for (int age = 0; age <= topAge; age++)
			dailyByAge[age] =
					1 - Math.exp(-annualHazard(age) / DAYS_PER_YEAR);
	}

	// index of the age group containing ageYears (O(1); ages outside the
	// precomputed range clamp to the first/last group)
	private int groupIndex(int ageYears) {
		if (ageYears <= 0)
			return 0;
		if (ageYears >= groupByAge.length)
			return groupByAge[groupByAge.length - 1];
		return groupByAge[ageYears];
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
		if (ageYears <= 0)
			return dailyByAge[0];
		if (ageYears >= dailyByAge.length)
			return dailyByAge[dailyByAge.length - 1];
		return dailyByAge[ageYears];
	}
}
