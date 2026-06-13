package eos.skill;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * One person's skills: exactly one {@link SkillRecord} per {@link Skill},
 * created up front. Experience flows in through {@link #learn(Skill, double)}
 * (e.g. from doing work), and {@link #tick()} is the periodic hook the owning
 * person's step can call. Decay is intentionally excluded, so {@code tick()} is
 * a no-op for now.
 */
public final class SkillTracker {

	private final Map<Skill, SkillRecord> records;

	/** Create a tracker with every skill at {@link SkillRecord#MIN_LEVEL}. */
	public SkillTracker() {
		Map<Skill, SkillRecord> m = new EnumMap<>(Skill.class);
		for (Skill s : Skill.values())
			m.put(s, new SkillRecord());
		this.records = m;
	}

	/**
	 * Create a tracker from pre-built records (e.g. a randomized starting set).
	 * Any skill missing from <tt>records</tt> is filled with a fresh default
	 * record, so the tracker always holds all twelve.
	 *
	 * @param records
	 *            the initial records, by skill
	 */
	public SkillTracker(Map<Skill, SkillRecord> records) {
		Map<Skill, SkillRecord> m = new EnumMap<>(Skill.class);
		for (Skill s : Skill.values()) {
			SkillRecord r = records.get(s);
			m.put(s, r != null ? r : new SkillRecord());
		}
		this.records = m;
	}

	/**
	 * The record for <tt>skill</tt> (never null — every skill has a record).
	 *
	 * @param skill
	 *            the skill
	 * @return its record
	 */
	public SkillRecord getSkill(Skill skill) {
		return records.get(skill);
	}

	/**
	 * The current level of <tt>skill</tt> — shorthand for
	 * {@code getSkill(skill).getLevel()}.
	 *
	 * @param skill
	 *            the skill
	 * @return its current level, in {@code [SkillRecord.MIN_LEVEL, MAX_LEVEL]}
	 */
	public int level(Skill skill) {
		return records.get(skill).getLevel();
	}

	/**
	 * Gain <tt>xp</tt> experience in <tt>skill</tt> (see
	 * {@link SkillRecord#learn(double)}).
	 *
	 * @param skill
	 *            the skill worked
	 * @param xp
	 *            raw experience gained
	 */
	public void learn(Skill skill, double xp) {
		records.get(skill).learn(xp);
	}

	/**
	 * Advance every skill by one day: applies {@linkplain SkillRecord#decay()
	 * decay} ("forgetting") to each record. Called once per day per living person
	 * by the step loop ({@link eos.settlement.Settlement#newDay()}).
	 */
	public void tick() {
		for (SkillRecord r : records.values())
			r.decay();
	}

	/**
	 * The person's overall skill level: the mean of the twelve skill levels,
	 * rounded to the nearest integer (so it stays in
	 * {@code [SkillRecord.MIN_LEVEL, MAX_LEVEL]}). Used where the economy needs a
	 * single scalar skill (labor productivity, notability, name rarity).
	 *
	 * @return the rounded mean level across all skills
	 */
	public int overallLevel() {
		int sum = 0;
		for (SkillRecord r : records.values())
			sum += r.getLevel();
		return Math.round((float) sum / records.size());
	}

	/**
	 * An unmodifiable view of all records, by skill.
	 *
	 * @return the records
	 */
	public Map<Skill, SkillRecord> getRecords() {
		return Collections.unmodifiableMap(records);
	}
}
