package com.civstudio.io.sink;

/**
 * A {@link RowSinkFactory} that builds a {@link CompositeRowSink} from several
 * backing factories — used for {@link OutputMode#BOTH}, so each printer writes to
 * a CSV file and a database table at once.
 */
public class CompositeRowSinkFactory implements RowSinkFactory {

	private final RowSinkFactory[] factories;

	/**
	 * @param factories the backing factories; each printer's sink fans out to all
	 */
	public CompositeRowSinkFactory(RowSinkFactory... factories) {
		assert (factories.length > 0);
		this.factories = factories;
	}

	@Override
	public RowSink create(String table, String fileName, ColumnSpec[] columns) {
		RowSink[] sinks = new RowSink[factories.length];
		for (int i = 0; i < factories.length; i++)
			sinks[i] = factories[i].create(table, fileName, columns);
		return new CompositeRowSink(sinks);
	}
}
