package com.civstudio.skill;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * The object-layout {@link SkillTracker}: exactly one heap {@link SkillRecord}
 * per {@link Skill}, held in an {@link EnumMap}. This is the default for a
 * household member (a head or spouse), and the materialized form a
 * {@link ColumnSkillTracker} becomes when it leaves a {@link SkillColumns} store.
 */
public final class RecordSkillTracker implements SkillTracker {

	private final Map<Skill, SkillRecord> records;

	/** Create a tracker with every skill at {@link SkillRecord#MIN_LEVEL}. */
	public RecordSkillTracker() {
		Map<Skill, SkillRecord> m = new EnumMap<>(Skill.class);
		for (Skill s : Skill.values())
			m.put(s, new SkillRecord());
		this.records = m;
	}

	/**
	 * Create a tracker from pre-built records. Any skill missing from
	 * <tt>records</tt> is filled with a fresh default record, so the tracker always
	 * holds all twelve.
	 *
	 * @param records
	 *            the initial records, by skill
	 */
	public RecordSkillTracker(Map<Skill, SkillRecord> records) {
		Map<Skill, SkillRecord> m = new EnumMap<>(Skill.class);
		for (Skill s : Skill.values()) {
			SkillRecord r = records.get(s);
			m.put(s, r != null ? r : new SkillRecord());
		}
		this.records = m;
	}

	@Override
	public SkillRecord getSkill(Skill skill) {
		return records.get(skill);
	}

	@Override
	public int level(Skill skill) {
		return records.get(skill).getLevel();
	}

	@Override
	public void learn(Skill skill, double xp) {
		records.get(skill).learn(xp);
	}

	@Override
	public void tick() {
		for (SkillRecord r : records.values())
			r.decay();
	}

	@Override
	public Map<Skill, SkillRecord> getRecords() {
		return Collections.unmodifiableMap(records);
	}

	/**
	 * A debug-friendly rendering of all twelve skills: the {@linkplain
	 * #overallLevel() overall level} followed by every skill's
	 * {@code Name=level<passion glyph>} in {@link Skill} order.
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
