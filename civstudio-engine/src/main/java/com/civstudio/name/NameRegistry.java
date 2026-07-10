package com.civstudio.name;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import com.civstudio.race.Race;
import com.civstudio.util.Rng;

/**
 * The names available to a <b>single colony</b>, <b>keyed by {@link Race}</b>:
 * per-race given-name tables plus a per-race, consumable slice of the session's
 * dynasty surnames, all drawn on the colony's own naming generator. Owned
 * per-colony (created by {@code GameSession} for each {@code Settlement}), so two
 * colonies can name their households concurrently — on separate threads —
 * without sharing any mutable state.
 * <p>
 * Each person is named from <b>its own race's</b> tables: the draw methods take a
 * {@link Race} (the no-{@code Race} overloads default to {@link Race#HUMAN}, so a
 * mono-cultural colony — every current scenario — behaves exactly as before). The
 * given-name tables ({@link NameTable}) are immutable and stateless to draw from,
 * so they are <b>shared</b> across the session's colonies; only the generator
 * they are driven with is per-colony. Dynasty surnames are <b>partitioned per
 * race</b>: this registry owns one {@code DynastyDraw} per race (a {@link
 * DynastySlice} carved off that race's session-wide {@link DynastyPool}), draws
 * surnames from it <b>without replacement</b> (so they are unique within the
 * colony), and — because slices are pairwise disjoint — they are unique across
 * the whole session too. When a slice runs low the draw pulls another disjoint
 * slice from its pool.
 * <p>
 * Because the surname pool is finite, an extinct dynasty's surname is
 * <b>recycled</b> back into its race's drawable slice (see {@link
 * #releaseDynastyName(String)}); the invariant is uniqueness among <em>living</em>
 * households, not permanent consumption. Releasing a surname this registry never
 * handed out (e.g. one a migrating band carried in from another colony's slice,
 * or one already released) is a tolerated no-op — slices are disjoint, so a
 * foreign surname can never collide with one this colony would draw.
 */
public final class NameRegistry {

	private static final String DYNASTY = "/names/human/dynasty.json";
	private static final String MALE = "/names/human/male.json";
	private static final String FEMALE = "/names/human/female.json";

	private final Rng rng;

	// per-race given-name tables (immutable, drawn with replacement); shared across
	// the session's colonies — only the generator above is per-colony
	private final Map<Race, NameTable> maleByRace = new EnumMap<>(Race.class);
	private final Map<Race, NameTable> femaleByRace = new EnumMap<>(Race.class);

	// per-race dynasty surname draw state (each over its own disjoint slice of its
	// race's session-wide pool, so surnames stay unique per race across the session)
	private final Map<Race, DynastyDraw> dynastyByRace = new EnumMap<>(Race.class);

	/**
	 * The consumable surname draw state for one race: this colony's slices (minus
	 * those handed out, plus any recycled), the surnames currently in use, and the
	 * backing pool to pull fresh slices from. One per race, so the
	 * surname-uniqueness invariant is per race; the pure-human path holds a single
	 * {@code HUMAN} entry behaving exactly as the old single-slice registry did.
	 */
	private static final class DynastyDraw {

		// the session-wide pool this race pulls fresh slices from when its drawable
		// surnames run out; null for a self-contained draw (one fixed slice, throws on
		// exhaustion)
		private final DynastyPool pool;
		private final int refillSize;

		// the consumable drawable surnames: parallel weighted arrays, drawable in [0,
		// size). A draw swap-removes the picked entry; a release (or refill) appends.
		// Primitive arrays (no boxing) keep the weighted draw cheap; capacity is grown
		// on refill to hold every surname ever dealt here (so a release always has a
		// slot, even one from an earlier slice).
		private String[] names = new String[0];
		private double[] weights = new double[0];
		private int size;
		private int dealtCount; // total surnames ever dealt here (= required capacity)
		private double total;

		// every surname's original weight, so a recycled one re-enters with the weight
		// it was loaded with (accumulated across every slice this race pulls)
		private final Map<String, Double> weightByName = new HashMap<>();

