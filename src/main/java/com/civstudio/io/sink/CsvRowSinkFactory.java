package com.civstudio.io.sink;

/**
 * The default {@link RowSinkFactory}: every printer writes to its own CSV file,
 * exactly as before the database backend existed. A {@link
 * com.civstudio.settlement.Settlement} uses this unless a launcher installs a
 * database-backed factory, so plain {@code main()} runs and the test suite are
 * unaffected.
 */
public class CsvRowSinkFactory implements RowSinkFactory {

	@Override
	public RowSink create(String table, String fileName, ColumnSpec[] columns) {
		return new CsvRowSink(fileName, columns);
	}
}
