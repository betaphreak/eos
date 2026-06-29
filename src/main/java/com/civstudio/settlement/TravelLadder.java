package com.civstudio.settlement;

/**
 * The travel-time ladder of the plot model: plots are ordered by how long it
 * takes a laborer to walk out to them and back, not by position on a disc. Plot
 * {@code index} 0 is the (notional) city center — travel time 0 — and each later
 * plot lies one rung farther out, its <b>one-way</b> travel time following the
 * Fibonacci sequence (in <b>seconds</b>):
 *
 * <pre>
 * index i:   0  1  1  2  3  5  8  13  21  34  55  89  144  233  377  610  987  1597 ...
 * </pre>
 *
 * So the inner plots cost almost nothing and the commute only bites on the far
 * rungs. The commute eats into the day's work window {@code D} (sunrise→sunset),
 * a Von&nbsp;Thünen rent gradient that caps useful village size organically:
 * beyond {@code N + 2·T(i) ≥ D} a plot yields no useful labor at all. Pure
 * arithmetic, no state. See {@code docs/plots.md}.
 */
public final class TravelLadder {

	private TravelLadder() {
	}

	/**
	 * The <b>one-way</b> travel time, in seconds, to the plot at the given ladder
	 * index — {@code 0} for the center (index ≤ 0), then the Fibonacci sequence
	 * {@code T(1)=1, T(2)=1, T(3)=2, T(4)=3, T(5)=5, …}.
	 *
	 * @param index the plot's ladder index
	 * @return the one-way travel time in seconds
	 */
	public static long oneWaySeconds(int index) {
		if (index <= 0)
			return 0;
		long a = 1, b = 1; // T(1), T(2)
		for (int i = 1; i < index; i++) {
			long c = a + b;
			a = b;
			b = c;
		}
		return a;
	}

	/**
	 * The fraction of a worker's labor that survives the day's overheads:
	 * {@code max(0, 1 − (N + commute) / D)}, where {@code N} is the labor market's
	 * clearing overhead (one second per participating worker), {@code commute} is the
	 * worker's round-trip travel to its firm's plot ({@code 2·T(index)}, zero for a
	 * center-grouped firm), and {@code D} is the day's work window in seconds. A
	 * center plot costs only the uniform {@code N}; a distant plot also pays its
	 * commute, down to zero past the frontier ({@code N + commute ≥ D}).
	 *
	 * @param commute the worker's round-trip commute in seconds (0 = center)
	 * @param n       the market clearing overhead in seconds (participant count)
	 * @param d       the day's work window in seconds (sunrise→sunset)
	 * @return the surviving labor fraction in {@code [0, 1]}
	 */
	public static double workFactor(double commute, double n, double d) {
		if (d <= 0)
			return 0; // no work window (polar night) → no useful labor today
		return Math.max(0, 1 - (n + commute) / d);
	}
}
