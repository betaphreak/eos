package com.civstudio.geo;

import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.imageio.ImageIO;

/**
 * Reads a single province's pixel {@link ProvinceMask silhouette} from the
 * committed Anbennar rasters under {@code data/} ({@code definition.csv} for the
 * colour↔id map, {@code provinces.bmp} for the shapes, {@code rivers.bmp} for
 * water), on demand. This is the runtime counterpart to the build-time {@link
 * com.civstudio.geo.export.ProvinceExporter} — where the exporter scans the whole
 * map to summarise every province, this loads <em>one</em> province's mask so its
 * plot field can be generated when a settlement first needs it (see {@code
 * docs/province-plots.md}).
 * <p>
 * The {@code definition.csv} id→colour map is parsed up front (cheap); the two
 * BMP pixel arrays are loaded lazily on the first {@link #mask(int)} call and
 * cached, so repeated province lookups in one session pay the ~46&nbsp;MB read
 * once. Paths are relative to the working directory (the project root under
 * Maven), matching the other {@code data/}-reading tools.
 */
public final class ProvinceRaster {

	private static final String DEFINITIONS = "data/definition.csv";
	private static final String PROVINCES_BMP = "data/provinces.bmp";
	private static final String RIVERS_BMP = "data/rivers.bmp";
	private static final String TERRAIN_BMP = "data/terrain.bmp";
	private static final String TREES_BMP = "data/trees.bmp";

	private static final int RIVER_NONE = 0xFFFFFF; // pure white = no river

	private final Map<Integer, Integer> idToColor;

	// lazily loaded raster (cached after the first mask() call)
	private int[] pixels;
	private int[] river;
	private int width;
	private int height;

	// the real EU4 terrain/tree overlays (8-bit indexed): terrainIdx is full
	// resolution (1:1 with the province raster); treeIdx is the smaller trees.bmp,
	// sampled by scaling province coordinates into it (see treeIndexAt). Both are
	// optional — a missing file leaves the array null and the mask falls back.
	private int[] terrainIdx;
	private int[] treeIdx;
	private int treeWidth;
	private int treeHeight;

	private ProvinceRaster(Map<Integer, Integer> idToColor) {
		this.idToColor = idToColor;
	}

	/** Load the {@code definition.csv} id→colour map (the BMPs load on first use). */
	public static ProvinceRaster load() throws IOException {
		Map<Integer, Integer> idToColor = new HashMap<>();
		try (BufferedReader br = new BufferedReader(new InputStreamReader(
				Files.newInputStream(new File(DEFINITIONS).toPath()), StandardCharsets.UTF_8))) {
			br.readLine(); // header
			String line;
			while ((line = br.readLine()) != null) {
				line = line.trim();
				if (line.isEmpty() || line.startsWith("#"))
					continue;
				String[] parts = line.split(";", -1);
				if (parts.length < 5)
					continue;
				try {
					int id = Integer.parseInt(parts[0].trim());
					int r = Integer.parseInt(parts[1].trim());
					int g = Integer.parseInt(parts[2].trim());
					int b = Integer.parseInt(parts[3].trim());
					idToColor.put(id, (r << 16) | (g << 8) | b);
				} catch (NumberFormatException ignored) {
					// malformed row — skip
				}
			}
		}
		return new ProvinceRaster(idToColor);
	}

