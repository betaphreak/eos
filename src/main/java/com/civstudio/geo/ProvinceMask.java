package com.civstudio.geo;

/**
 * The pixel silhouette of one province, read from {@code data/anbennar/provinces.bmp}: a
 * rectangular grid over the province's bounding box in which each cell is either
 * <b>land</b> (a pixel of this province's colour) or not, with a parallel
 * <b>river</b> flag (from {@code data/anbennar/rivers.bmp}). It is the substrate the
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
	// the real EU4 terrain.bmp / trees.bmp palette index per cell, or -1 where the
	// overlay is absent (see ProvinceRaster); the plot field reads these to ground a
	// plot on the real map and falls back to climate generation where they are -1
	private final int[] terrainIndex;
	private final int[] treeIndex;

	ProvinceMask(int originX, int originY, int width, int height, boolean[] land, boolean[] river,
			int[] terrainIndex, int[] treeIndex) {
		this.originX = originX;
		this.originY = originY;
		this.width = width;
		this.height = height;
		this.land = land;
		this.river = river;
		this.terrainIndex = terrainIndex;
		this.treeIndex = treeIndex;
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

	/**
	 * The real {@code terrain.bmp} palette index at the local cell, or {@code -1} if
	 * the cell is outside the bbox or the terrain overlay was not loaded. Decode it
	 * with {@link MapTerrainCodec#ground}/{@link MapTerrainCodec#relief}.
	 */
	public int terrainIndex(int lx, int ly) {
		if (lx < 0 || lx >= width || ly < 0 || ly >= height)
			return -1;
		return terrainIndex[ly * width + lx];
	}

	/**
	 * The real {@code trees.bmp} palette index covering the local cell, or {@code -1}
	 * if outside the bbox or the tree overlay was not loaded. Decode it with {@link
	 * MapTerrainCodec#isWoody} (the overlay is coarse — used as a density signal).
	 */
	public int treeIndex(int lx, int ly) {
		if (lx < 0 || lx >= width || ly < 0 || ly >= height)
			return -1;
		return treeIndex[ly * width + lx];
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
