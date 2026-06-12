package eos.name;

import eos.util.Rng;

/**
 * The complete sets of names for a game session, with the random-number
 * generator used to draw from them. Owned by a {@code GameSession} and shared
 * with the colonies it creates, so naming is independent of (and does not
 * perturb) the economic random stream.
 * <p>
 * Dynasty surnames are drawn <b>without replacement</b>, so every household in
 * the session gets a unique surname; given names are drawn <b>with
 * replacement</b> and may repeat. Draws are weighted by the source tables, so
 * common names are picked first.
 */
public final class NameRegistry {

	private static final String DYNASTY = "/dynasty-human.json";
	private static final String MALE = "/male-human.json";
	private static final String FEMALE = "/female-human.json";

	private final Rng rng;

	// given-name tables (drawn with replacement)
	private final NameTable male;
	private final NameTable female;

	// consumable dynasty pool (drawn without replacement): a flat weighted list
	// that shrinks by swap-remove as surnames are handed out
	private final String[] dynastyNames;
	private final double[] dynastyWeights;
	private double dynastyTotal;
	private int dynastySize;

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
		// swap-remove the picked entry so it can't be drawn again
		int last = dynastySize - 1;
		dynastyTotal -= dynastyWeights[picked];
		dynastyNames[picked] = dynastyNames[last];
		dynastyWeights[picked] = dynastyWeights[last];
		dynastyNames[last] = null;
		dynastySize--;
		return name;
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
	 * dynasty surname.
	 *
	 * @return the household head
	 */
	public Person nextHead() {
		String surname = nextDynastyName();
		String givenName = nextMaleName();
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
}
