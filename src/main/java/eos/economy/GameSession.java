package eos.economy;

import java.time.LocalDate;

import eos.util.Rng;
import lombok.Getter;

/**
 * A game session owns the random-number seed and its {@link Rng}, and creates
 * {@link Economy} instances from it. The economies it creates share this
 * session's generator, so distinct sessions draw from independent generators
 * and run fully independently. The same seed yields an identical run.
 *
 * @author zhihongx
 */
public class GameSession {

	// random-number seed for this session
	@Getter
	private final long seed;

	// random-number generator shared with the economies this session creates
	@Getter
	private final Rng rng;

	/**
	 * Create a new game session with the given random-number seed.
	 *
	 * @param seed
	 *            the random-number seed for runs created from this session
	 */
	public GameSession(long seed) {
		this.seed = seed;
		this.rng = new Rng(seed);
	}

	/**
	 * Create a new economy whose step 0 falls on <tt>startDate</tt>, drawing
	 * randomness from this session's {@link Rng}.
	 *
	 * @param startDate
	 *            the in-game date of step 0
	 * @return a fresh economy
	 */
	public Economy newEconomy(LocalDate startDate) {
		return new Economy(startDate, rng);
	}
}
