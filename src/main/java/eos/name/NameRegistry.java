package eos.name;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import eos.util.Rng;

/**
 * The names available to a <b>single colony</b>: shared, immutable given-name
 * tables plus a private, consumable slice of the session's dynasty surnames,
 * all drawn on the colony's own naming generator. Owned per-colony (created by
 * {@code GameSession} for each {@code Settlement}), so two colonies can name
 * their households concurrently — on separate threads — without sharing any
 * mutable state.
 * <p>
 * The given-name tables ({@link NameTable}) are immutable and stateless to draw
 * from, so they are <b>shared</b> across the session's colonies; only the
 * generator they are driven with is per-colony. Dynasty surnames are
 * <b>partitioned</b>: this registry owns a {@link DynastySlice} carved off the
 * session-wide {@link DynastyPool}, draws surnames from it <b>without
 * replacement</b> (so they are unique within the colony), and — because slices
 * are pairwise disjoint — they are unique across the whole session too. When
 * the slice runs low the registry pulls another disjoint slice from the pool.
 * <p>
 * Because the surname pool is finite, an extinct dynasty's surname is
 * <b>recycled</b> back into this colony's drawable slice (see {@link
 * #releaseDynastyName(String)}); the invariant is uniqueness among <em>living</em>
 * households, not permanent consumption. Releasing a surname this registry never
 * handed out (e.g. one a migrating band carried in from another colony's slice,
 * or one already released) is a tolerated no-op — slices are disjoint, so a
 * foreign surname can never collide with one this colony would draw.
 */
public final class NameRegistry {

	private static final String DYNASTY = "/dynasty-human.json";
	private static final String MALE = "/male-human.json";
	private static final String FEMALE = "/female-human.json";

	private final Rng rng;

	// given-name tables (immutable, drawn with replacement); shared across the
	// session's colonies — only the generator above is per-colony
	private final NameTable male;
	private final NameTable female;

	// the session-wide pool this colony pulls fresh slices from when its drawable
	// surnames run out; null for a self-contained registry (it owns one fixed
	// slice and throws on exhaustion)
	private final DynastyPool pool;
	private final int refillSize;

	// the consumable drawable surnames (this colony's slices, minus those handed
	// out, plus any recycled): parallel weighted arrays, drawable in [0,
	// dynastySize). A draw swap-removes the picked entry; a release (or refill)
	// appends. Primitive arrays (no boxing) keep the weighted draw cheap; capacity
	// is grown on refill to hold every surname this registry has ever been dealt
	// (so a release always has a slot, even one from an earlier slice).
	private String[] dynastyNames = new String[0];
	private double[] dynastyWeights = new double[0];
	private int dynastySize;
	private int dealtCount; // total surnames ever dealt here (= required capacity)
	private double dynastyTotal;

	// every surname's original weight, so a recycled one re-enters with the
	// weight it was loaded with (accumulated across every slice this colony pulls)
	private final Map<String, Double> dynastyWeightByName = new HashMap<>();

	// surnames currently handed out (in use by a living dynasty of this colony); a
	// surname this registry dealt is in exactly one of {drawable, this set}
	private final Set<String> inUse = new HashSet<>();

	/**
	 * Create a <b>self-contained</b> registry owning the full dynasty pool, drawn
	 * on <tt>nameRng</tt>. Convenience for callers that do not partition across
	 * colonies (e.g. tests): it loads the whole surname table as one fixed slice
	 * with no backing pool, so it throws when the pool is exhausted rather than
	 * pulling more.
	 *
	 * @param nameRng
	 *            the random-number generator used for all name draws
	 */
	public NameRegistry(Rng nameRng) {
		this(NameTable.load(MALE), NameTable.load(FEMALE), null,
				fullDynastySlice(), 0, nameRng);
	}

	/**
	 * Create a per-colony registry that draws surnames from <tt>slice</tt> (a
	 * disjoint block carved off <tt>pool</tt>) and pulls another slice of
	 * <tt>refillSize</tt> from <tt>pool</tt> when it runs out. The given-name
	 * tables are shared (immutable); the generator is this colony's.
	 *
	 * @param male
	 *            the shared male given-name table
	 * @param female
	 *            the shared female given-name table
	 * @param pool
	 *            the session-wide surname pool to pull refills from (may be null
	 *            for a fixed, non-refilling registry)
	 * @param slice
	 *            this colony's initial disjoint surname slice
	 * @param refillSize
	 *            the size of each refill slice pulled from {@code pool}
	 * @param rng
	 *            this colony's naming generator
	 */
	public NameRegistry(NameTable male, NameTable female, DynastyPool pool,
			DynastySlice slice, int refillSize, Rng rng) {
		this.male = male;
		this.female = female;
		this.pool = pool;
		this.refillSize = refillSize;
		this.rng = rng;
		addSlice(slice);
	}

