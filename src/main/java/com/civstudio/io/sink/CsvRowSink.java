package com.civstudio.io.sink;

import com.civstudio.io.printer.CSVPrintWriter;

/**
 * A {@link RowSink} that writes rows to a CSV file, preserving the historical
 * output format: it delegates to {@link CSVPrintWriter}, which writes the header
 * row from the column names on construction and renders each value the same way
 * the printers always have ({@link Double}/{@link Float} to two decimals, every
 * other value via {@code String.valueOf}). The {@link ColumnType}s are ignored —
 * CSV is untyped text — so this sink is a drop-in for the old per-printer
 * {@code CSVPrintWriter}.
 */
public class CsvRowSink implements RowSink {

	private final CSVPrintWriter out;

	/**
	 * Open a CSV sink at {@code fileName} and immediately write the header row
	 * from the column names.
	 *
	 * @param fileName the CSV file name (a bare name lands under {@code output/})
	 * @param columns  the ordered columns; their names form the header
	 */
	public CsvRowSink(String fileName, ColumnSpec[] columns) {
		this.out = new CSVPrintWriter(fileName);
		Object[] headers = new Object[columns.length];
		for (int i = 0; i < columns.length; i++)
			headers[i] = columns[i].name();
		out.println(headers);
	}

	@Override
	public void writeRow(Object... values) {
		out.println(values);
	}

	@Override
	public void flush() {
		// CSVPrintWriter autoflushes on every println; nothing buffered
	}

	@Override
	public void close() {
		out.cleanup();
	}

	@Override
	public String name() {
		return out.getFileName();
	}
}
