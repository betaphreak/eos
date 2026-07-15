package com.civstudio.io.sink;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDate;

/**
 * A {@link RowSink} that writes a printer's rows to a SQL table via JDBC — the
 * database backend the {@link RowSinkFactory} Javadoc anticipates, built through the
 * seam the printers already declare their typed schema for. It stays driver-free (the
 * {@code java.sql} API is in the JDK); whichever launcher installs the {@link
 * JdbcRowSinkFactory} provides the driver (H2 for the local calibration harness,
 * Postgres for the server). See {@code docs/mcp-server.md} §Data backend.
 *
 * <p>Each row is prefixed with the run identity the factory carries ({@code run_id},
 * {@code seed}, {@code scenario}) so a store can {@code GROUP BY run_id} / tell runs
 * apart — the thing CSV-per-directory can't do. The printer's own {@link ColumnType}s
 * map to SQL types (see {@link #sqlType}) and drive how each value is bound. Rows are
 * batched and committed on {@link #flush()} / {@link #close()}; one {@link Connection}
 * is held for the sink's lifetime (a colony has a bounded set of printers).
 *
 * <p>A printer is owned by a single colony thread, so a sink instance is written by one
 * thread; the factory serializes the one-off DDL across colonies sharing a table.
 */
public final class JdbcRowSink implements RowSink {

	// rows buffered before an implicit flush; a printer emits at most one row per in-game
	// month, so this rarely fills mid-run — flush()/close() do the real committing.
	private static final int BATCH = 256;

	private final String table;
	private final ColumnSpec[] columns;
	private final String runId;
	private final long seed;
	private final String scenario;

	private final Connection conn;
	private final PreparedStatement insert;
	private int pending;

	JdbcRowSink(Connection conn, String table, ColumnSpec[] columns,
			String runId, long seed, String scenario) {
		this.table = table;
		this.columns = columns;
		this.runId = runId;
		this.seed = seed;
		this.scenario = scenario;
		this.conn = conn;
		try {
			conn.setAutoCommit(false);
			this.insert = conn.prepareStatement(insertSql(table, columns));
		} catch (SQLException e) {
			throw new IllegalStateException("failed to prepare insert for table " + table, e);
		}
	}

	@Override
	public void writeRow(Object... values) {
		try {
			insert.setString(1, runId);
			insert.setLong(2, seed);
			insert.setString(3, scenario);
			for (int i = 0; i < columns.length; i++)
				bind(insert, 4 + i, columns[i].type(), values[i]);
			insert.addBatch();
			if (++pending >= BATCH)
				flush();
		} catch (SQLException e) {
			throw new IllegalStateException("failed to write row to " + table, e);
		}
	}

	@Override
	public void flush() {
		if (pending == 0)
			return;
		try {
			insert.executeBatch();
			conn.commit();
			pending = 0;
		} catch (SQLException e) {
			throw new IllegalStateException("failed to flush rows to " + table, e);
		}
	}

	@Override
	public void close() {
		try (Connection c = conn; PreparedStatement ps = insert) {
			flush();
		} catch (SQLException e) {
			throw new IllegalStateException("failed to close sink for " + table, e);
		}
	}

	@Override
	public String name() {
		return table;
	}

	// bind one value to the prepared statement per its declared type; null-safe
	private static void bind(PreparedStatement ps, int idx, ColumnType type, Object value)
			throws SQLException {
		if (value == null) {
			ps.setObject(idx, null);
			return;
		}
		switch (type) {
			case DATE -> ps.setObject(idx, (LocalDate) value); // JDBC 4.2 LocalDate binding
			case INT -> ps.setLong(idx, ((Number) value).longValue());
			case REAL -> ps.setDouble(idx, ((Number) value).doubleValue());
			case TEXT -> ps.setString(idx, value instanceof Enum<?> e ? e.name() : value.toString());
		}
	}

	// INSERT INTO "table" ("run_id", "seed", "scenario", "c1", "c2", ...) VALUES (?, ?, ?, ?, ?, ...)
	private static String insertSql(String table, ColumnSpec[] columns) {
		StringBuilder cols = new StringBuilder("\"run_id\", \"seed\", \"scenario\"");
		StringBuilder qs = new StringBuilder("?, ?, ?");
		for (ColumnSpec c : columns) {
			cols.append(", ").append(quote(c.name()));
			qs.append(", ?");
		}
		return "INSERT INTO " + quote(table) + " (" + cols + ") VALUES (" + qs + ")";
	}

	/**
	 * The {@code CREATE TABLE IF NOT EXISTS} DDL for a table with these columns (used by the factory).
	 * Every identifier is quoted, so names survive exactly (mixed case, spaces — CSV headers have both)
	 * and reads are consistent across H2 and Postgres, whose unquoted identifier folding differs. The
	 * query layer therefore introspects the real names from {@code information_schema} and quotes them.
	 */
	static String createTableSql(String table, ColumnSpec[] columns) {
		StringBuilder sb = new StringBuilder("CREATE TABLE IF NOT EXISTS ")
				.append(quote(table))
				.append(" (\"run_id\" VARCHAR(64), \"seed\" BIGINT, \"scenario\" VARCHAR(128)");
		for (ColumnSpec c : columns)
			sb.append(", ").append(quote(c.name())).append(' ').append(sqlType(c.type()));
		return sb.append(')').toString();
	}

	// ColumnType → a SQL type both H2 (PostgreSQL mode) and Postgres accept
	private static String sqlType(ColumnType type) {
		return switch (type) {
			case DATE -> "DATE";
			case TEXT -> "VARCHAR";
			case INT -> "INTEGER";
			case REAL -> "DOUBLE PRECISION";
		};
	}

	// quote an identifier so column/table names with spaces or mixed case survive (CSV headers do)
	private static String quote(String id) {
		return '"' + id.replace("\"", "\"\"") + '"';
	}
}
