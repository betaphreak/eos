package com.civstudio.io.sink;

/**
 * A destination for a printer's output rows — the seam that lets a {@link
 * com.civstudio.io.printer.Printer} write to a CSV file, a database table, or
 * both without knowing which. A printer declares its schema once (its {@link
 * com.civstudio.io.printer.Printer#columns() columns}) when the sink is created
 * by a {@link RowSinkFactory}, then calls {@link #writeRow(Object...)} for each
 * row it emits.
 * <p>
 * Values passed to {@link #writeRow} are <b>raw</b> (a {@link java.time.LocalDate},
 * {@link Double}, {@link Integer}, {@link String} or enum), positionally matching
 * the declared columns; each sink renders or binds them in its own way (the CSV
 * sink formats them as text, the JDBC sink binds them to a prepared statement).
 */
public interface RowSink {

	/**
	 * Write one row of values, positionally matching the columns this sink was
	 * created with.
	 *
	 * @param values the raw column values, in column order
	 */
	void writeRow(Object... values);

	/** Flush any buffered rows to the underlying destination. */
	void flush();

	/** Flush and release the underlying resource (file handle, batch). */
	void close();

	/**
	 * A human-readable name for this sink (the CSV file path, or the table name),
	 * used for diagnostics and backward-compatible {@code getFileName()} reporting.
	 *
	 * @return the sink's name
	 */
	String name();
}
