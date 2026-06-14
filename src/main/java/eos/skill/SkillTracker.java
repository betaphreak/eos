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
	 * The person's highest single skill level: the maximum across the twelve
	 * skills. Where {@link #overallLevel()} measures all-round competence (the
	 * mean), this captures <b>mastery of one specialty</b> — used to decide
	 * whether a person is notable, since averaging twelve skills hides a single
	 * exceptional one.
	 *
	 * @return the maximum level across all skills
	 */
	public int peakLevel() {
		int max = SkillRecord.MIN_LEVEL;
		for (SkillRecord r : records.values())
			max = Math.max(max, r.getLevel());
		return max;
	}

	/**
	 * The {@linkplain Skill#index() index} (0..11) of the person's strongest
	 * skill — the skill with the highest level, ties broken toward the lowest
	 * index. Companion to {@link #peakLevel()} (which gives that skill's level):
	 * {@code peakLevel} is <em>how good</em> the best skill is, {@code peakSkill}
	 * is <em>which</em> skill it is, identified by its stable index.
	 *
	 * @return the index of the highest-level skill
	 */
	public int peakSkill() {
		int bestLevel = -1;
		int bestIndex = -1;
		for (Skill s : Skill.values()) {
			int level = records.get(s).getLevel();
			// strict >, with an explicit index tie-break, so the result depends on
			// the skill's index rather than the enum's iteration order
			if (level > bestLevel
					|| (level == bestLevel && s.index() < bestIndex)) {
				bestLevel = level;
				bestIndex = s.index();
			}
		}
		return bestIndex;
	}

	/**
	 * An unmodifiable view of all records, by skill.
	 *
	 * @return the records
	 */
	public Map<Skill, SkillRecord> getRecords() {
		return Collections.unmodifiableMap(records);
	}

	/**
	 * A debug-friendly rendering of all twelve skills: the {@linkplain
	 * #overallLevel() overall level} followed by every skill's
	 * {@code Name=level<passion glyph>} in {@link Skill} order, e.g.
	 * {@code overall=7 {Construction=3, Plants=14!, Intellectual=8~, ...}} — so a
	 * person's whole skill profile (not just the scalar) shows in stderr logs and
	 * agent {@code toString}s.
	 */
	@Override
	public String toString() {
		StringBuilder sb =
				new StringBuilder("overall=").append(overallLevel()).append(" {");
		boolean first = true;
		for (Skill s : Skill.values()) {
			if (!first)
				sb.append(", ");
			sb.append(s).append('=').append(records.get(s));
			first = false;
		}
		return sb.append('}').toString();
	}
}
