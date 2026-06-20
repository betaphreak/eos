package com.civstudio.io.printer;

import com.civstudio.io.sink.ColumnSpec;
import com.civstudio.io.sink.RowSink;
import com.civstudio.io.sink.RowSinkFactory;
import com.civstudio.settlement.Settlement;
import lombok.Getter;

/**
 * Parent class of all printers. A printer declares a typed schema (its {@link
 * #tableName()} and {@link #columns()}) and writes rows through a {@link RowSink},
 * so the same printer can emit to a CSV file, a database table, or both depending
 * on the {@link RowSinkFactory} the colony was given. The sink is created when the
 * printer is registered ({@link Settlement#addPrinter}), which writes the header /
 * declares the schema; rows are emitted from {@link #print(Settlement)} each step.
 *
 * @author zhihongx
 */
@Getter
public abstract class Printer {

	/** starting time step */
	protected final int start;

	/** ending time step */
	protected final int end;

	/** the CSV file name (carries any per-colony prefix) */
	protected final String fileName;

	/** the sink rows are written to, bound at registration via {@link #bind} */
	protected RowSink sink;

	/**
	 * Create a printer that prints on the first day of each month between the
	 * start step and the end step (inclusive).
	 *
	 * @param fileName the CSV file name
	 * @param start    starting time step
	 * @param end      ending time step
	 */
	protected Printer(String fileName, int start, int end) {
		assert (start >= 0);
		assert (end >= start);
		this.fileName = fileName;
		this.start = start;
		this.end = end;
	}

	/**
	 * Create a printer that prints on the first day of each month from the start
	 * step till the last step.
	 *
	 * @param fileName the CSV file name
	 * @param start    starting time step
	 */
	protected Printer(String fileName, int start) {
		this(fileName, start, Integer.MAX_VALUE);
	}

	/**
	 * Create a printer that prints on the first day of each month over the whole
	 * run.
	 *
	 * @param fileName the CSV file name
	 */
	protected Printer(String fileName) {
		this(fileName, 0, Integer.MAX_VALUE);
	}

	/**
	 * The logical table name for this printer (stable across colonies — rows are
	 * told apart by colony, not by table). Used by the database backend; the CSV
	 * backend uses {@link #fileName} instead.
	 *
	 * @return the table name
	 */
	public abstract String tableName();

	/**
	 * The printer's ordered, typed columns. Their names form the CSV header and
	 * the SQL column list; their order matches the values passed to {@link
	 * RowSink#writeRow}.
	 *
	 * @return the column specifications
	 */
	public abstract ColumnSpec[] columns();

	/**
	 * Bind this printer to a sink created by {@code factory}, declaring its
	 * schema (which writes the CSV header / ensures the table). Called by {@link
	 * Settlement#addPrinter} at registration.
	 *
	 * @param factory the factory that creates the sink
	 */
	public void bind(RowSinkFactory factory) {
		this.sink = factory.create(tableName(), fileName, columns());
	}

	/**
	 * Whether this printer should emit a row on the current step. A row is written
	 * on the <b>first day of each month</b>, provided the step is within the
	 * printer's [{@code start}, {@code end}] bounds.
	 *
	 * @param colony the colony being printed (source of the in-game date and step)
	 * @return {@code true} if a data row should be written this step
	 */
	protected boolean shouldPrint(Settlement colony) {
		int step = colony.getTimeStep();
		return step >= start && step <= end
				&& colony.getDate().getDayOfMonth() == 1;
	}

	/**
	 * Print data, called by {@link Settlement#newDay()} at each time step.
	 *
	 * @param colony the colony being printed
	 */
	public abstract void print(Settlement colony);

	/** Clean up the printer (flush and close its sink). */
	public void cleanup() {
		if (sink != null)
			sink.close();
	}

	/**
	 * Return the name of the output destination (the CSV file path, or the table
	 * name once bound).
	 *
	 * @return the output name
	 */
	public String getFileName() {
		return sink != null ? sink.name() : fileName;
	}
}
