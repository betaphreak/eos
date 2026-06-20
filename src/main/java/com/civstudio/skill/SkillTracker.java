package com.civstudio.skill;

import com.civstudio.agent.Retinue;

import java.util.Map;

/**
 * One person's skills: a level and {@link Passion} for each of the twelve
 * {@link Skill}s, with experience flowing in through {@link #learn(Skill, double)}
 * and decay applied by {@link #tick()}.
 * <p>
 * This is an <b>interface</b> so a person's skills can be stored two ways behind
 * the same API:
 * <ul>
 * <li>{@link RecordSkillTracker} — one heap {@link SkillRecord} per skill in an
 * {@link java.util.EnumMap}; the default for households (a head, a spouse).</li>
 * <li>{@link ColumnSkillTracker} — a view over a row of a {@link SkillColumns}
 * struct-of-arrays store, used for a whole population at once (the {@link
 * Retinue}'s peasant pool), so the daily decay/reduction sweeps run
 * over contiguous primitive arrays instead of chasing twelve scattered objects
 * per person. A column-backed view {@linkplain SkillColumns#remove materializes}
 * into a standalone record-backed copy when it leaves the store.</li>
 * </ul>
 * The derived queries ({@link #overallLevel()}, {@link #totalLevel()},
 * {@link #peakLevel()}, {@link #peakSkill()}) are {@code default} methods over
 * {@link #level(Skill)}, so both layouts share them.
 */
public interface SkillTracker {

	/** Create an empty tracker with every skill at {@link SkillRecord#MIN_LEVEL}. */
	static SkillTracker empty() {
		return new RecordSkillTracker();
	}

	/**
	 * Create a tracker from pre-built records (e.g. a randomized starting set);
	 * any missing skill is filled with a fresh default record.
	 *
	 * @param records
	 *            the initial records, by skill
	 * @return a record-backed tracker holding all twelve
	 */
	static SkillTracker of(Map<Skill, SkillRecord> records) {
		return new RecordSkillTracker(records);
	}

	/**
	 * The record for <tt>skill</tt> (never null — every skill has a record). For a
	 * column-backed view this is a <em>snapshot</em> of the row's current state.
	 *
	 * @param skill
	 *            the skill
	 * @return its record
	 */
	SkillRecord getSkill(Skill skill);

	/**
	 * The current level of <tt>skill</tt>.
	 *
	 * @param skill
	 *            the skill
	 * @return its current level, in {@code [SkillRecord.MIN_LEVEL, MAX_LEVEL]}
	 */
	int level(Skill skill);

	/**
	 * Gain <tt>xp</tt> experience in <tt>skill</tt> (see
	 * {@link SkillRecord#learn(double)}).
	 *
	 * @param skill
	 *            the skill worked
	 * @param xp
	 *            raw experience gained
	 */
	void learn(Skill skill, double xp);

	/**
	 * Advance every skill by one day, applying {@linkplain SkillRecord#decay()
	 * decay} ("forgetting") to each.
	 */
	void tick();

	/**
	 * An unmodifiable view of all records, by skill. For a column-backed view this
	 * is a snapshot.
	 *
	 * @return the records
	 */
	Map<Skill, SkillRecord> getRecords();

	/**
	 * The person's overall skill level: the mean of the twelve skill levels,
	 * rounded to the nearest integer. Used where the economy needs a single scalar
	 * skill (labor productivity, notability, name rarity).
	 *
	 * @return the rounded mean level across all skills
	 */
	default int overallLevel() {
		int sum = 0;
		for (Skill s : Skill.all())
			sum += level(s);
		return Math.round((float) sum / Skill.COUNT);
	}

	/**
	 * The sum of all twelve skill levels (0..240) — a person's skill-based founding
	 * endowment when promoted out of the pool.
	 *
	 * @return the sum of the twelve skill levels
	 */
	default int totalLevel() {
		int sum = 0;
		for (Skill s : Skill.all())
			sum += level(s);
		return sum;
	}

	/**
	 * The person's highest single skill level — mastery of one specialty, used to
	 * decide notability (averaging twelve skills hides a single exceptional one).
	 *
	 * @return the maximum level across all skills
	 */
	default int peakLevel() {
		int max = SkillRecord.MIN_LEVEL;
		for (Skill s : Skill.all())
			max = Math.max(max, level(s));
		return max;
	}

	/**
	 * The person's strongest skill — the {@link Skill} with the highest level, ties
	 * broken toward the lowest {@linkplain Skill#index() index}.
	 *
	 * @return the highest-level skill
	 */
	default Skill peakSkill() {
		Skill best = null;
		int bestLevel = -1;
		for (Skill s : Skill.all()) {
			int lvl = level(s);
			// strict >, with an explicit index tie-break, so the result depends on
			// the skill's index rather than the enum's iteration order
			if (lvl > bestLevel || (lvl == bestLevel && s.index() < best.index())) {
				bestLevel = lvl;
				best = s;
			}
		}
		return best;
	}
}
