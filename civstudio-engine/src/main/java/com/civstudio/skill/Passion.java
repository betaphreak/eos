package com.civstudio.skill;

/**
 * A person's passion for a given {@link Skill}: how keenly they learn it.
 * Passion is per-person, per-skill, and scales the experience gained when
 * {@linkplain SkillRecord#learn(double) learning} that skill.
 */
public enum Passion {

	/** No passion: learns slowly and forgets fastest. */
	NONE(0.35, 1.0),

	/** Minor passion: the baseline learn rate; forgets more slowly. */
	MINOR(1.0, 0.5),

	/** Major passion: learns fastest and never forgets. */
	MAJOR(1.5, 0.0);

	/** Learn-rate factor for {@link #NONE}. */
	public static final double LEARN_FACTOR_PASSION_NONE = 0.35;

	/** Learn-rate factor for {@link #MINOR}. */
	public static final double LEARN_FACTOR_PASSION_MINOR = 1.0;

	/** Learn-rate factor for {@link #MAJOR}. */
	public static final double LEARN_FACTOR_PASSION_MAJOR = 1.5;

	private final double learnRateFactor;
	private final double decayRateFactor;

	Passion(double learnRateFactor, double decayRateFactor) {
		this.learnRateFactor = learnRateFactor;
		this.decayRateFactor = decayRateFactor;
	}

	/**
	 * The factor by which this passion scales experience gained while learning:
	 * {@value #LEARN_FACTOR_PASSION_NONE} for {@link #NONE},
	 * {@value #LEARN_FACTOR_PASSION_MINOR} for {@link #MINOR},
	 * {@value #LEARN_FACTOR_PASSION_MAJOR} for {@link #MAJOR}.
	 *
	 * @return the learn-rate factor
	 */
	public double learnRateFactor() {
		return learnRateFactor;
	}

	/**
	 * The factor by which this passion scales skill <b>decay</b> (forgetting): 1.0
	 * for {@link #NONE} (forgets at the full rate), 0.5 for {@link #MINOR}, and 0.0
	 * for {@link #MAJOR} (a passionately-held skill never fades). Higher passion
	 * both learns faster and decays slower.
	 *
	 * @return the decay-rate factor
	 */
	public double decayRateFactor() {
		return decayRateFactor;
	}

	/**
	 * A compact glyph for debug output: {@code ""} for {@link #NONE}, {@code "~"}
	 * for {@link #MINOR}, {@code "!"} for {@link #MAJOR} — so a skill's passion
	 * reads at a glance in a {@link SkillRecord}/{@link SkillTracker} dump.
	 *
	 * @return the passion's debug glyph
	 */
	public String symbol() {
		return switch (this) {
			case NONE -> "";
			case MINOR -> "~";
			case MAJOR -> "!";
		};
	}
}