		// surnames currently handed out (in use by a living dynasty of this colony); a
		// surname dealt here is in exactly one of {drawable, this set}
		private final Set<String> inUse = new HashSet<>();

		DynastyDraw(DynastyPool pool, int refillSize, DynastySlice initial) {
			this.pool = pool;
			this.refillSize = refillSize;
			addSlice(initial);
		}

		// fold a freshly dealt slice into the drawable pool, growing capacity to hold
		// every surname ever dealt here (so a later release always has a slot)
		private void addSlice(DynastySlice slice) {
			dealtCount += slice.names().length;
			if (names.length < dealtCount) {
				names = Arrays.copyOf(names, dealtCount);
				weights = Arrays.copyOf(weights, dealtCount);
			}
			for (int i = 0; i < slice.names().length; i++) {
				String name = slice.names()[i];
				double weight = slice.weights()[i];
				names[size] = name;
				weights[size] = weight;
				size++;
				total += weight;
				weightByName.put(name, weight);
			}
		}

		// pull a fresh slice (or throw) when the drawable surnames are exhausted
		private void ensureDrawable() {
			if (size == 0) {
				if (pool == null)
					throw new IllegalStateException("dynasty name pool exhausted");
				addSlice(pool.deal(refillSize));
			}
		}

		// weighted draw without replacement
		String next(Rng rng) {
			ensureDrawable();
			double r = rng.uniform() * total;
			int picked = size - 1; // fall back to the last on FP slack
			for (int i = 0; i < size; i++) {
				r -= weights[i];
				if (r < 0) {
					picked = i;
					break;
				}
			}
			return removeAndReserve(picked);
		}

		// draw uniformly from the rarest (smallest-weight) tier, without replacement
		String nextRarest(Rng rng) {
			ensureDrawable();
			// find the smallest drawable weight (the rarest tier), then pick uniformly
			// among the entries sharing it — same-tier weights are identical, so an
			// exact-min match collects exactly the rarest tier
			double min = Double.POSITIVE_INFINITY;
			for (int i = 0; i < size; i++)
				if (weights[i] < min)
					min = weights[i];
			int count = 0;
			for (int i = 0; i < size; i++)
				if (weights[i] == min)
					count++;
			int target = rng.uniform(count); // 0-based index within the rarest tier
			int picked = size - 1;
			for (int i = 0, k = 0; i < size; i++)
				if (weights[i] == min && k++ == target) {
					picked = i;
					break;
				}
			return removeAndReserve(picked);
		}

		// swap-remove the drawable entry at {@code picked}, reserve it as in-use, and
		// return its surname; shared by the weighted and rarest dynasty draws
		private String removeAndReserve(int picked) {
			String name = names[picked];
			double weight = weights[picked];
			int last = size - 1;
			names[picked] = names[last];
			weights[picked] = weights[last];
			names[last] = null;
			size--;
			total -= weight;
			inUse.add(name);
			return name;
		}

		// return a surname to the drawable pool; a no-op (returns false) if this draw
		// is not currently lending it (foreign or already released)
		boolean release(String surname) {
			if (!inUse.remove(surname))
				return false; // foreign or already released — tolerate (disjoint slices)
			double weight = weightByName.get(surname);
			// a released surname was previously dealt here, so capacity (== dealtCount)
			// already has a slot for it; size < dealtCount whenever a name is in use
			names[size] = surname;
			weights[size] = weight;
			size++;
			total += weight;
			return true;
		}
	}

	/**
	 * Create a <b>self-contained</b> registry owning the full human dynasty pool,
	 * drawn on <tt>nameRng</tt>. Convenience for callers that do not partition
	 * across colonies (e.g. tests): it loads the whole human surname table as one
	 * fixed slice with no backing pool, so it throws when the pool is exhausted
	 * rather than pulling more. Only the {@link Race#HUMAN} tables are loaded.
	 *
	 * @param nameRng
	 *            the random-number generator used for all name draws
	 */
	public NameRegistry(Rng nameRng) {
		this(NameTable.load(MALE), NameTable.load(FEMALE), null,
				fullDynastySlice(), 0, nameRng);
	}

