package com.civstudio.io.sink;

/**
 * Creates the {@link RowSink} a printer writes to, hiding which backend is in
 * play. A {@link com.civstudio.settlement.Settlement} holds one factory (the
 * default writes CSV — see {@link CsvRowSinkFactory}); the Spring Boot launcher
 * installs a database- or composite-backed factory carrying the run/colony
 * identity, so a printer never changes regardless of where its rows land.
 */
public interface RowSinkFactory {

	/**
	 * Create a sink for one printer.
	 *
	 * @param table    the printer's logical table name (e.g. {@code "prices"}) —
	 *                 the SQL table for the database backend, stable across
	 *                 colonies (rows are told apart by colony, not table)
	 * @param fileName the printer's CSV file name (carrying any per-colony prefix)
	 * @param columns  the printer's ordered, typed columns
	 * @return a sink the printer writes its rows to
	 */
	RowSink create(String table, String fileName, ColumnSpec[] columns);
}