	// load the whole dynasty table as a single slice (for the self-contained ctor)
	private static DynastySlice fullDynastySlice() {
		NameTable dynasty = NameTable.load(DYNASTY);
		return new DynastySlice(dynasty.namesCopy(), dynasty.weightsCopy());
	}

	// fold a freshly dealt slice into the drawable pool, growing capacity to hold
	// every surname ever dealt here (so a later release always has a slot)
	private void addSlice(DynastySlice slice) {
		dealtCount += slice.names().length;
		if (dynastyNames.length < dealtCount) {
			dynastyNames = Arrays.copyOf(dynastyNames, dealtCount);
			dynastyWeights = Arrays.copyOf(dynastyWeights, dealtCount);
		}
		for (int i = 0; i < slice.names().length; i++) {
			String name = slice.names()[i];
			double weight = slice.weights()[i];
			dynastyNames[dynastySize] = name;
			dynastyWeights[dynastySize] = weight;
			dynastySize++;
			dynastyTotal += weight;
			dynastyWeightByName.put(name, weight);
		}
	}

	/**
	 * Draw and reserve a unique dynasty surname (weighted, without replacement).
	 * Pulls a fresh slice from the backing pool if this colony's drawable
	 * surnames are exhausted.
	 *
	 * @return a surname not currently in use by this colony (nor any other, since
	 *         slices are disjoint)
	 * @throws IllegalStateException
	 *             if the drawable pool is empty and there is no backing pool (or
	 *             it too is exhausted)
	 */
	public String nextDynastyName() {
		if (dynastySize == 0) {
			if (pool == null)
				throw new IllegalStateException("dynasty name pool exhausted");
			addSlice(pool.deal(refillSize));
		}
		double r = rng.uniform() * dynastyTotal;
		int picked = dynastySize - 1; // fall back to the last on FP slack
		for (int i = 0; i < dynastySize; i++) {
			r -= dynastyWeights[i];
			if (r < 0) {
				picked = i;
				break;
			}
		}
		// swap-remove the picked entry so it can't be drawn again while in use
		return removeAndReserve(picked);
	}

	/**
	 * Draw and reserve a unique dynasty surname from the <b>rarest tier</b> — the
	 * drawable surnames carrying the smallest loaded weight (the most distinctive
	 * houses; for the Harimari pool these are the grand clan-names). Among that
	 * rarest set the pick is uniform. Like {@link #nextDynastyName} this is a draw
	 * without replacement (pulling a fresh slice if the drawable pool is empty), so
	 * the surname stays unique to one living dynasty. Used for nobles, who carry rare
	 * dynasties rather than the common surnames the commoners draw.
	 *
	 * @return a rare surname not currently in use by this colony
	 * @throws IllegalStateException
	 *             if the drawable pool is empty and cannot be refilled
	 */
	public String nextRarestDynastyName() {
		if (dynastySize == 0) {
			if (pool == null)
				throw new IllegalStateException("dynasty name pool exhausted");
			addSlice(pool.deal(refillSize));
		}
		// find the smallest drawable weight (the rarest tier), then pick uniformly
		// among the entries sharing it — same-tier weights are identical, so an
		// exact-min match collects exactly the rarest tier
		double min = Double.POSITIVE_INFINITY;
		for (int i = 0; i < dynastySize; i++)
			if (dynastyWeights[i] < min)
				min = dynastyWeights[i];
		int count = 0;
		for (int i = 0; i < dynastySize; i++)
			if (dynastyWeights[i] == min)
				count++;
		int target = rng.uniform(count); // 0-based index within the rarest tier
		int picked = dynastySize - 1;
		for (int i = 0, k = 0; i < dynastySize; i++)
			if (dynastyWeights[i] == min && k++ == target) {
				picked = i;
				break;
			}
		return removeAndReserve(picked);
	}

