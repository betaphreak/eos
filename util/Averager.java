package eos.util;

import java.util.LinkedList;

/**
 * A tool to calculate the mean of a data series. Data are continuously fed into
 * a fixed-size buffer, and their average are calculated. When the buffer is
 * full, the oldest datum would be swapped out.
 * 
 * @author zhihongx
 * 
 */
public class Averager {
	private double sum; // sum of data
	private int size; // buffer size
	private LinkedList<Double> data;

	/**
	 * Create a new <tt>Averager</tt> with buffer size <tt>size</tt>
	 * 
	 * @param size
	 */
	public Averager(int size) {
		data = new LinkedList<Double>();
		this.size = size;
		sum = 0;
	}

	/**
	 * Add <tt>val</tt> to buffer and returns the mean of data in the buffer
	 * 
	 * @param val
	 * @return mean of data inside the buffer
	 */
	public double update(double val) {
		data.offerLast(val);
		sum += val;
		if (data.size() > size) {
			sum -= data.removeFirst();
		}
		return sum / data.size();
	}

}
