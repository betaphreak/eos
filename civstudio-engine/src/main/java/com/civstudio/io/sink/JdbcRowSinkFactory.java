package com.civstudio.io.sink;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;

import javax.sql.DataSource;

/**
 * A {@link RowSinkFactory} that writes every printer's rows to a SQL table via {@link
 * JdbcRowSink}, carrying the <b>run identity</b> ({@code runId}, {@code seed}, {@code
 * scenario}) that becomes a column on every table — so a store can tell runs apart and
 * aggregate across them (what {@code output/<seed>/} directories can't). The engine
 * stays driver-free: the caller supplies the {@link DataSource} (H2 for the local
 * calibration harness, Postgres for the server). See {@code docs/mcp-server.md} §Data
 * backend.
 *
 * <p>Install it on a colony with {@link com.civstudio.settlement.Settlement#setSinkFactory}
 * — the default stays {@link CsvRowSinkFactory}, so plain {@code main()} runs and the
 * test suite are unaffected; SQL is opt-in.
 *
 * <p>Several colonies of a run share one factory and may register a printer for the same
 * logical table concurrently; the one-off {@code CREATE TABLE} is serialized here and
 * completes before any sink prepares its insert, so no writer races an absent table.
 */
public final class JdbcRowSinkFactory implements RowSinkFactory {

	private final DataSource dataSource;
	private final String runId;
	private final long seed;
	private final String scenario;

	// tables whose DDL has been executed; guarded by `this` via the synchronized ensureTable
	private final Set<String> created = new HashSet<>();

	/**
	 * @param dataSource the JDBC data source the caller's launcher provides (H2 / Postgres)
	 * @param runId      the run's stable id — a column on every table, for {@code GROUP BY run_id}
	 * @param seed       the run's seed (a column, for convenience filtering)
	 * @param scenario   the scenario's main class / logical name (a column)
	 */
	public JdbcRowSinkFactory(DataSource dataSource, String runId, long seed, String scenario) {
		this.dataSource = dataSource;
		this.runId = runId;
		this.seed = seed;
		this.scenario = scenario;
	}

	@Override
	public RowSink create(String table, String fileName, ColumnSpec[] columns) {
		ensureTable(table, columns);
		try {
			// the sink keeps this connection for its lifetime (batched inserts + commit)
			Connection conn = dataSource.getConnection();
			return new JdbcRowSink(conn, table, columns, runId, seed, scenario);
		} catch (SQLException e) {
			throw new IllegalStateException("failed to open a connection for table " + table, e);
		}
	}

	// create the table on the first registration for it; synchronized so the DDL finishes before
	// any concurrent caller returns to prepare an insert against the (now guaranteed) table
	private synchronized void ensureTable(String table, ColumnSpec[] columns) {
		if (!created.add(table))
			return;
		try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
			s.execute(JdbcRowSink.createTableSql(table, columns));
		} catch (SQLException e) {
			created.remove(table); // let a later attempt retry
			throw new IllegalStateException("failed to create table " + table, e);
		}
	}
}
