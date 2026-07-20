package com.civstudio.name;

import com.civstudio.util.Rng;

/**
 * The session-wide master pool of dynasty surnames, dealt out in pairwise
 * <b>disjoint</b> per-colony {@link DynastySlice slices}. This is what lets the
 * colonies of one session run on separate threads while keeping dynasty
 * surnames unique across the whole session: each colony draws and recycles only
 * within its own slice (lock-free, on its own naming generator), and the pool
 * hands out non-overlapping slices so two colonies can never mint the same
 * surname.
 * <p>
 * The master list is <b>shuffled once</b> at construction (deterministically,
 * on the session's naming generator) so each contiguous slice is a
 * representative mix of common and rare surnames rather than one colony getting
 * all the common names and the next all the rare ones. Slices are dealt
 * front-to-back; {@link #deal(int)} is {@code synchronized} because colonies may
 * be created (or re-founded from a wandering band) on different threads, but it
 * is only called at colony creation — never on a per-step hot path.
 */
public final class DynastyPool {

	// the shuffled master list and parallel weights; slices are carved off the
	// front, advancing the cursor
	private final String[] names;
	private final double[] weights;
	private int cursor;
	// completed passes over the list; > 0 means dealt surnames have begun repeating
	private int passes;

	/**
	 * Build the master pool from a loaded dynasty {@link NameTable}, shuffling it
	 * once on <tt>shuffleRng</tt> so dealt slices are representative samples.
	 *
	 * @param dynastyTable
	 *            the full loaded dynasty surname table
	 * @param shuffleRng
	 *            the generator used for the one-time shuffle (the session's naming
	 *            generator, so the shuffle is deterministic per seed)
	 */
	public DynastyPool(NameTable dynastyTable, Rng shuffleRng) {
		this.names = dynastyTable.namesCopy();
		this.weights = dynastyTable.weightsCopy();
		// Fisher–Yates: a deterministic shuffle so each contiguous slice mixes
		// common and rare surnames
		for (int i = names.length - 1; i > 0; i--) {
			int j = shuffleRng.uniform(i + 1);
			String tn = names[i];
			names[i] = names[j];
			names[j] = tn;
			double tw = weights[i];
			weights[i] = weights[j];
			weights[j] = tw;
		}
	}

	/**
	 * Deal the next slice of up to <tt>n</tt> surnames. Thread-safe (it may be
	 * called from different colony-creation threads), but intended for colony
	 * creation only — not a per-step path. If fewer than <tt>n</tt> surnames
	 * remain before the end of the list, the slice holds that remainder.
	 * <p>
	 * <b>Slices are disjoint for the first full pass, then repeat.</b> Once the
	 * list is spent the cursor wraps and surnames are dealt again rather than the
	 * pool failing. This is what lets a race with a small authored surname list
	 * people a full-size colony at all: only {@link com.civstudio.race.Race#HUMAN
	 * HUMAN} has hand-authored tables (151k surnames across 822 tiers), while every
	 * other race is imported from Anbennar's {@code anb_cultures.txt} — a couple of
	 * hundred names — against a standard colony of ~405 households. Refusing to
	 * repeat meant an elven or Anbennarian colony died mid-founding on an exhausted
	 * pool.
	 * <p>
	 * Repetition is also the realistic outcome: four hundred medieval households
	 * emphatically do <em>not</em> hold four hundred distinct surnames. Global
	 * uniqueness was the unrealistic constraint, not the shortage of names. Human
	 * runs are unaffected in practice — 151k surnames outlast any colony count this
	 * engine founds — so the wrap never fires there.
	 *
	 * @param n
	 *            the requested slice size
	 * @return a fresh slice, disjoint from earlier ones until the pool wraps
	 * @throws IllegalStateException
	 *             if the master pool holds no surnames at all
	 */
	public synchronized DynastySlice deal(int n) {
		if (names.length == 0)
			throw new IllegalStateException("dynasty master pool is empty");
		if (cursor >= names.length) {
			// a full pass has been dealt — start over rather than fail. Deterministic:
			// the list was shuffled once at construction and is never reordered.
			cursor = 0;
			passes++;
		}
		// bounded by the end of the list, so a slice never repeats a surname WITHIN
		// itself; repetition only ever happens across slices
		int take = Math.min(n, names.length - cursor);
		String[] sn = new String[take];
		double[] sw = new double[take];
		System.arraycopy(names, cursor, sn, 0, take);
		System.arraycopy(weights, cursor, sw, 0, take);
		cursor += take;
		return new DynastySlice(sn, sw);
	}

	/** Surnames left in the current pass — the count before the next wrap. */
	public synchronized int remaining() {
		return names.length - cursor;
	}

	/**
	 * How many times the pool has wrapped. Zero means every surname dealt so far is
	 * unique across the session; above zero, surnames repeat. Diagnostic — a race
	 * wrapping early is the signal its authored name list is small for the colonies
	 * being founded from it.
	 *
	 * @return the completed-pass count
	 */
	public synchronized int passes() {
		return passes;
	}

	/** The total surnames this pool was built from. */
	public synchronized int capacity() {
		return names.length;
	}
}
