package com.civstudio.io.sink.jdbc;

import java.util.ArrayList;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;

import com.civstudio.io.sink.ColumnSpec;
import com.civstudio.io.sink.ColumnType;
import com.civstudio.io.sink.RowSink;

/**
 * A {@link RowSink} that batch-inserts rows into a Postgres table via a {@link
 * JdbcTemplate}. Every row is tagged with the run and colony it belongs to
 * ({@code run_id}, {@code colony_id}), so all colonies of all runs share one table
 * per metric, told apart by those keys (the way the CSV backend used a per-colony
 * file-name prefix).
 * <p>
 * Rows accumulate in an in-memory batch and are flushed when the batch fills or the
 * sink is {@linkplain #close() closed}. One sink is used by one printer on one
 * colony thread, so its batch needs no synchronization (the shared {@link
 * JdbcTemplate}/{@code DataSource} is itself thread-safe).
 */
public class JdbcRowSink implements RowSink {

	private final JdbcTemplate jdbc;
	private final String table;
	private final ColumnType[] types;
	private final String insertSql;
	private final long runId;
	private final long colonyId;
	private final int batchSize;
	private final List<Object[]> batch = new ArrayList<>();

	/**
	 * @param jdbc      the shared, pooled template
	 * @param table     the metric table name
	 * @param columns   the printer's typed columns (matching {@link #writeRow})
	 * @param runId     the run this colony belongs to
	 * @param colonyId  the colony these rows belong to
	 * @param batchSize how many rows to buffer before flushing
	 */
	public JdbcRowSink(JdbcTemplate jdbc, String table, ColumnSpec[] columns,
			long runId, long colonyId, int batchSize) {
		this.jdbc = jdbc;
		this.table = table;
		this.runId = runId;
		this.colonyId = colonyId;
		this.batchSize = batchSize;

		this.types = new ColumnType[columns.length];
		StringBuilder cols = new StringBuilder("run_id, colony_id");
		StringBuilder qs = new StringBuilder("?, ?");
		for (int i = 0; i < columns.length; i++) {
			types[i] = columns[i].type();
			cols.append(", ").append(JdbcSchema.sqlName(columns[i].name()));
			qs.append(", ?");
		}
		this.insertSql = "INSERT INTO " + table + " (" + cols + ") VALUES (" + qs
				+ ")";
	}

	@Override
	public void writeRow(Object... values) {
		assert (values.length == types.length);
		Object[] args = new Object[values.length + 2];
		args[0] = runId;
		args[1] = colonyId;
		for (int i = 0; i < values.length; i++)
			args[i + 2] = bind(values[i], types[i]);
		batch.add(args);
		if (batch.size() >= batchSize)
			flush();
	}

	// coerce a raw printer value to a JDBC-friendly form for its column type; a
	// TEXT column may receive an enum (e.g. CurrencyType) which must go as a String
	private static Object bind(Object value, ColumnType type) {
		if (value == null)
			return null;
		return type == ColumnType.TEXT ? value.toString() : value;
	}

	@Override
	public void flush() {
		if (batch.isEmpty())
			return;
		jdbc.batchUpdate(insertSql, batch);
		batch.clear();
	}

	@Override
	public void close() {
		flush();
	}

	@Override
	public String name() {
		return table;
	}
}
