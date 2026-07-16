package com.civstudio.geo.names;

/**
 * An inclusive pixel bounding box on the source raster — a region's footprint,
 * used to normalize a plot's absolute {@code (x,y)} to {@code [0,1]²} before
 * projecting it into the mapped Earth country's lat/lon box. Built at bake time
 * from the union of a region's plot pixels.
 *
 * @param minX westmost pixel column (inclusive)
 * @param minY northmost pixel row (inclusive)
 * @param maxX eastmost pixel column (inclusive)
 * @param maxY southmost pixel row (inclusive)
 */
public record PixelBox(int minX, int minY, int maxX, int maxY) {

	/** A growable accumulator: fold in pixels, then {@link #build()}. */
	public static final class Builder {
		private int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE;
		private int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;

		/** Extend the box to include pixel {@code (x,y)}. */
		public Builder add(int x, int y) {
			if (x < minX)
				minX = x;
			if (y < minY)
				minY = y;
			if (x > maxX)
				maxX = x;
			if (y > maxY)
				maxY = y;
			return this;
		}

		/** Whether any pixel has been added. */
		public boolean isEmpty() {
			return minX > maxX;
		}

		/** The accumulated box. */
		public PixelBox build() {
			if (isEmpty())
				throw new IllegalStateException("no pixels added");
			return new PixelBox(minX, minY, maxX, maxY);
		}
	}

	/** Number of pixel columns spanned (at least 1). */
	public int width() {
		return maxX - minX + 1;
	}

	/** Number of pixel rows spanned (at least 1). */
	public int height() {
		return maxY - minY + 1;
	}

	/**
	 * Horizontal position of column {@code x} within the box, in {@code [0,1]}
	 * (0 at the west edge, 1 at the east).
	 */
	public double u(int x) {
		return width() <= 1 ? 0.5 : clamp01((x - minX) / (double) (width() - 1));
	}

	/**
	 * Vertical position of row {@code y} within the box, in {@code [0,1]}
	 * (0 at the north edge, 1 at the south).
	 */
	public double v(int y) {
		return height() <= 1 ? 0.5 : clamp01((y - minY) / (double) (height() - 1));
	}

	private static double clamp01(double t) {
		return t < 0 ? 0 : (t > 1 ? 1 : t);
	}
}