	/**
	 * Create a per-colony registry whose {@link Race#HUMAN} surnames are drawn from
	 * <tt>slice</tt> (a disjoint block carved off <tt>pool</tt>), pulling another
	 * slice of <tt>refillSize</tt> from <tt>pool</tt> when it runs out. The
	 * given-name tables are shared (immutable); the generator is this colony's.
	 * Non-human races are added via {@link #registerRace}.
	 *
	 * @param male
	 *            the shared human male given-name table
	 * @param female
	 *            the shared human female given-name table
	 * @param pool
	 *            the session-wide human surname pool to pull refills from (may be
	 *            null for a fixed, non-refilling registry)
	 * @param slice
	 *            this colony's initial disjoint human surname slice
	 * @param refillSize
	 *            the size of each refill slice pulled from {@code pool}
	 * @param rng
	 *            this colony's naming generator
	 */
	public NameRegistry(NameTable male, NameTable female, DynastyPool pool,
			DynastySlice slice, int refillSize, Rng rng) {
		this.rng = rng;
		maleByRace.put(Race.HUMAN, male);
		femaleByRace.put(Race.HUMAN, female);
		dynastyByRace.put(Race.HUMAN, new DynastyDraw(pool, refillSize, slice));
	}

	/**
	 * Register the name tables and surname pool for a <b>non-human race</b>, so this
	 * colony can draw {@code race}'s people from its own tables. Called once per race
	 * present in a mixed colony's race-mix (see {@code docs/race.md}); a mono-cultural
	 * human colony never calls it and is unaffected.
	 *
	 * @param race
	 *            the race to register (typically not {@link Race#HUMAN}, which the
	 *            constructor already installs)
	 * @param male
	 *            the race's male given-name table
	 * @param female
	 *            the race's female given-name table
	 * @param pool
	 *            the race's session-wide surname pool (may be null for a fixed slice)
	 * @param slice
	 *            this colony's initial disjoint surname slice for the race
	 * @param refillSize
	 *            the size of each refill slice pulled from {@code pool}
	 */
	public void registerRace(Race race, NameTable male, NameTable female,
			DynastyPool pool, DynastySlice slice, int refillSize) {
		maleByRace.put(race, male);
		femaleByRace.put(race, female);
		dynastyByRace.put(race, new DynastyDraw(pool, refillSize, slice));
	}

	// load the whole human dynasty table as a single slice (for the self-contained ctor)
	private static DynastySlice fullDynastySlice() {
		NameTable dynasty = NameTable.load(DYNASTY);
		return new DynastySlice(dynasty.namesCopy(), dynasty.weightsCopy());
	}

	// the surname draw for a race, or a clear error if the race was never registered
	private DynastyDraw dynasty(Race race) {
		DynastyDraw draw = dynastyByRace.get(race);
		if (draw == null)
			throw new IllegalStateException("race not registered: " + race);
		return draw;
	}

	/**
	 * Draw and reserve a unique {@link Race#HUMAN human} dynasty surname.
	 *
	 * @return a surname not currently in use by this colony
	 */
	public String nextDynastyName() {
		return nextDynastyName(Race.HUMAN);
	}

	/**
	 * Draw and reserve a unique dynasty surname for {@code race} (weighted, without
	 * replacement). Pulls a fresh slice from that race's backing pool if this
	 * colony's drawable surnames are exhausted.
	 *
	 * @param race
	 *            the race whose surname pool to draw from
	 * @return a surname not currently in use by this colony (nor any other, since
	 *         slices are disjoint)
	 * @throws IllegalStateException
	 *             if the drawable pool is empty and there is no backing pool (or it
	 *             too is exhausted)
	 */
	public String nextDynastyName(Race race) {
		return dynasty(race).next(rng);
	}

	/**
	 * Draw and reserve a unique {@link Race#HUMAN human} surname from the rarest
	 * tier (see {@link #nextRarestDynastyName(Race)}).
	 *
	 * @return a rare surname not currently in use by this colony
	 */
	public String nextRarestDynastyName() {
		return nextRarestDynastyName(Race.HUMAN);
	}

