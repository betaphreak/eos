package com.civstudio.geo;

/**
 * The pixel silhouette of one province, read from {@code data/provinces.bmp}: a
 * rectangular grid over the province's bounding box in which each cell is either
 * <b>land</b> (a pixel of this province's colour) or not, with a parallel
 * <b>river</b> flag (from {@code data/rivers.bmp}). It is the substrate the
 * per-province plot generation paints onto — one land cell becomes one plot, at 1
 * raster pixel = 1 plot. See {@code docs/province-plots.md}.
 * <p>
 * Coordinates are local to the bounding box ({@code 0..width-1}, {@code
 * 0..height-1}); add {@link #originX()}/{@link #originY()} to recover the absolute
 * raster pixel. Out-of-bounds queries return {@code false} (treated as ocean), so
 * the spatial generators can probe neighbours freely at the edges.
 */
public final class ProvinceMask {

	private final int originX;
	private final int originY;
	private final int width;
	private final int height;
	private final boolean[] land; // row-major, width*height
	private final boolean[] river;

	ProvinceMask(int originX, int originY, int width, int height, boolean[] land, boolean[] river) {
		this.originX = originX;
		this.originY = originY;
		this.width = width;
		this.height = height;
		this.land = land;
		this.river = river;
	}

	/** Absolute raster x of local column 0 (the bounding-box left edge). */
	public int originX() {
		return originX;
	}

	/** Absolute raster y of local row 0 (the bounding-box top edge). */
	public int originY() {
		return originY;
	}

	/** Bounding-box width in cells. */
	public int width() {
		return width;
	}

	/** Bounding-box height in cells. */
	public int height() {
		return height;
	}

	/** Whether the local cell is the province's land (false outside the bbox). */
	public boolean isLand(int lx, int ly) {
		if (lx < 0 || lx >= width || ly < 0 || ly >= height)
			return false;
		return land[ly * width + lx];
	}

	/** Whether the local cell carried a river pixel (false outside the bbox). */
	public boolean isRiver(int lx, int ly) {
		if (lx < 0 || lx >= width || ly < 0 || ly >= height)
			return false;
		return river[ly * width + lx];
	}

	/** The number of land cells (== the province's plot count). */
	public int landCount() {
		int n = 0;
		for (boolean b : land)
			if (b)
				n++;
		return n;
	}
}
