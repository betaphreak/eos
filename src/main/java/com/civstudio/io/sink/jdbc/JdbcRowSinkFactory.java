package com.civstudio.io.sink.jdbc;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.jdbc.core.JdbcTemplate;

import com.civstudio.io.sink.ColumnSpec;
import com.civstudio.io.sink.RowSink;
import com.civstudio.io.sink.RowSinkFactory;

/**
 * Creates {@link JdbcRowSink}s for one colony of one run (the {@code runId}/{@code
 * colonyId} are fixed per factory). On first use of each metric table it ensures
 * the table and its index exist, deriving the DDL from the printer's columns (see
 * {@link JdbcSchema}). Table creation is guarded by a JVM-wide set so that, when
 * several colonies run concurrently (a thread each), only one issues the {@code
 * CREATE TABLE} per table — avoiding a concurrent-DDL race.
 */
public class JdbcRowSinkFactory implements RowSinkFactory {

	/** How many rows a sink buffers before flushing a batch insert. */
	public static final int DEFAULT_BATCH_SIZE = 256;

	// metric tables ensured this JVM run; first factory creates, others skip
	private static final Set<String> ensuredTables = ConcurrentHashMap.newKeySet();
	private static final Object DDL_LOCK = new Object();

	private final JdbcTemplate jdbc;
	private final long runId;
	private final long colonyId;
	private final int batchSize;

	public JdbcRowSinkFactory(JdbcTemplate jdbc, long runId, long colonyId) {
		this(jdbc, runId, colonyId, DEFAULT_BATCH_SIZE);
	}

	public JdbcRowSinkFactory(JdbcTemplate jdbc, long runId, long colonyId,
			int batchSize) {
		this.jdbc = jdbc;
		this.runId = runId;
		this.colonyId = colonyId;
		this.batchSize = batchSize;
	}

	@Override
	public RowSink create(String table, String fileName, ColumnSpec[] columns) {
		ensureTable(table, columns);
		return new JdbcRowSink(jdbc, table, columns, runId, colonyId, batchSize);
	}

	private void ensureTable(String table, ColumnSpec[] columns) {
		if (ensuredTables.contains(table))
			return;
		synchronized (DDL_LOCK) {
			if (ensuredTables.contains(table))
				return;
			jdbc.execute(JdbcSchema.createTable(table, columns));
			jdbc.execute(JdbcSchema.createIndex(table));
			ensuredTables.add(table);
		}
	}
}