	/**
	 * Draw and reserve a unique dynasty surname for {@code race} from the <b>rarest
	 * tier</b> — the drawable surnames carrying the smallest loaded weight (the most
	 * distinctive houses; for the Harimari pool these are the grand clan-names).
	 * Among that rarest set the pick is uniform. Like {@link #nextDynastyName(Race)}
	 * this is a draw without replacement (pulling a fresh slice if the drawable pool
	 * is empty), so the surname stays unique to one living dynasty. Used for nobles,
	 * who carry rare dynasties rather than the common surnames the commoners draw.
	 *
	 * @param race
	 *            the race whose surname pool to draw from
	 * @return a rare surname not currently in use by this colony
	 * @throws IllegalStateException
	 *             if the drawable pool is empty and cannot be refilled
	 */
	public String nextRarestDynastyName(Race race) {
		return dynasty(race).nextRarest(rng);
	}

	/**
	 * Return an extinct dynasty's surname to its race's drawable pool so it can be
	 * reused. Called when a household dies with no successor. The owning race is
	 * resolved automatically (each race's draw tolerates a surname it never lent), so
	 * the caller need not know it.
	 * <p>
	 * Releasing a surname that is <b>not</b> currently in use by this colony is a
	 * tolerated no-op: it may have been minted by another colony's slice (carried in
	 * by a migrating band) or already released. Slices are disjoint (and per race),
	 * so such a surname can never collide with one this colony would draw, and
	 * ignoring it keeps the cross-colony migration path working without a shared
	 * registry.
	 *
	 * @param surname
	 *            a surname to retire from use
	 */
	public void releaseDynastyName(String surname) {
		// a living surname was drawn from exactly one race's draw, so at most one
		// accepts it; the rest tolerate it as a no-op (disjoint per-race slices)
		for (DynastyDraw draw : dynastyByRace.values())
			if (draw.release(surname))
				return;
	}

	/** Draw a {@link Race#HUMAN human} male given name (weighted, with replacement). */
	public String nextMaleName() {
		return nextMaleName(Race.HUMAN);
	}

	/** Draw a {@code race} male given name (weighted, with replacement). */
	public String nextMaleName(Race race) {
		return maleByRace.get(race).pick(rng);
	}

	/** Draw a {@link Race#HUMAN human} female given name (weighted, with replacement). */
	public String nextFemaleName() {
		return nextFemaleName(Race.HUMAN);
	}

	/** Draw a {@code race} female given name (weighted, with replacement). */
	public String nextFemaleName(Race race) {
		return femaleByRace.get(race).pick(rng);
	}

	/**
	 * Create the {@link Race#HUMAN human} head of a new household: a male given name
	 * plus a unique dynasty surname (a plain weighted draw).
	 *
	 * @return the household head
	 */
	public Person nextHead() {
		return nextHead(Race.HUMAN);
	}

	/**
	 * Create the head of a new household of {@code race}: a male given name plus a
	 * unique dynasty surname (a plain weighted draw).
	 *
	 * @param race
	 *            the head's race (names drawn from its tables)
	 * @return the household head
	 */
	public Person nextHead(Race race) {
		String surname = nextDynastyName(race);
		String givenName = nextMaleName(race);
		return new Person(givenName, surname, Gender.MALE, null, race);
	}

	/**
	 * Create the {@link Race#HUMAN human} head of a new household whose <b>given
	 * name's rarity tracks {@code nameRarity}</b> (0 = common, 1 = rare) plus a
	 * unique dynasty surname. Used to give abler households more distinctive names.
	 *
	 * @param nameRarity
	 *            target rarity of the given name in {@code [0, 1]}
	 * @return the household head
	 */
	public Person nextHead(double nameRarity) {
		return nextHead(nameRarity, Race.HUMAN);
	}

