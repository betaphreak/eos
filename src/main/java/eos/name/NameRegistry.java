package eos.name;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import eos.util.Rng;

/**
 * The complete sets of names for a game session, with the random-number
 * generator used to draw from them. Owned by a {@code GameSession} and shared
 * with the colonies it creates, so naming is independent of (and does not
 * perturb) the economic random stream.
 * <p>
 * Dynasty surnames are drawn <b>without replacement</b>, so every household
 * <em>in use</em> across the session — in any colony — gets a unique surname;
 * given names are drawn <b>with replacement</b> and may repeat. Draws are
 * weighted by the source tables, so common names are picked first.
 * <p>
 * Because the pool of surnames is finite, an extinct dynasty's surname is
 * <b>recycled</b>: when a household dies with no successor, the colony returns
 * its surname via {@link #releaseDynastyName(String)} and it becomes drawable
 * again. So the invariant is uniqueness among <em>living</em> households, not
 * permanent consumption — letting a long run (or many colonies) reuse the names
 * of bygone dynasties without ever colliding with a current one.
 */
public final class NameRegistry {

	private static final String DYNASTY = "/dynasty-human.json";
	private static final String MALE = "/male-human.json";
	private static final String FEMALE = "/female-human.json";

	private final Rng rng;

	// given-name tables (drawn with replacement)
	private final NameTable male;
	private final NameTable female;

	// consumable dynasty pool (drawn without replacement): a flat weighted list,
	// drawable in [0, dynastySize). A draw swap-removes the picked entry; a
	// release appends it back at the boundary. Capacity is the full set, so a
	// recycled surname always has a slot.
	private final String[] dynastyNames;
	private final double[] dynastyWeights;
	private double dynastyTotal;
	private int dynastySize;

	// every surname's original weight, so a recycled one re-enters the pool with
	// the weight it was loaded with
	private final Map<String, Double> dynastyWeightByName;

	// surnames currently handed out (in use by a living dynasty); a surname is in
	// exactly one of {the drawable pool, this set} at any time
	private final Set<String> inUse = new HashSet<>();

	/**
	 * Load the complete name sets and bind them to <tt>nameRng</tt>.
	 *
	 * @param nameRng
	 *            the random-number generator used for all name draws
	 */
	public NameRegistry(Rng nameRng) {
		this.rng = nameRng;
		this.male = NameTable.load(MALE);
		this.female = NameTable.load(FEMALE);

		NameTable dynasty = NameTable.load(DYNASTY);
		this.dynastyNames = dynasty.namesCopy();
		this.dynastyWeights = dynasty.weightsCopy();
		this.dynastyTotal = dynasty.total();
		this.dynastySize = dynastyNames.length;

		this.dynastyWeightByName = new HashMap<>(dynastyNames.length * 2);
		for (int i = 0; i < dynastyNames.length; i++)
			dynastyWeightByName.put(dynastyNames[i], dynastyWeights[i]);
	}

	/**
	 * Draw and reserve a unique dynasty surname (weighted, without replacement).
	 *
	 * @return a surname not previously returned by this registry
	 * @throws IllegalStateException
	 *             if the dynasty pool is exhausted
	 */
	public String nextDynastyName() {
		if (dynastySize == 0)
			throw new IllegalStateException("dynasty name pool exhausted");
		double r = rng.uniform() * dynastyTotal;
		int picked = dynastySize - 1; // fall back to the last on FP slack
		for (int i = 0; i < dynastySize; i++) {
			r -= dynastyWeights[i];
			if (r < 0) {
				picked = i;
				break;
			}
		}
		String name = dynastyNames[picked];
		// swap-remove the picked entry so it can't be drawn again while in use
		int last = dynastySize - 1;
		dynastyTotal -= dynastyWeights[picked];
		dynastyNames[picked] = dynastyNames[last];
		dynastyWeights[picked] = dynastyWeights[last];
		dynastyNames[last] = null;
		dynastySize--;
		inUse.add(name);
		return name;
	}

	/**
	 * Return an extinct dynasty's surname to the drawable pool so it can be
	 * reused. Called when a household dies with no successor, so the surname is no
	 * longer in use by any living dynasty; it then becomes eligible to be drawn
	 * again (with its original weight) by a future founding or immigrant
	 * household. Surnames still in use are never released, so the uniqueness of
	 * living households is preserved.
	 *
	 * @param surname
	 *            a surname previously handed out by this registry and not yet
	 *            released
	 * @throws IllegalStateException
	 *             if the surname is not currently in use (never drawn, already
	 *             released, or not from this registry)
	 */
	public void releaseDynastyName(String surname) {
		if (!inUse.remove(surname))
			throw new IllegalStateException(
					"surname not in use, cannot release: " + surname);
		double weight = dynastyWeightByName.get(surname);
		dynastyNames[dynastySize] = surname;
		dynastyWeights[dynastySize] = weight;
		dynastyTotal += weight;
		dynastySize++;
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
