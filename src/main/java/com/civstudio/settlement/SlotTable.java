package com.civstudio.settlement;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The precalculated settlement slot table: a {@code size -> }{@link SlotInfo}
 * lookup loaded once from {@code /slots.json} (the exported design
 * spreadsheet). It is pure geometry — independent of seed and location — so a
 * single instance is shared by every colony in a {@link GameSession}, which
 * loads it at start and exposes it via {@code getSlotTable()}. A {@link
 * Settlement} reads its current row through {@link #forSize(int)} rather than
 * recomputing the formulas each step.
 * <p>
 * The table runs from size 0 to {@link #maxSize()} (95 in the bundled data,
 * where roads have eaten the disc back down to ~830 effective slots — a colony
 * would take far more than a normal run to approach it). Colonies are founded
 * at {@link #MIN_SIZE} and grow upward from there.
 */
public final class SlotTable {

	private static final String RESOURCE = "/slots.json";

	private static final ObjectMapper MAPPER = new ObjectMapper()
			.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

	/**
	 * The smallest size a colony is founded at (a floor): big enough to hold the
	 * standard firm count with room to spare ({@code effective} = 15 at size 3).
	 * A colony auto-grows past this when it needs more slots.
	 */
	public static final int MIN_SIZE = 3;

	// rows indexed by size, 0..maxSize (contiguous)
	private final SlotInfo[] bySize;

	private SlotTable(SlotInfo[] bySize) {
		this.bySize = bySize;
	}

	/**
	 * Load the slot table from its classpath resource ({@code /slots.json}).
	 *
	 * @return the loaded table
	 * @throws IllegalStateException
	 *             if the resource is missing or its sizes are not the contiguous
	 *             range {@code 0..n-1}
	 */
	public static SlotTable load() {
		try (InputStream in = SlotTable.class.getResourceAsStream(RESOURCE)) {
			if (in == null)
				throw new IllegalStateException(
						"Slot table resource not found: " + RESOURCE);
			List<SlotInfo> rows = MAPPER.readValue(in,
					new TypeReference<List<SlotInfo>>() {
					});
			SlotInfo[] bySize = new SlotInfo[rows.size()];
			for (SlotInfo row : rows) {
				if (row.size() < 0 || row.size() >= bySize.length
						|| bySize[row.size()] != null)
					throw new IllegalStateException(
							"slot table sizes must be the contiguous range 0.."
									+ (bySize.length - 1) + "; bad size "
									+ row.size());
				bySize[row.size()] = row;
			}
			for (int i = 0; i < bySize.length; i++)
				if (bySize[i] == null)
					throw new IllegalStateException(
							"slot table missing row for size " + i);
			return new SlotTable(bySize);
		} catch (IOException e) {
			throw new UncheckedIOException(
					"Failed to load slot table resource: " + RESOURCE, e);
		}
	}

	/** The largest size the table describes (a colony cannot grow past it). */
	public int maxSize() {
		return bySize.length - 1;
	}

	/**
	 * The slot geometry at <tt>size</tt>.
	 *
	 * @param size
	 *            a settlement size in {@code [0, maxSize()]}
	 * @return the row for that size
	 * @throws IndexOutOfBoundsException
	 *             if {@code size} is outside the table
	 */
	public SlotInfo forSize(int size) {
		if (size < 0 || size >= bySize.length)
			throw new IndexOutOfBoundsException(
					"size " + size + " outside slot table [0, " + maxSize() + "]");
		return bySize[size];
	}
}
