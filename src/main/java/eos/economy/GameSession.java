package eos.economy;

import java.time.LocalDate;

import eos.name.NameRegistry;
import eos.util.Rng;
import lombok.Getter;

/**
 * A game session owns the random-number seed and its {@link Rng}, and creates
 * {@link Economy} instances from it. The economies it creates share this
 * session's generator, so distinct sessions draw from independent generators
 * and run fully independently. The same seed yields an identical run.
 * <p>
 * The session also owns the complete name sets ({@link NameRegistry}), drawn
 * from a <em>separate</em> generator (a salted copy of the seed) so naming is
 * deterministic yet never perturbs the economic random stream. Because the
 * registry is shared across the session's economies, dynasty surnames are
 * unique for the whole session.
 *
 * @author zhihongx
 */
public class GameSession {

	// decorrelates the naming generator from the economic one (same seed input)
	private static final long NAME_SEED_SALT = 0x9E3779B97F4A7C15L;

	// random-number seed for this session
	@Getter
	private final long seed;

	// random-number generator shared with the economies this session creates
	@Getter
	private final Rng rng;

	// the complete name sets for this session, with their own generator
	@Getter
	private final NameRegistry names;

	/**
	 * Create a new game session with the given random-number seed.
	 *
	 * @param seed
	 *            the random-number seed for runs created from this session
	 */
	public GameSession(long seed) {
		this.seed = seed;
		this.rng = new Rng(seed);
		this.names = new NameRegistry(new Rng(seed ^ NAME_SEED_SALT));
	}

	/**
	 * Create a new economy whose step 0 falls on <tt>startDate</tt>, drawing
	 * economic randomness from this session's {@link Rng} and names from its
	 * {@link NameRegistry}.
	 *
	 * @param startDate
	 *            the in-game date of step 0
	 * @return a fresh economy
	 */
	public Economy newEconomy(LocalDate startDate) {
		return new Economy(startDate, rng, names);
	}
}
