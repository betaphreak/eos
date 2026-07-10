package com.civstudio.io.sink;

/**
 * One column of a printer's output row: its name (the CSV header / SQL column
 * name) and its {@link ColumnType}. A printer declares an ordered array of these
 * via {@link com.civstudio.io.printer.Printer#columns()}; the order matches the
 * positional values it passes to {@link RowSink#writeRow(Object...)}.
 *
 * @param name the column name (CSV header cell and SQL column identifier)
 * @param type the column's logical type
 */
public record ColumnSpec(String name, ColumnType type) {

	/** A {@code date} column. */
	public static ColumnSpec date(String name) {
		return new ColumnSpec(name, ColumnType.DATE);
	}

	/** A {@code text} column. */
	public static ColumnSpec text(String name) {
		return new ColumnSpec(name, ColumnType.TEXT);
	}

	/** An {@code integer} column. */
	public static ColumnSpec integer(String name) {
		return new ColumnSpec(name, ColumnType.INT);
	}

	/** A {@code double precision} column. */
	public static ColumnSpec real(String name) {
		return new ColumnSpec(name, ColumnType.REAL);
	}
}