	/**
	 * The pixel mask of the province with this {@code province_id}: its land cells
	 * (pixels of its colour) and their river flags, framed to its bounding box.
	 *
	 * @param provinceId the game province id
	 * @return the province's mask
	 * @throws IllegalArgumentException if the id has no colour in {@code definition.csv}
	 * @throws IllegalStateException    if the province has no pixels on the map
	 */
	public ProvinceMask mask(int provinceId) throws IOException {
		Integer color = idToColor.get(provinceId);
		if (color == null)
			throw new IllegalArgumentException("no colour for province " + provinceId);
		ensureRaster();

		int target = color;
		int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE;
		int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;
		List<int[]> hits = new ArrayList<>(); // x, y, riverFlag
		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				int i = y * width + x;
				if ((pixels[i] & 0xFFFFFF) != target)
					continue;
				int riverFlag = (river[i] & 0xFFFFFF) != RIVER_NONE ? 1 : 0;
				hits.add(new int[] { x, y, riverFlag });
				if (x < minX) minX = x;
				if (x > maxX) maxX = x;
				if (y < minY) minY = y;
				if (y > maxY) maxY = y;
			}
		}
		if (hits.isEmpty())
			throw new IllegalStateException("province " + provinceId + " has no pixels");

		int w = maxX - minX + 1;
		int h = maxY - minY + 1;
		boolean[] landGrid = new boolean[w * h];
		boolean[] riverGrid = new boolean[w * h];
		// per-cell EU4 terrain/tree palette indices, framed to the same bounding box
		// (-1 where the overlay is absent or out of bounds — the mask treats it as
		// "unmapped" and the plot field falls back to climate generation)
		int[] terrainGrid = new int[w * h];
		int[] treeGrid = new int[w * h];
		java.util.Arrays.fill(terrainGrid, -1);
		java.util.Arrays.fill(treeGrid, -1);
		for (int[] hit : hits) {
			int ax = hit[0], ay = hit[1];
			int idx = (ay - minY) * w + (ax - minX);
			landGrid[idx] = true;
			riverGrid[idx] = hit[2] == 1;
			if (terrainIdx != null)
				terrainGrid[idx] = terrainIdx[ay * width + ax];
			if (treeIdx != null)
				treeGrid[idx] = treeIndexAt(ax, ay);
		}
		return new ProvinceMask(minX, minY, w, h, landGrid, riverGrid, terrainGrid, treeGrid);
	}

	// the trees.bmp palette index covering an absolute province pixel: trees.bmp is
	// a coarser raster (732x266 vs 5632x2048, ~1/7.7 each axis), so the province
	// coordinate is scaled into its grid. Clamped to the trees raster bounds.
	private int treeIndexAt(int ax, int ay) {
		int tx = Math.min(treeWidth - 1, ax * treeWidth / width);
		int ty = Math.min(treeHeight - 1, ay * treeHeight / height);
		return treeIdx[ty * treeWidth + tx];
	}

	private void ensureRaster() throws IOException {
		if (pixels != null)
			return;
		BufferedImage img = ImageIO.read(new File(PROVINCES_BMP));
		BufferedImage rImg = ImageIO.read(new File(RIVERS_BMP));
		this.width = img.getWidth();
		this.height = img.getHeight();
		if (width != rImg.getWidth() || height != rImg.getHeight())
			throw new IllegalStateException("province/river raster dimensions differ");
		this.pixels = img.getRGB(0, 0, width, height, null, 0, width);
		this.river = rImg.getRGB(0, 0, width, height, null, 0, width);
		this.terrainIdx = loadIndexed(TERRAIN_BMP, width, height);
		this.treeIdx = loadTreeOverlay();
	}

	// read an 8-bit indexed BMP's raw palette indices (not RGB) into a row-major
	// int[]. Returns null if the file is missing or its dimensions do not match the
	// expected (w, h) — the overlay is optional, so a mismatch degrades gracefully
	// to climate generation rather than failing the run.
	private static int[] loadIndexed(String path, int expectW, int expectH) throws IOException {
		File f = new File(path);
		if (!f.exists())
			return null;
		BufferedImage img = ImageIO.read(f);
		if (img.getWidth() != expectW || img.getHeight() != expectH)
			return null;
		return img.getRaster().getSamples(0, 0, expectW, expectH, 0, (int[]) null);
	}

	// load trees.bmp at its own (smaller) resolution; it is sampled by scaling, so
	// it need not match the province raster. Records its dimensions for treeIndexAt.
	private int[] loadTreeOverlay() throws IOException {
		File f = new File(TREES_BMP);
		if (!f.exists())
			return null;
		BufferedImage img = ImageIO.read(f);
		this.treeWidth = img.getWidth();
		this.treeHeight = img.getHeight();
		return img.getRaster().getSamples(0, 0, treeWidth, treeHeight, 0, (int[]) null);
	}
}
