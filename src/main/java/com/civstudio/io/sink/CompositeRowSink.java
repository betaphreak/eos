package com.civstudio.io.sink;

/**
 * A {@link RowSink} that fans every row out to several backing sinks — used for
 * {@link OutputMode#BOTH}, where each printer writes to both a CSV file and a
 * database table.
 */
public class CompositeRowSink implements RowSink {

	private final RowSink[] sinks;
	private final String name;

	/**
	 * @param sinks the backing sinks; rows are written to each in order
	 */
	public CompositeRowSink(RowSink... sinks) {
		assert (sinks.length > 0);
		this.sinks = sinks;
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < sinks.length; i++) {
			if (i > 0)
				sb.append('+');
			sb.append(sinks[i].name());
		}
		this.name = sb.toString();
	}

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
		return name;
	}
}
