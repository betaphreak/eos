package com.civstudio.agent.laborer;

import com.civstudio.good.RationSize;
import lombok.Builder;

/**
 * Tunable parameters for household fertility — when a married household bears a
 * child. A <b>colony-wide demographic property</b> (uniform across the colony's
 * households and fixed for its life, like the target necessity stock and the
 * gendered mean skills), held on the {@link com.civstudio.settlement.Settlement} and
 * read live each step by <b>every</b> household type — {@link Laborer}, {@code Noble}
 * and {@code Ruler} alike — through
 * {@code AbstractHousehold.bearChildIfFertile} (the births mechanism is universal;
 * the config lives in this package for historical reasons only).
 * <p>
 * Births are <b>on by default</b> for every colony: the canonical {@link #DEFAULT}
 * carries a non-zero {@code dailyBirthProb}, so any colony with married households
 * breeds. A run lowers it (or sets {@code 0}) only to deliberately suppress births.
 * The current default values are <b>placeholders pending calibration</b> (Phase 3 of
 * {@code docs/births.md}). Setting {@code dailyBirthProb == 0} disables births
 * entirely (no random draw is taken), leaving a run's economic stream undisturbed.
 *
 * @param dailyBirthProb
 *            per-day probability that a fertile, well-fed household bears a child;
 *            {@code 0} disables births entirely (no random draw is taken)
 * @param childbearingMinAge
 *            youngest age (whole years) at which a female member is fertile
 * @param childbearingMaxAge
 *            oldest age (whole years) at which a female member is fertile
 * @param foodBufferDays
 *            the affordability gate: the household must hold at least this many days
 *            of food for all its current members <i>plus</i> the prospective newborn
 *            before it bears a child
 * @param childRation
 *            the daily {@link RationSize ration} a child eats until it comes of age
 */
@Builder(toBuilder = true)
public record FertilityConfig(
		double dailyBirthProb,
		int childbearingMinAge,
		int childbearingMaxAge,
		double foodBufferDays,
		RationSize childRation) {

	/**
	 * The canonical values — births <b>enabled</b> at a placeholder per-day rate of
	 * {@code 0.002} (a fertile, well-fed household bears roughly one child every ~1.4
	 * years it remains eligible), a childbearing window of 15–45, a 14-day food
	 * buffer, and the {@link RationSize#SNACK} child ration. The rate and buffer are
	 * <b>placeholders pending Phase-3 calibration</b> (see {@code docs/births.md}).
	 */
	public static final FertilityConfig DEFAULT =
			new FertilityConfig(0.002, 15, 45, 14, RationSize.SNACK);
}
