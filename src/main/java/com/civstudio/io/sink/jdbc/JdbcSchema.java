package com.civstudio.io.sink.jdbc;

import com.civstudio.io.sink.ColumnSpec;
import com.civstudio.io.sink.ColumnType;

/**
 * Maps a printer's {@link ColumnSpec} schema to Postgres DDL. The stable identity
 * tables ({@code runs}, {@code settlements}) are owned by Flyway migrations; the
 * per-metric tables are derived <b>here at runtime</b> from each printer's declared
 * columns, so a metric table can never drift out of sync with the printer that
 * fills it (add a column to a printer and its table grows with it). Each metric
 * table carries a surrogate {@code id} plus the {@code run_id}/{@code colony_id}
 * foreign keys every row is tagged with.
 */
public final class JdbcSchema {

	private JdbcSchema() {
	}

	/** The SQL identifier for a column: the declared name, lower-cased. */
	public static String sqlName(String columnName) {
		return columnName.toLowerCase();
	}

	/** The Postgres type for a {@link ColumnType}. */
	public static String pgType(ColumnType type) {
		return switch (type) {
			case DATE -> "date";
			case TEXT -> "text";
			case INT -> "bigint";
			case REAL -> "double precision";
		};
	}

	/**
	 * The {@code CREATE TABLE IF NOT EXISTS} statement for a metric table derived
	 * from {@code columns}.
	 *
	 * @param table   the metric table name
	 * @param columns the printer's typed columns
	 * @return the DDL statement
	 */
	public static String createTable(String table, ColumnSpec[] columns) {
		StringBuilder sb = new StringBuilder("CREATE TABLE IF NOT EXISTS ")
				.append(table).append(" (\n")
				.append("  id bigserial PRIMARY KEY,\n")
				.append("  run_id bigint NOT NULL REFERENCES runs(id),\n")
				.append("  colony_id bigint NOT NULL REFERENCES settlements(id)");
		for (ColumnSpec c : columns)
			sb.append(",\n  ").append(sqlName(c.name())).append(' ')
					.append(pgType(c.type()));
		sb.append("\n)");
		return sb.toString();
	}

	/**
	 * The {@code CREATE INDEX IF NOT EXISTS} statement keying a metric table by
	 * {@code (run_id, colony_id)} for per-run/per-colony queries.
	 *
	 * @param table the metric table name
	 * @return the index DDL statement
	 */
	public static String createIndex(String table) {
		return "CREATE INDEX IF NOT EXISTS " + table + "_run_colony_idx ON " + table
				+ " (run_id, colony_id)";
	}
}
