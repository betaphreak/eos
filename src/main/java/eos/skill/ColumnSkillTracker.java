package eos.skill;

import java.util.Map;

/**
 * A {@link SkillTracker} backed by one <b>row of a {@link SkillColumns}</b> store
 * while the person is part of a columnar population (the {@link
 * eos.agent.Retinue}'s peasant pool): reads and writes go straight to the store's
 * primitive arrays, so the population's daily decay/learn/reduction work runs over
 * contiguous memory instead of per-person heap objects.
 * <p>
 * When the person <b>leaves</b> the store (promoted, wed, or it dies), {@link
 * SkillColumns#remove} calls {@link #detach(Map)}: the view materializes its row
 * into a standalone {@link RecordSkillTracker} and from then on delegates to that
 * copy. Because the <em>same</em> {@code SkillTracker} object stays attached to
 * the person's {@link eos.name.Person} throughout, every downstream holder keeps
 * working across the transition with no swap — the storage silently changes from
 * column-backed to record-backed underneath.
 */
public final class ColumnSkillTracker implements SkillTracker {

	// the store this view reads/writes while attached; null once detached
	private SkillColumns columns;
	// this view's row in the store (mutated in place when the store swap-removes
	// another row into this one); meaningless once detached
	private int row;
	// the materialized record-backed skills, set by detach(); null while attached
	private RecordSkillTracker detached;

	ColumnSkillTracker(SkillColumns columns, int row) {
		this.columns = columns;
		this.row = row;
	}

	// --- package hooks used by SkillColumns ---

	int row() {
		return row;
	}

	void setRow(int row) {
		this.row = row;
	}

	/**
	 * Materialize this view into a standalone record-backed tracker from the given
	 * snapshot of its row, and stop tracking the store. Called by {@link
	 * SkillColumns#remove} as the person leaves the population.
	 */
	void detach(Map<Skill, SkillRecord> snapshot) {
		this.detached = new RecordSkillTracker(snapshot);
		this.columns = null;
	}

	private boolean attached() {
		return columns != null;
	}

	// --- SkillTracker ---

	@Override
	public SkillRecord getSkill(Skill skill) {
		return attached() ? columns.snapshotRecord(row, skill)
				: detached.getSkill(skill);
	}

	@Override
	public int level(Skill skill) {
		return attached() ? columns.level(row, skill) : detached.level(skill);
	}

	@Override
	public void learn(Skill skill, double xp) {
		if (attached())
			columns.learn(row, skill, xp);
		else
			detached.learn(skill, xp);
	}

	@Override
	public void tick() {
		if (attached())
			columns.decayRow(row);
		else
			detached.tick();
	}

	@Override
	public Map<Skill, SkillRecord> getRecords() {
		return attached() ? columns.snapshotRecords(row) : detached.getRecords();
	}

	@Override
	public String toString() {
		return attached() ? "overall=" + overallLevel() + " (columnar)"
				: detached.toString();
	}
}
