package com.civstudio.io.sink;

/**
 * The default {@link RowSinkFactory}: every printer writes to its own CSV file,
 * exactly as before the database backend existed. A {@link
 * com.civstudio.settlement.Settlement} uses this unless a launcher installs a
 * database-backed factory, so plain {@code main()} runs and the test suite are
 * unaffected.
 * <p>
 * The factory may carry a {@code baseDir} under which every file is written —
 * a colony of a {@link com.civstudio.settlement.GameSession} scopes its output to
 * {@code output/<seed>/} so a whole run lands in one folder (installed by
 * {@code Settlement.setSession}). With no base dir, a bare file name keeps landing
 * under {@code output/} as before (the {@link
 * com.civstudio.io.printer.CSVPrintWriter} default).
 */
public class CsvRowSinkFactory implements RowSinkFactory {

	// directory every file is written under (e.g. "output/7654321"), or null to
	// leave the file name as-is (CSVPrintWriter then defaults a bare name to output/)
	private final String baseDir;

	/** Create a factory that writes bare file names under {@code output/} (the default). */
	public CsvRowSinkFactory() {
		this(null);
	}

	/**
	 * Create a factory that writes every file under {@code baseDir}.
	 *
	 * @param baseDir the directory to write all files under (e.g. {@code "output/7654321"}),
	 *                or {@code null} to leave file names unscoped
	 */
	public CsvRowSinkFactory(String baseDir) {
		this.baseDir = baseDir;
	}

	@Override
	public RowSink create(String table, String fileName, ColumnSpec[] columns) {
		String resolved = (baseDir == null) ? fileName : baseDir + "/" + fileName;
		return new CsvRowSink(resolved, columns);
	}
}
