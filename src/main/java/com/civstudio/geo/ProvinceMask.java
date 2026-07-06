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
	// the river classification code per cell (0 = none; low digit = width 1..4, tens digit =
	// downstream flow direction 1..8, hundreds digit = node marker), from rivers.bmp via
	// ProvinceRaster.classifyRiver + RiverFlow — see docs/river-rendering.md §1/§3. Preserves
	// the authored width/nodes and the derived flow rather than a bare flag.
	private final int[] river;
	// the 4-bit sea-edge mask per cell (which orthogonal neighbour is water: 1=E,2=W,4=S,8=N;
	// 0 = inland), from ProvinceRaster over the global land/sea raster — see docs/coastlines.md.
	private final int[] coast;
	// the real EU4 terrain.bmp / trees.bmp palette index per cell, or -1 where the
	// overlay is absent (see ProvinceRaster); the plot field reads these to ground a
	// plot on the real map and falls back to climate generation where they are -1
	private final int[] terrainIndex;
	private final int[] treeIndex;
	// the real heightmap.bmp elevation per cell (0..255, the 8-bit grayscale height), or
	// 0 where the overlay is absent; a raster lookup (no generation), read by the plot
	// field so each plot carries its elevation
	private final int[] elevation;

	ProvinceMask(int originX, int originY, int width, int height, boolean[] land, int[] river,
			int[] coast, int[] terrainIndex, int[] treeIndex, int[] elevation) {
		this.originX = originX;
		this.originY = originY;
		this.width = width;
		this.height = height;
		this.land = land;
		this.river = river;
		this.coast = coast;
		this.terrainIndex = terrainIndex;
		this.treeIndex = treeIndex;
		this.elevation = elevation;
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
		return riverCode(lx, ly) != 0;
	}

	/**
	 * The river classification code at the local cell (0 outside the bbox / no river). It
	 * packs three fields as decimal digits: the <b>low</b> digit is the width level 1..4, the
	 * <b>tens</b> digit is the downstream flow direction 1..8 (0 = a sink/mouth; see {@link
	 * RiverFlow}), and the <b>hundreds</b> digit is the node marker (0 plain, 1 source, 2
	 * confluence, 3 split). e.g. {@code 53} = a width-3 river flowing direction 5 (W); {@code
	 * 141} = a source (width 1) flowing direction 4. See {@link ProvinceRaster#classifyRiver}
	 * and {@code docs/river-rendering.md} §1/§3.
	 */
	public int riverCode(int lx, int ly) {
		if (lx < 0 || lx >= width || ly < 0 || ly >= height)
			return 0;
		return river[ly * width + lx];
	}

	/**
	 * The 4-bit sea-edge mask at the local cell (0 outside the bbox / inland): which
	 * orthogonal neighbour borders water — bit {@code 1} = E, {@code 2} = W, {@code 4} = S,
	 * {@code 8} = N (matching {@code NB4}). Non-zero means the cell is coastal. See {@code
	 * docs/coastlines.md} §A.
	 */
	public int coast(int lx, int ly) {
		if (lx < 0 || lx >= width || ly < 0 || ly >= height)
			return 0;
		return coast[ly * width + lx];
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

	/**
	 * The real {@code heightmap.bmp} elevation at the local cell (0..255), or {@code 0}
	 * (sea level) outside the bbox or if the heightmap was not loaded. A raster lookup
	 * — higher is higher ground; used for hillshading and, later, gameplay elevation.
	 */
	public int elevation(int lx, int ly) {
		if (lx < 0 || lx >= width || ly < 0 || ly >= height)
			return 0;
		return elevation[ly * width + lx];
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
