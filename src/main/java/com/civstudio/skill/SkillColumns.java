package com.civstudio.skill;

import com.civstudio.agent.Retinue;
import com.civstudio.benchmark.SkillBenchmark;
import com.civstudio.mortality.Demography;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.Map;

/**
 * A <b>struct-of-arrays (columnar) skill store for a whole population</b> — the
 * data-oriented backing for a large, homogeneous group of people (the
 * {@link Retinue}'s peasant pool). Where a population otherwise holds
 * {@code N} {@link RecordSkillTracker}s, each an {@link EnumMap} of twelve
 * scattered heap {@link SkillRecord}s, this stores the same data as flat
 * primitive arrays: one row per person, twelve columns (skills), laid out
 * <b>skill-major</b> ({@code [skill.index() * capacity + row]}) so each skill's
 * column is contiguous across all persons. The daily decay/reduction sweeps then
 * run over contiguous memory with no pointer-chasing or virtual dispatch.
 * <p>
 * Each person is handed a {@link ColumnSkillTracker} view over its row, so the
 * rest of the model keeps using the {@link SkillTracker} API unchanged. People
 * are mobile (a peasant is promoted, married off, or dies): {@link #add} appends
 * a row and {@link #remove} frees one. Removal is a <b>swap-remove</b> (the last
 * row fills the gap so the live rows stay dense, {@code [0, size)}), and the
 * moved row's view is fixed up in place — and the <em>leaving</em> view is
 * {@linkplain ColumnSkillTracker#detach materialized} into a standalone
 * record-backed copy, so a person's skills survive intact when it leaves the
 * pool (carried into the household it is promoted or wed into).
 * <p>
 * Semantics are a faithful port of {@link SkillRecord} — the XP curve is reused
 * verbatim via {@link SkillRecord#xpRequiredForLevelUp(int)} — so a population
 * stored this way learns, decays and reduces bit-for-bit identically to the
 * object layout (see {@link SkillBenchmark}).
 */
public final class SkillColumns {

	/** Number of skills (columns) — the size of {@link Skill}. */
	public static final int SKILL_COUNT = Skill.values().length;

	// mirrors of SkillRecord's private constants (kept in sync by the port)
	private static final int DECAY_FLOOR_LEVEL = 10;
	private static final double DECAY_XP_PER_LEVEL_PER_DAY = 0.05;
	private static final int MAX_LEVEL = SkillRecord.MAX_LEVEL;

	// passion factors by Passion.ordinal(), resolved once so the inner loops read
	// a primitive array rather than calling an enum method per element
	private static final double[] PASSION_DECAY = factors(true);
	private static final double[] PASSION_LEARN = factors(false);

	// cached enum arrays (Skill.values()/Passion.values() copy on each call)
	private static final Skill[] SKILLS = Skill.values();
	private static final Passion[] PASSIONS = Passion.values();

	private int capacity;
	private int n;

	private short[] level; // [SKILL_COUNT * capacity], in [0, 20]
	private byte[] passion; // [SKILL_COUNT * capacity], Passion.ordinal()
	private double[] xp; // [SKILL_COUNT * capacity], xpSinceLastLevel
	private ColumnSkillTracker[] owners; // [capacity], the view at each live row

	/**
	 * Create an empty store sized for {@code capacity} persons (it grows on demand
	 * if more are added).
	 *
	 * @param capacity
	 *            the initial number of persons (rows) to size for
	 */
	public SkillColumns(int capacity) {
		this.capacity = Math.max(1, capacity);
		this.level = new short[SKILL_COUNT * this.capacity];
		this.passion = new byte[SKILL_COUNT * this.capacity];
		this.xp = new double[SKILL_COUNT * this.capacity];
		this.owners = new ColumnSkillTracker[this.capacity];
	}

	private static double[] factors(boolean decay) {
		Passion[] ps = Passion.values();
		double[] f = new double[ps.length];
		for (Passion p : ps)
			f[p.ordinal()] = decay ? p.decayRateFactor() : p.learnRateFactor();
		return f;
	}

	/** @return the number of live persons (rows) in the store */
	public int size() {
		return n;
	}

	/**
	 * Append a person whose skills are copied from {@code seed} (level, passion and
	 * accumulated XP per skill), returning a {@link ColumnSkillTracker} view over
	 * the new row. The seed is typically a fresh {@link RecordSkillTracker} from
	 * {@link Demography}.
	 *
	 * @param seed
	 *            the skills to copy into the new row
	 * @return a column-backed view the person carries as its {@link SkillTracker}
	 */
	public ColumnSkillTracker add(SkillTracker seed) {
		if (n == capacity)
			growTo(capacity * 2);
		int row = n++;
		for (Skill s : SKILLS) {
			SkillRecord r = seed.getSkill(s);
			int idx = s.index() * capacity + row;
			level[idx] = (short) r.getLevel();
			passion[idx] = (byte) r.getPassion().ordinal();
			xp[idx] = r.getXpSinceLastLevel();
		}
		ColumnSkillTracker view = new ColumnSkillTracker(this, row);
		owners[row] = view;
		return view;
	}

