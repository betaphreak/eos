package com.civstudio.util;

/**
 * A tool to calculate the mean of a data series over a fixed-size sliding window.
 * Values are continuously fed in; once the window is full, each new value evicts
 * the oldest. Backed by a fixed {@code double[]} <b>circular buffer</b> (rather than
 * a {@code LinkedList<Double>}): no boxing, no per-element node allocation, O(1)
 * update, and constant memory.
 * <p>
 * The running {@code sum} is maintained incrementally with the same operation order
 * as a plain add-then-evict queue ({@code sum += val}, then {@code sum -= oldest}
 * once full), so the computed mean is bit-for-bit identical to the previous
 * list-backed implementation.
 *
 * @author zhihongx
 */
public class Averager {

	private final double[] buffer; // circular window of the last `capacity` values
	private final int capacity; // window size
	private int count; // number of values seen so far, capped at capacity
	private int next; // index to write the next value (and, when full, the oldest)
	private double sum; // running sum of the values currently in the window

	/**
	 * Create a new <tt>Averager</tt> with window size <tt>size</tt>.
	 *
	 * @param size the sliding-window size (must be positive)
	 */
	public Averager(int size) {
		assert (size > 0);
		this.buffer = new double[size];
		this.capacity = size;
	}

	/**
	 * Add <tt>val</tt> to the window and return the mean of the values currently in
	 * it (the last {@code size} values, or all values seen so far until the window
	 * fills).
	 *
	 * @param val the value to add
	 * @return the mean of the values in the window
	 */
	public double update(double val) {
		sum += val;
		if (count < capacity)
			count++; // window still filling: keep the value, nothing evicted
		else
			sum -= buffer[next]; // window full: evict the oldest (sits at `next`)
		buffer[next] = val;
		next++;
		if (next == capacity)
			next = 0;
		return sum / count;
	}
}
