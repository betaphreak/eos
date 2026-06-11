package eos.economy;

import java.time.LocalDate;

import eos.mortality.Demography;
import eos.name.NameRegistry;
import eos.util.Rng;
import lombok.Getter;

/**
 * A game session owns the random-number seed and its {@link Rng}, and creates
 * {@link Economy} instances from it. The economies it creates share this
 * session's generator, so distinct sessions draw from independent generators
 * and run fully independently. The same seed yields an identical run.
 * <p>
 * The session also owns the complete name sets ({@link NameRegistry}) and the
 * demographic service ({@link Demography}), each drawn from its own
 * <em>separate</em> generator (a salted copy of the seed) so naming and
 * mortality are deterministic yet never perturb the economic random stream.
 * Because these are shared across the session's economies, dynasty surnames are
 * unique for the whole session.
 *
 * @author zhihongx
 */
public class GameSession {

	// decorrelate the naming/mortality generators from the economic one (and
	// from each other), all seeded from the same session seed
	private static final long NAME_SEED_SALT = 0x9E3779B97F4A7C15L;
	private static final long MORTALITY_SEED_SALT = 0xD1B54A32D192ED03L;

	// random-number seed for this session
	@Getter
	private final long seed;

	// random-number generator shared with the economies this session creates
	@Getter
	private final Rng rng;

	// the complete name sets for this session, with their own generator
	@Getter
	private final NameRegistry names;

	// the demographic service for this session, with its own generator
	@Getter
	private final Demography demography;

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
		this.demography = new Demography(new Rng(seed ^ MORTALITY_SEED_SALT));
	}

	/**
	 * Create a new economy whose step 0 falls on <tt>startDate</tt>, drawing
	 * economic randomness from this session's {@link Rng}, names from its
	 * {@link NameRegistry} and mortality from its {@link Demography}.
	 *
	 * @param startDate
	 *            the in-game date of step 0
	 * @return a fresh economy
	 */
	public Economy newEconomy(LocalDate startDate) {
		return new Economy(startDate, rng, names, demography);
	}
}
