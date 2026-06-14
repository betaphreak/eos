package eos.skill;

/**
 * A skill a {@link eos.name.Person person} can have and improve — the "type" of
 * a {@link SkillRecord}. The twelve skills are modelled on RimWorld's skill set.
 * (Decay, genetic aptitudes and traits are intentionally outside this model.)
 */
public enum Skill {
	CONSTRUCTION(0),
	PLANTS(1),
	INTELLECTUAL(2),
	MINING(3),
	SHOOTING(4),
	MELEE(5),
	SOCIAL(6),
	ANIMALS(7),
	COOKING(8),
	MEDICINE(9),
	ARTISTIC(10),
	CRAFTING(11);

	// an explicit, stable index in [0, 11]. Currently equal to ordinal(), but kept
	// as its own field so the declaration order can change later without shifting
	// each skill's number (the index, not the position, is the identity callers use).
	private final int index;

	Skill(int index) {
		this.index = index;
	}

	/**
	 * This skill's stable index in {@code [0, 11]} — its identity independent of
	 * declaration order, used where a skill must be referred to by number (e.g.
	 * {@link SkillTracker#peakSkill()}).
	 *
	 * @return the skill's index
	 */
	public int index() {
		return index;
	}
}
