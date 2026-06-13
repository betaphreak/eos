package eos.skill;

/**
 * One person's state for one {@link Skill}: a level in
 * {@code [MIN_LEVEL, MAX_LEVEL]} and a {@link Passion}. Experience is gained
 * through {@link #learn(double)}; when accumulated experience crosses the
 * {@linkplain #xpRequiredForLevelUp(int) level-up threshold} the level advances
 * (clamped at {@value #MAX_LEVEL}). Decay is intentionally excluded.
 */
public final class SkillRecord {

	/** Lowest attainable level. */
	public static final int MIN_LEVEL = 0;

	/** Highest attainable level. */
	public static final int MAX_LEVEL = 20;

	// Decay ("forgetting") applies only above this level: basic competence is
	// permanent, but mastery erodes without practice. (Calibration placeholder.)
	private static final int DECAY_FLOOR_LEVEL = 10;

	// Per-day experience lost above the floor, per level: a higher skill decays
	// faster (so each skill settles at a level where work balances forgetting),
	// further scaled by passion (see Passion.decayRateFactor). (Placeholder.)
	private static final double DECAY_XP_PER_LEVEL_PER_DAY = 0.05;

	// Control points of the piecewise-linear XP-to-level-up curve, each {level,
	// xp}: the experience needed to advance *from* that level. Interpolated
	// linearly between points and clamped to the endpoints outside the range.
	private static final double[][] XP_CURVE = { { 0, 100 }, { 9, 1000 }, { 19, 3000 } };

	private int level;
	private Passion passion;

	// experience accumulated toward the next level-up (reset by the level gain)
	private double xpSinceLastLevel;

	/** Create a record at {@link #MIN_LEVEL} with {@link Passion#NONE}. */
	public SkillRecord() {
		this(MIN_LEVEL, Passion.NONE);
	}

	/**
	 * Create a record at the given level (clamped to
	 * {@code [MIN_LEVEL, MAX_LEVEL]}) and passion.
	 *
	 * @param level
	 *            the initial level
	 * @param passion
	 *            the passion
	 */
	public SkillRecord(int level, Passion passion) {
		this.level = clampLevel(level);
		this.passion = passion;
	}

	/** @return the current level, in {@code [MIN_LEVEL, MAX_LEVEL]} */
	public int getLevel() {
		return level;
	}

	/** @return this skill's passion */
	public Passion getPassion() {
		return passion;
	}

	/**
	 * Set this skill's passion.
	 *
	 * @param passion
	 *            the new passion
	 */
	public void setPassion(Passion passion) {
		this.passion = passion;
	}

	/** @return experience accumulated toward the next level-up */
	public double getXpSinceLastLevel() {
		return xpSinceLastLevel;
	}

	/**
	 * Gain <tt>xp</tt> raw experience in this skill, scaled by the passion's
	 * {@linkplain Passion#learnRateFactor() learn rate}, advancing the level
	 * (possibly several times) for as long as the accumulated experience meets
	 * the level-up threshold, up to {@value #MAX_LEVEL}.
	 *
	 * @param xp
	 *            raw experience gained (non-positive amounts are ignored)
	 */
	public void learn(double xp) {
		if (xp <= 0)
			return;
		xpSinceLastLevel += xp * passion.learnRateFactor();
		double needed;
		while (level < MAX_LEVEL
				&& xpSinceLastLevel >= (needed = xpRequiredForLevelUp(level))) {
			xpSinceLastLevel -= needed;
			level++;
		}
		// at max level there is nowhere to spend further experience; cap it so it
		// cannot accumulate without bound
		if (level >= MAX_LEVEL)
			xpSinceLastLevel =
					Math.min(xpSinceLastLevel, xpRequiredForLevelUp(MAX_LEVEL));
	}

	/**
	 * Apply one day of <b>decay</b> ("forgetting"): a skill above
	 * {@value #DECAY_FLOOR_LEVEL} loses experience each day — more at higher
	 * levels, scaled by the passion's {@linkplain Passion#decayRateFactor() decay
	 * factor} — possibly dropping a level. Skills at or below the floor (basic
	 * competence) never fade, and decay can never push a skill below the floor.
	 * Called once per day by {@link SkillTracker#tick()}.
	 */
	public void decay() {
		if (level <= DECAY_FLOOR_LEVEL)
			return;
		double loss = level * DECAY_XP_PER_LEVEL_PER_DAY * passion.decayRateFactor();
		if (loss <= 0)
			return;
		xpSinceLastLevel -= loss;
		// underflow drops one or more levels, carrying the deficit into the lower
		// level's bucket (the inverse of a level-up), but not past the floor
		while (xpSinceLastLevel < 0 && level > DECAY_FLOOR_LEVEL) {
			level--;
			xpSinceLastLevel += xpRequiredForLevelUp(level);
		}
		if (xpSinceLastLevel < 0)
			xpSinceLastLevel = 0; // reached the floor; cannot fade further
	}

	/**
	 * The experience needed to advance from <tt>level</tt> to the next, read off
	 * the piecewise-linear curve through (0&rarr;100), (9&rarr;1000),
	 * (19&rarr;3000), clamped to the endpoints outside that range.
	 *
	 * @param level
	 *            the level to advance from
	 * @return the experience required to level up
	 */
	public static double xpRequiredForLevelUp(int level) {
		return linearInterp(XP_CURVE, level);
	}

	/**
	 * Evaluate a piecewise-linear curve at <tt>x</tt>: linearly interpolate
	 * between the bracketing control points, clamping to the first/last point's
	 * value outside the points' x-range. Points must be sorted by ascending x.
	 *
	 * @param points
	 *            control points, each {@code {x, y}}, sorted by x
	 * @param x
	 *            the abscissa to evaluate at
	 * @return the interpolated value
	 */
	static double linearInterp(double[][] points, double x) {
		if (x <= points[0][0])
			return points[0][1];
		int last = points.length - 1;
		if (x >= points[last][0])
			return points[last][1];
		for (int i = 0; i < last; i++) {
			double x0 = points[i][0], x1 = points[i + 1][0];
			if (x <= x1) {
				double y0 = points[i][1], y1 = points[i + 1][1];
				return y0 + (y1 - y0) * (x - x0) / (x1 - x0);
			}
		}
		return points[last][1]; // unreachable: x is within range by the guards above
	}

	private static int clampLevel(int level) {
		return Math.max(MIN_LEVEL, Math.min(MAX_LEVEL, level));
	}
}
