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

	private static final int RIVER_NONE = 0xFFFFFF; // pure white = no river

	private final Map<Integer, Integer> idToColor;

	// lazily loaded raster (cached after the first mask() call)
	private int[] pixels;
	private int[] river;
	private int width;
	private int height;

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
		for (int[] hit : hits) {
			int idx = (hit[1] - minY) * w + (hit[0] - minX);
			landGrid[idx] = true;
			riverGrid[idx] = hit[2] == 1;
		}
		return new ProvinceMask(minX, minY, w, h, landGrid, riverGrid);
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
	}
}
