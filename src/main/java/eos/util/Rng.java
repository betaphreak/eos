package eos.util;

import java.util.Random;

/**
 * Minimal seedable random-number generator. Each instance wraps its own
 * {@link java.util.Random}, so a run is fully determined by the seed passed at
 * construction — the same seed yields an identical sequence. A {@link
 * eos.settlement.GameSession} owns one {@code Rng} and shares it with the colonies
 * it creates, so distinct sessions draw from independent generators.
 */
public final class Rng {

	private final Random random;

	/** Create a generator seeded with <tt>seed</tt> for a reproducible run. */
	public Rng(long seed) {
		this.random = new Random(seed);
	}

	/** The backing generator (e.g. for {@link java.util.Collections#shuffle}). */
	public Random getRandom() {
		return random;
	}

	/** Uniform real in [0, 1). */
	public double uniform() {
		return random.nextDouble();
	}

	/** Uniform real in [a, b). */
	public double uniform(double a, double b) {
		return a + random.nextDouble() * (b - a);
	}

	/** Uniform integer in [0, n). */
	public int uniform(int n) {
		return (int) (random.nextDouble() * n);
	}

	/**
	 * A draw from a normal distribution with the given mean and standard
	 * deviation, using the polar form of the Box-Muller method.
	 *
	 * @param mean   the mean
	 * @param stddev the standard deviation
	 * @return a normally distributed value
	 */
	public double gaussian(double mean, double stddev) {
		double x, y, r;
		do {
			x = uniform(-1.0, 1.0);
			y = uniform(-1.0, 1.0);
			r = x * x + y * y;
		} while (r >= 1 || r == 0);
		// compute the standard normal first, then scale, so the
		// floating-point grouping matches a plain mean + stddev * z
		double z = x * Math.sqrt(-2 * Math.log(r) / r);
		return mean + stddev * z;
	}
}
