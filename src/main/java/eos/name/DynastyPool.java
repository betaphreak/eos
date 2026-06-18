package eos.name;

import eos.util.Rng;

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
	 * Deal the next disjoint slice of up to <tt>n</tt> surnames. Thread-safe (it
	 * may be called from different colony-creation threads), but intended for
	 * colony creation only — not a per-step path. If fewer than <tt>n</tt>
	 * surnames remain, the slice holds the remainder; if none remain the pool is
	 * exhausted.
	 *
	 * @param n
	 *            the requested slice size
	 * @return a fresh disjoint slice
	 * @throws IllegalStateException
	 *             if the master pool is already exhausted
	 */
	public synchronized DynastySlice deal(int n) {
		int take = Math.min(n, names.length - cursor);
		if (take <= 0)
			throw new IllegalStateException("dynasty master pool exhausted");
		String[] sn = new String[take];
		double[] sw = new double[take];
		System.arraycopy(names, cursor, sn, 0, take);
		System.arraycopy(weights, cursor, sw, 0, take);
		cursor += take;
		return new DynastySlice(sn, sw);
	}

	/** Surnames not yet dealt to any colony. */
	public synchronized int remaining() {
		return names.length - cursor;
	}
}
