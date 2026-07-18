package com.civstudio.skill;

import com.civstudio.name.Person;

/**
 * A skill a {@link Person person} can have and improve — the "type" of
 * a {@link SkillRecord}. The twelve skills are aligned with the nine
 * {@link com.civstudio.agent.CaravanRole caravan roles}: each role has one
 * <b>signature skill</b> that governs (and is trained by) a band acting in that
 * role, plus three non-role skills carrying live logic — {@code INTELLECTUAL}
 * (tech science), {@code SOCIAL} (leadership / ennoblement / marriage) and the
 * general {@code PRODUCTION} making-skill. See {@code docs/c2c-unit-import.md}
 * §Skill–role realignment. (Decay, genetic aptitudes and traits are
 * intentionally outside this model.)
 */
public enum Skill {
	STEWARDSHIP(0),   // SETTLER  — found & govern
	CONSTRUCTION(1),  // WORKER   — build routes/improvements
	SURVIVAL(2),      // EXPLORER — scout, forage, subsist
	WARFARE(3),       // MILITARY — fight
	COMMERCE(4),      // TRADE    — trade
	FAITH(5),         // MISSIONARY — proselytize
	HUNTING(6),       // HUNTER   — hunt
	MEDICINE(7),      // HEALER   — heal
	SUBTERFUGE(8),    // COVERT   — spy / crime↔order
	INTELLECTUAL(9),  // non-role — science
	SOCIAL(10),       // non-role — leadership / marriage
	PRODUCTION(11);   // non-role — general making

	// an explicit, stable index in [0, 11]. Currently equal to ordinal(), but kept
	// as its own field so the declaration order can change later without shifting
	// each skill's number (the index, not the position, is the identity callers use).
	private final int index;

	Skill(int index) {
		this.index = index;
	}

	/**
	 * This skill's stable index in {@code [0, 11]} — its identity independent of
	 * declaration order, used where a skill must be referred to by number (and as
	 * the deterministic tie-break in {@link SkillTracker#peakSkill()}).
	 *
	 * @return the skill's index
	 */
	public int index() {
		return index;
	}

	// Skill.values() copies its backing array on every call; the hot paths
	// (SkillTracker.overallLevel/totalLevel/peakLevel, tracker construction) iterate
	// the twelve skills constantly, so cache one shared copy. Callers must not mutate
	// the returned array.
	private static final Skill[] VALUES = values();

	/** The number of skills (12) — a non-allocating {@code values().length}. */
	public static final int COUNT = VALUES.length;

	/**
	 * The twelve skills as a shared, cached array (a non-allocating {@link #values()}
	 * for hot iteration). <b>Do not mutate</b> the returned array.
	 *
	 * @return the cached array of all skills, in declaration order
	 */
	public static Skill[] all() {
		return VALUES;
	}
}
