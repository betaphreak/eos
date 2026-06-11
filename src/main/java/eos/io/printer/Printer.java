package eos.io.printer;

import eos.economy.Economy;
import lombok.Getter;

/**
 * Parent class of all printers
 * @author zhihongx
 *
 */
@Getter
public abstract class Printer {

	/**
	 * starting time step
	 */
	protected final int start;

	/**
	 * ending time step
	 */
	protected final int end;

	/**
	 * Create a new printer that prints on the first day of each month between
	 * the start step and the end step (inclusive).
	 *
	 * @param start
	 *            starting time step
	 * @param end
	 *            ending time step
	 */
	public Printer(int start, int end) {
		assert (start >= 0);
		assert (end >= start);
		this.start = start;
		this.end = end;
	}

	/**
	 * Create a new printer that prints on the first day of each month from the
	 * start step till the last step.
	 *
	 * @param start
	 *            starting time step
	 */
	public Printer(int start) {
		this(start, Integer.MAX_VALUE);
	}

	/**
	 * Create a new printer that prints on the first day of each month over the
	 * whole run.
	 */
	public Printer() {
		this(0, Integer.MAX_VALUE);
	}

	/**
	 * Whether this printer should emit a row on the current step. A row is
	 * written on the <b>first day of each month</b> (the in-game date's day of
	 * month is 1), provided the step is within the printer's [{@code start},
	 * {@code end}] bounds.
	 *
	 * @param economy
	 *            the economy being printed (source of the in-game date and step)
	 * @return {@code true} if a data row should be written this step
	 */
	protected boolean shouldPrint(Economy economy) {
		int step = economy.getTimeStep();
		return step >= start && step <= end
				&& economy.getDate().getDayOfMonth() == 1;
	}

	/**
	 * Print column titles
	 */
	public abstract void printTitles();

	/**
	 * Print data, called by {@link Economy#newDay()} at each time step.
	 *
	 * @param economy
	 *            the economy being printed (source of the in-game date and time
	 *            step)
	 */
	public abstract void print(Economy economy);

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
