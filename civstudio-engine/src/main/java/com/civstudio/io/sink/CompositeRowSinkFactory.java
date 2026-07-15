package com.civstudio.io.sink;

import java.util.List;

/**
 * A {@link RowSinkFactory} that fans each printer's rows to several backends at once —
 * the "a CSV file, a database table, or <b>both</b>" the {@link RowSink} contract names.
 * The calibration harness uses it to keep the human-readable {@code output/<seed>/*.csv}
 * for eyeballing while the SQL store (via {@link JdbcRowSinkFactory}) is what the MCP
 * query tools read. Delegates in order; {@link #name()} reports the first backend's.
 */
public final class CompositeRowSinkFactory implements RowSinkFactory {

	private final List<RowSinkFactory> factories;

	/** @param factories the backends to write every row to, in order (at least one) */
	public CompositeRowSinkFactory(RowSinkFactory... factories) {
		if (factories.length == 0)
			throw new IllegalArgumentException("a composite needs at least one factory");
		this.factories = List.of(factories);
	}

	@Override
	public RowSink create(String table, String fileName, ColumnSpec[] columns) {
		return new CompositeRowSink(
				factories.stream().map(f -> f.create(table, fileName, columns)).toList());
	}

	// a sink that mirrors every call to each delegate; construction order is preserved
	private record CompositeRowSink(List<RowSink> sinks) implements RowSink {

		@Override
		public void writeRow(Object... values) {
			for (RowSink s : sinks)
				s.writeRow(values);
		}

		@Override
		public void flush() {
			for (RowSink s : sinks)
				s.flush();
		}

		@Override
		public void close() {
			for (RowSink s : sinks)
				s.close();
		}

		@Override
		public String name() {
			return sinks.get(0).name();
		}
	}
}