	/**
	 * Create the head of a new household of {@code race} whose given name's rarity
	 * tracks {@code nameRarity} (0 = common, 1 = rare) plus a unique dynasty surname.
	 *
	 * @param nameRarity
	 *            target rarity of the given name in {@code [0, 1]}
	 * @param race
	 *            the head's race (names drawn from its tables)
	 * @return the household head
	 */
	public Person nextHead(double nameRarity, Race race) {
		String surname = nextDynastyName(race);
		String givenName = maleByRace.get(race).pickAtRarity(rng, nameRarity);
		return new Person(givenName, surname, Gender.MALE, null, race);
	}

	/**
	 * Create the {@link Race#HUMAN human} head of a new household whose <b>dynasty
	 * surname is drawn from the rarest tier</b> (see {@link #nextRarestDynastyName}),
	 * with a given name whose rarity tracks {@code nameRarity} exactly as {@link
	 * #nextHead(double)}. Used for nobles, who carry a rare, distinctive dynasty.
	 *
	 * @param nameRarity
	 *            target rarity of the given name in {@code [0, 1]}
	 * @return the household head
	 */
	public Person nextHeadRareDynasty(double nameRarity) {
		return nextHeadRareDynasty(nameRarity, Race.HUMAN);
	}

	/**
	 * Create the head of a new household of {@code race} whose dynasty surname is
	 * drawn from the rarest tier (see {@link #nextRarestDynastyName(Race)}; e.g. a
	 * Harimari clan-name), with a given name whose rarity tracks {@code nameRarity}.
	 *
	 * @param nameRarity
	 *            target rarity of the given name in {@code [0, 1]}
	 * @param race
	 *            the head's race (names drawn from its tables)
	 * @return the household head
	 */
	public Person nextHeadRareDynasty(double nameRarity, Race race) {
		String surname = nextRarestDynastyName(race);
		String givenName = maleByRace.get(race).pickAtRarity(rng, nameRarity);
		return new Person(givenName, surname, Gender.MALE, null, race);
	}

	/**
	 * Create the {@link Race#HUMAN human} head of a household that continues an
	 * existing dynasty: a new male given name paired with the given surname. The
	 * dynasty pool is not touched, so a household succeeding its deceased head never
	 * consumes a new (and ultimately finite) surname.
	 *
	 * @param surname
	 *            the dynasty surname to continue
	 * @return the successor household head
	 */
	public Person nextHeadInDynasty(String surname) {
		return nextHeadInDynasty(surname, Race.HUMAN);
	}

	/**
	 * Create the head of a household of {@code race} continuing an existing dynasty:
	 * a new male given name paired with the given surname.
	 *
	 * @param surname
	 *            the dynasty surname to continue
	 * @param race
	 *            the head's race (given name drawn from its table)
	 * @return the successor household head
	 */
	public Person nextHeadInDynasty(String surname, Race race) {
		return new Person(nextMaleName(race), surname, Gender.MALE, null, race);
	}

	/**
	 * Create a {@link Race#HUMAN human} successor head continuing <tt>surname</tt>
	 * whose given name's rarity tracks <tt>nameRarity</tt> (0 = common, 1 = rare).
	 *
	 * @param surname
	 *            the dynasty surname to continue
	 * @param nameRarity
	 *            target rarity of the given name in {@code [0, 1]}
	 * @return the successor household head
	 */
	public Person nextHeadInDynasty(String surname, double nameRarity) {
		return nextHeadInDynasty(surname, nameRarity, Race.HUMAN);
	}

	/**
	 * Create a successor head of {@code race} continuing <tt>surname</tt> whose given
	 * name's rarity tracks <tt>nameRarity</tt> (0 = common, 1 = rare).
	 *
	 * @param surname
	 *            the dynasty surname to continue
	 * @param nameRarity
	 *            target rarity of the given name in {@code [0, 1]}
	 * @param race
	 *            the head's race (given name drawn from its table)
	 * @return the successor household head
	 */
	public Person nextHeadInDynasty(String surname, double nameRarity, Race race) {
		String givenName = maleByRace.get(race).pickAtRarity(rng, nameRarity);
		return new Person(givenName, surname, Gender.MALE, null, race);
	}
}
