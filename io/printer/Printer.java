package eos.io.printer;

/**
 * Parent class of all printers
 * @author zhihongx
 *
 */
public abstract class Printer {

	/**
	 * interval (in steps) between two printing
	 */
	protected final int period;

	/**
	 * starting time step
	 */
	protected final int start;

	/**
	 * ending time step
	 */
	protected final int end;

	/**
	 * Create a new printer that prints every period steps from the start step
	 * till the end step.
	 * 
	 * @param period
	 *            number of steps between two prints
	 * @param start
	 *            starting time step
	 * @param end
	 *            ending time step
	 */
	public Printer(int period, int start, int end) {
		assert (period > 0);
		assert (start >= 0);
		assert (end >= start);
		this.period = period;
		this.start = start;
		this.end = end;
	}

	/**
	 * Create a new printer that prints every period steps from the start step
	 * till the last step
	 * 
	 * @param period
	 *            number of steps between two prints
	 * @param start
	 *            starting time step
	 */
	public Printer(int period, int start) {
		this(period, start, Integer.MAX_VALUE);
	}

	/**
	 * Create a new printer that prints every period steps from the first step
	 * till the last step
	 * 
	 * @param period
	 *            number of steps between two printing
	 */
	public Printer(int period) {
		this(period, 0, Integer.MAX_VALUE);
	}

	/**
	 * Return the period of this Printer
	 * 
	 * @return the period of this Printer
	 */
	public int getPeriod() {
		return this.period;
	}

	/**
	 * Return the starting step of this Printer
	 * 
	 * @return the starting step of this Printer
	 */
	public int getStart() {
		return this.start;
	}

	/**
	 * Return the ending step of this Printer
	 * 
	 * @return the ending step of this Printer
	 */
	public int getEnd() {
		return this.end;
	}

	/**
	 * Print column titles
	 */
	public abstract void printTitles();

	/**
	 * Print data, called by Economy at each time step
	 */
	public abstract void print();

	/**
	 * Clean up the printer
	 */
	public abstract void cleanup();

	/**
	 * Return the name of the output file.
	 * 
	 * @return the name of the output file
	 */
	public abstract String getFileName();
}