	// swap-remove the drawable entry at {@code picked}, reserve it as in-use, and
	// return its surname; shared by the weighted and rarest dynasty draws
	private String removeAndReserve(int picked) {
		String name = dynastyNames[picked];
		double weight = dynastyWeights[picked];
		int last = dynastySize - 1;
		dynastyNames[picked] = dynastyNames[last];
		dynastyWeights[picked] = dynastyWeights[last];
		dynastyNames[last] = null;
		dynastySize--;
		dynastyTotal -= weight;
		inUse.add(name);
		return name;
	}

	/**
	 * Return an extinct dynasty's surname to this colony's drawable pool so it can
	 * be reused. Called when a household dies with no successor.
	 * <p>
	 * Releasing a surname that is <b>not</b> currently in use by this colony is a
	 * tolerated no-op: it may have been minted by another colony's slice (carried
	 * in by a migrating band) or already released. Slices are disjoint, so such a
	 * surname can never collide with one this colony would draw, and ignoring it
	 * keeps the cross-colony migration path working without a shared registry.
	 *
	 * @param surname
	 *            a surname to retire from use
	 */
	public void releaseDynastyName(String surname) {
		if (!inUse.remove(surname))
			return; // foreign or already released — tolerate (disjoint slices)
		double weight = dynastyWeightByName.get(surname);
		// a released surname was previously dealt here, so capacity (== dealtCount)
		// already has a slot for it; dynastySize < dealtCount whenever a name is in use
		dynastyNames[dynastySize] = surname;
		dynastyWeights[dynastySize] = weight;
		dynastySize++;
		dynastyTotal += weight;
	}

	/** Draw a male given name (weighted, with replacement). */
	public String nextMaleName() {
		return male.pick(rng);
	}

	/** Draw a female given name (weighted, with replacement). */
	public String nextFemaleName() {
		return female.pick(rng);
	}

	/**
	 * Create the head of a new household: a male given name plus a unique
	 * dynasty surname. The given name is a plain weighted draw.
	 *
	 * @return the household head
	 */
	public Person nextHead() {
		String surname = nextDynastyName();
		String givenName = nextMaleName();
		return new Person(givenName, surname);
	}

	/**
	 * Create the head of a new household whose <b>given name's rarity tracks
	 * {@code nameRarity}</b>: a male given name drawn near that rarity percentile
	 * (0 = common, 1 = rare) plus a unique dynasty surname. Used to give abler
	 * households more distinctive names.
	 *
	 * @param nameRarity
	 *            target rarity of the given name in {@code [0, 1]}
	 * @return the household head
	 */
	public Person nextHead(double nameRarity) {
		String surname = nextDynastyName();
		String givenName = male.pickAtRarity(rng, nameRarity);
		return new Person(givenName, surname);
	}

	/**
	 * Create the head of a new household whose <b>dynasty surname is drawn from the
	 * rarest tier</b> (see {@link #nextRarestDynastyName}), with a given name whose
	 * rarity tracks {@code nameRarity} exactly as {@link #nextHead(double)}. Used for
	 * nobles, who carry a rare, distinctive dynasty (e.g. a Harimari clan-name).
	 *
	 * @param nameRarity
	 *            target rarity of the given name in {@code [0, 1]}
	 * @return the household head
	 */
	public Person nextHeadRareDynasty(double nameRarity) {
		String surname = nextRarestDynastyName();
		String givenName = male.pickAtRarity(rng, nameRarity);
		return new Person(givenName, surname);
	}

	/**
	 * Create the head of a household that continues an existing dynasty: a new
	 * male given name paired with the given surname. The dynasty pool is not
	 * touched, so a household succeeding its deceased head never consumes a new
	 * (and ultimately finite) surname.
	 *
	 * @param surname
	 *            the dynasty surname to continue
	 * @return the successor household head
	 */
	public Person nextHeadInDynasty(String surname) {
		return new Person(nextMaleName(), surname);
	}

	/**
	 * Create a successor head continuing <tt>surname</tt> whose given name's
	 * rarity tracks <tt>nameRarity</tt> (0 = common, 1 = rare).
	 *
	 * @param surname
	 *            the dynasty surname to continue
	 * @param nameRarity
	 *            target rarity of the given name in {@code [0, 1]}
	 * @return the successor household head
	 */
	public Person nextHeadInDynasty(String surname, double nameRarity) {
		return new Person(male.pickAtRarity(rng, nameRarity), surname);
	}
}