	/**
	 * Remove {@code view}'s person from the store: materialize its row into a
	 * standalone record-backed copy (so the person's skills survive its departure),
	 * then swap-remove the row so the live rows stay dense.
	 *
	 * @param view
	 *            the leaving person's column-backed view (must belong to this store)
	 */
	public void remove(ColumnSkillTracker view) {
		int r = view.row();
		// 1. snapshot the row into the leaving view, so any Member/Person still
		//    referencing it keeps working once it is out of the pool
		view.detach(snapshotRecords(r));
		// 2. swap the last live row into the gap, keeping rows [0, n) dense
		int last = n - 1;
		if (r != last) {
			for (int si = 0; si < SKILL_COUNT; si++) {
				int from = si * capacity + last;
				int to = si * capacity + r;
				level[to] = level[from];
				passion[to] = passion[from];
				xp[to] = xp[from];
			}
			ColumnSkillTracker moved = owners[last];
			owners[r] = moved;
			moved.setRow(r); // fix up the moved person's handle in place
		}
		owners[last] = null;
		n--;
	}

	/**
	 * Apply one day of decay to every person's every skill — the columnar form of
	 * calling {@link SkillTracker#tick()} on the whole population. Loops skill-major
	 * so each pass is a contiguous sweep over one skill's column across all live
	 * rows {@code [0, size)}.
	 */
	public void tickAll() {
		for (int s = 0; s < SKILL_COUNT; s++) {
			final int base = s * capacity;
			for (int i = 0; i < n; i++)
				decayAt(base + i);
		}
	}

	/**
	 * Compute every person's {@linkplain SkillTracker#overallLevel() overall level}
	 * into {@code out} in a single contiguous sweep — the batched form of the
	 * reductions the pool drives (starvation sort, promotion, spouse choice).
	 *
	 * @param out
	 *            destination, length &ge; {@link #size()}; {@code out[i]} receives
	 *            row {@code i}'s overall level
	 */
	public void overallLevels(int[] out) {
		Arrays.fill(out, 0, n, 0);
		for (int s = 0; s < SKILL_COUNT; s++) {
			final int base = s * capacity;
			for (int i = 0; i < n; i++)
				out[i] += level[base + i];
		}
		for (int i = 0; i < n; i++)
			out[i] = Math.round(out[i] / (float) SKILL_COUNT);
	}

	// ---- per-row operations backing a ColumnSkillTracker ------------------

	/** The level of one person's one skill. */
	int level(int row, Skill skill) {
		return level[skill.index() * capacity + row];
	}

	/**
	 * The level of one person's one skill — public for inspection/cross-checking
	 * (e.g. the benchmark comparing the two layouts).
	 *
	 * @param row
	 *            the person's row index
	 * @param skill
	 *            the skill
	 * @return the level in {@code [0, 20]}
	 */
	public int levelAt(int row, Skill skill) {
		return level(row, skill);
	}

	/** Gain experience in one skill of one row — the port of {@link SkillRecord#learn}. */
	void learn(int row, Skill skill, double rawXp) {
		if (rawXp <= 0)
			return;
		int idx = skill.index() * capacity + row;
		int lv = level[idx];
		double x = xp[idx] + rawXp * PASSION_LEARN[passion[idx]];
		double needed;
		while (lv < MAX_LEVEL && x >= (needed = SkillRecord.xpRequiredForLevelUp(lv))) {
			x -= needed;
			lv++;
		}
		if (lv >= MAX_LEVEL)
			x = Math.min(x, SkillRecord.xpRequiredForLevelUp(MAX_LEVEL));
		level[idx] = (short) lv;
		xp[idx] = x;
	}

	/** Decay every skill of one row — the port of {@link SkillTracker#tick()}. */
	void decayRow(int row) {
		for (Skill s : SKILLS)
			decayAt(s.index() * capacity + row);
	}

	// the decay of a single cell — the faithful port of SkillRecord.decay()
	private void decayAt(int idx) {
		int lv = level[idx];
		if (lv <= DECAY_FLOOR_LEVEL)
			return; // below the floor: basic competence never fades
		double f = PASSION_DECAY[passion[idx]];
		double loss = lv * DECAY_XP_PER_LEVEL_PER_DAY * f;
		if (loss <= 0)
			return; // MAJOR passion (factor 0): never forgets
		double x = xp[idx] - loss;
		while (x < 0 && lv > DECAY_FLOOR_LEVEL) {
			lv--;
			x += SkillRecord.xpRequiredForLevelUp(lv);
		}
		if (x < 0)
			x = 0; // reached the floor; cannot fade further
		level[idx] = (short) lv;
		xp[idx] = x;
	}

	/** A snapshot {@link SkillRecord} of one person's one skill (level, passion, XP). */
	SkillRecord snapshotRecord(int row, Skill skill) {
		int idx = skill.index() * capacity + row;
		return new SkillRecord(level[idx], PASSIONS[passion[idx]], xp[idx]);
	}

	/** A snapshot of all twelve of one person's records (the materialized row). */
	Map<Skill, SkillRecord> snapshotRecords(int row) {
		Map<Skill, SkillRecord> m = new EnumMap<>(Skill.class);
		for (Skill s : SKILLS)
			m.put(s, snapshotRecord(row, s));
		return m;
	}

	// grow the columns to hold newCap persons, re-striding the skill-major layout
	// (the stride is `capacity`, so a larger capacity relays each skill's column)
	private void growTo(int newCap) {
		short[] nl = new short[SKILL_COUNT * newCap];
		byte[] np = new byte[SKILL_COUNT * newCap];
		double[] nx = new double[SKILL_COUNT * newCap];
		for (int s = 0; s < SKILL_COUNT; s++) {
			System.arraycopy(level, s * capacity, nl, s * newCap, n);
			System.arraycopy(passion, s * capacity, np, s * newCap, n);
			System.arraycopy(xp, s * capacity, nx, s * newCap, n);
		}
		this.level = nl;
		this.passion = np;
		this.xp = nx;
		this.owners = Arrays.copyOf(owners, newCap);
		this.capacity = newCap;
	}
}
