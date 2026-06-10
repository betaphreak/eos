package eos.util;

import java.util.Random;

/**
 * Minimal seedable random-number generator shared across the simulation. All
 * draws come from a single {@link java.util.Random}, so a run is fully
 * determined by the seed set via {@link #setSeed(long)} — the same seed yields
 * an identical run.
 */
public final class Rng {

	private static Random random = new Random();

	private Rng() {
	}

	/** Reset the generator to a fixed seed for a reproducible run. */
	public static void setSeed(long seed) {
		random = new Random(seed);
	}

	/** The backing generator (e.g. for {@link java.util.Collections#shuffle}). */
	public static Random getRandom() {
		return random;
	}

	/** Uniform real in [0, 1). */
	public static double uniform() {
		return random.nextDouble();
	}

	/** Uniform real in [a, b). */
	public static double uniform(double a, double b) {
		return a + random.nextDouble() * (b - a);
	}

	/** Uniform integer in [0, n). */
	public static int uniform(int n) {
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
	public static double gaussian(double mean, double stddev) {
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
