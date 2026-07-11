package com.civstudio.geo.export;

import com.civstudio.data.AnbennarFiles;

import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import javax.imageio.ImageIO;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Dev tool: exports {@code map/borders.json} — a simplified polygon outline for
 * every land province, so a presentation layer (the {@code web/} dashboard, an
 * eventual player map) can fill/stroke provinces and hit-test clicks against real
 * shapes rather than snapping to a nearest centroid. The geometry is canonical and
 * reusable, living next to {@code provinces.json} in the map resources.
 * <p>
 * It reads the same source raster the other exporters do, {@code
 * data/anbennar/provinces.bmp} (24-bit, one colour per province), and resolves
 * colours to ids/names via {@code data/anbennar/definition.csv}. It keeps every land
 * province plus the <b>coastal</b> sea/lake provinces — those that grew a shelf grid
 * (so the web can hover/select them like land, {@code docs/coastlines.md} Phase F);
 * deep-ocean provinces with no shelf are skipped (their huge outlines are pure clutter
 * and they ship nothing).
 * <p>
 * For each kept province it: (1) frames the province's pixels to their bounding
 * box, (2) splits them into 8-connected components (islands/exclaves each become a
 * ring), (3) traces each component's outer boundary with Moore-neighbour tracing,
 * and (4) simplifies the ring with Douglas–Peucker. Interior holes (a province
 * fully enclosing another) are not traced — the outer ring is enough for a fill.
 * Coordinates are <b>absolute source pixels</b> on the 5632&times;2048 raster, the
 * same space {@code provinces.json}'s {@code lon}/{@code lat} invert to, so the
 * rings align 1:1 with the province dots and caravan routes without reprojection.
 * <p>
 * Output is one compact JSON array of {@code {id, rings:[[[x,y],…],…]}} sorted by
 * id. Run via:
 *
 * <pre>
 * mvn compile exec:exec -Dsim.main=com.civstudio.geo.export.ProvinceBorderExporter
 * </pre>
 *
 * See {@code docs/geography.md}.
 */
public final class ProvinceBorderExporter {

	private static final String DEFINITIONS = "map/definition.csv";
	private static final String PROVINCES_BMP = "map/provinces.bmp";
	private static final String PROVINCES_JSON = "civstudio-engine/src/main/resources/generated/map/provinces.json";
	private static final String GRID_DIR = "civstudio-engine/src/main/resources/map/provinces";
	private static final String OUTPUT = "civstudio-engine/src/main/resources/generated/map/borders.json";

	private static final Pattern PLACEHOLDER_NAME = Pattern.compile("Anbennar\\d+");

	/** Drop components smaller than this many pixels — stray colour speckle, not real land. */
	private static final int MIN_COMPONENT_PX = 6;
	/** Douglas–Peucker tolerance in pixels: below this, staircase detail is dropped. */
	private static final double SIMPLIFY_EPS = 1.5;

	private ProvinceBorderExporter() {
	}

	public static void main(String[] args) throws Exception {
		Map<Integer, Integer> idToColor = invertDefinitions();   // province id -> rgb
		List<Integer> landIds = loadLandProvinceIds();           // ids kept, in provinces.json order

		BufferedImage img = ImageIO.read(AnbennarFiles.get(PROVINCES_BMP).toFile());
		int w = img.getWidth(), h = img.getHeight();
		int[] pixels = img.getRGB(0, 0, w, h, null, 0, w);
		img = null;

		// pass 1: bounding box of every kept colour, in one sweep
		Map<Integer, int[]> bbox = new HashMap<>();               // colour -> {minX,minY,maxX,maxY}
		Map<Integer, Integer> colorToId = new HashMap<>();
		for (int id : landIds) {
			Integer c = idToColor.get(id);
			if (c != null) colorToId.put(c, id);
		}
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				int c = pixels[y * w + x] & 0xFFFFFF;
				if (!colorToId.containsKey(c)) continue;
				int[] bb = bbox.get(c);
				if (bb == null) bbox.put(c, new int[] { x, y, x, y });
				else {
					if (x < bb[0]) bb[0] = x;
					if (y < bb[1]) bb[1] = y;
					if (x > bb[2]) bb[2] = x;
					if (y > bb[3]) bb[3] = y;
				}
			}
		}

		// pass 2: trace each province from its bounding box
		List<Map<String, Object>> rows = new ArrayList<>(landIds.size());
		long totalPts = 0;
		for (int id : landIds) {
			Integer color = idToColor.get(id);
			int[] bb = color == null ? null : bbox.get(color);
			if (bb == null) continue;                              // no pixels on the map
			List<List<int[]>> rings = traceProvince(pixels, w, color, bb);
			if (rings.isEmpty()) continue;
			for (List<int[]> r : rings) totalPts += r.size();
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("id", id);
			row.put("rings", rings);
			rows.add(row);
		}

		ObjectMapper mapper = new ObjectMapper();
		File out = new File(OUTPUT);
		mapper.writeValue(out, rows);
		System.out.printf("wrote %d province outlines (%d points, %.0f KB) to %s%n",
				rows.size(), totalPts, out.length() / 1024.0, out.getAbsolutePath());
	}

	/** Parse {@code definition.csv} into a province-id &rarr; rgb map (mirrors ProvinceExporter). */
	private static Map<Integer, Integer> invertDefinitions() throws Exception {
		Map<Integer, Integer> idToColor = new HashMap<>();
		try (BufferedReader br = new BufferedReader(new InputStreamReader(
				Files.newInputStream(AnbennarFiles.get(DEFINITIONS)), StandardCharsets.UTF_8))) {
			br.readLine(); // header
			String line;
			while ((line = br.readLine()) != null) {
				line = line.trim();
				if (line.isEmpty() || line.startsWith("#")) continue;
				String[] p = line.split(";", -1);
				if (p.length < 5) continue;
				try {
					int id = Integer.parseInt(p[0].trim());
					int r = Integer.parseInt(p[1].trim()), g = Integer.parseInt(p[2].trim()), b = Integer.parseInt(p[3].trim());
					String name = p[4].trim();
					if (name.startsWith("RNW") || name.startsWith("Unused")) continue;
					if (PLACEHOLDER_NAME.matcher(name.split("_#")[0].replace('_', ' ').trim()).matches()) continue;
					idToColor.put(id, (r << 16) | (g << 8) | b);
				} catch (NumberFormatException ignored) {
					// malformed line — skip
				}
			}
		}
		return idToColor;
	}

	/**
	 * The ids to outline, in {@code provinces.json} order: every land province, plus the
	 * <b>coastal</b> sea/lake provinces — those that grew a shelf grid ({@code
	 * map/provinces/<id>.json.gz}), so the web can hover/select them like land. Deep-ocean
	 * provinces with no shelf are skipped (their huge outlines would be pure clutter, and they
	 * ship nothing). The grids must exist first ({@link WorldPlotGenerator}).
	 */
	private static List<Integer> loadLandProvinceIds() throws Exception {
		ObjectMapper mapper = new ObjectMapper();
		List<Map<String, Object>> provs = mapper.readValue(new File(PROVINCES_JSON),
				new TypeReference<List<Map<String, Object>>>() {
				});
		List<Integer> ids = new ArrayList<>();
		for (Map<String, Object> p : provs) {
			String type = String.valueOf(p.get("type"));
			int id = ((Number) p.get("id")).intValue();
			// a sea/lake province ships (and so is outlined) only if it grew a coastal shelf
			if (("SEA".equals(type) || "LAKE".equals(type))
					&& !new File(GRID_DIR, id + ".json.gz").exists())
				continue;
			ids.add(id);
		}
		return ids;
	}

	/**
	 * Frame a province's pixels to a local mask, split into 8-connected components,
	 * and trace + simplify each into an absolute-pixel ring.
	 */
	private static List<List<int[]>> traceProvince(int[] pixels, int imgW, int color, int[] bb) {
		int minX = bb[0], minY = bb[1], lw = bb[2] - bb[0] + 1, lh = bb[3] - bb[1] + 1;
		boolean[] mask = new boolean[lw * lh];
		for (int ly = 0; ly < lh; ly++)
			for (int lx = 0; lx < lw; lx++)
				if ((pixels[(minY + ly) * imgW + (minX + lx)] & 0xFFFFFF) == color)
					mask[ly * lw + lx] = true;

		List<List<int[]>> rings = new ArrayList<>();
		int[] comp = new int[lw * lh];   // 0 = unvisited/outside, else component label
		int label = 0;
		int[] dxs = { -1, 1, 0, 0, -1, -1, 1, 1 }, dys = { 0, 0, -1, 1, -1, 1, -1, 1 };  // 8-neighbourhood
		Deque<int[]> stack = new ArrayDeque<>();
		for (int sy = 0; sy < lh; sy++) {
			for (int sx = 0; sx < lw; sx++) {
				if (!mask[sy * lw + sx] || comp[sy * lw + sx] != 0) continue;
				// flood the component (records its size and its topmost-left seed for tracing)
				label++;
				int count = 0, seedX = sx, seedY = sy;
				stack.push(new int[] { sx, sy });
				comp[sy * lw + sx] = label;
				while (!stack.isEmpty()) {
					int[] c = stack.pop();
					count++;
					if (c[1] < seedY || (c[1] == seedY && c[0] < seedX)) { seedX = c[0]; seedY = c[1]; }
					for (int k = 0; k < 8; k++) {
						int nx = c[0] + dxs[k], ny = c[1] + dys[k];
						if (nx < 0 || nx >= lw || ny < 0 || ny >= lh) continue;
						if (mask[ny * lw + nx] && comp[ny * lw + nx] == 0) {
							comp[ny * lw + nx] = label;
							stack.push(new int[] { nx, ny });
						}
					}
				}
				if (count < MIN_COMPONENT_PX) continue;
				List<int[]> ring = mooreTrace(comp, lw, lh, seedX, seedY, label);
				if (ring.size() < 3) continue;
				ring = simplify(ring, SIMPLIFY_EPS);
				// close the ring is implicit; translate to absolute pixels
				List<int[]> abs = new ArrayList<>(ring.size());
				for (int[] pt : ring) abs.add(new int[] { pt[0] + minX, pt[1] + minY });
				rings.add(abs);
			}
		}
		return rings;
	}

	// Moore neighbourhood in clockwise order starting North: N, NE, E, SE, S, SW, W, NW
	private static final int[] MDX = { 0, 1, 1, 1, 0, -1, -1, -1 };
	private static final int[] MDY = { -1, -1, 0, 1, 1, 1, 0, -1 };

	/**
	 * Moore-neighbour boundary tracing of one labelled component, clockwise from its
	 * topmost-then-leftmost cell (whose West neighbour is guaranteed background, the
	 * initial backtrack). Returns the ordered boundary cells in local coordinates,
	 * stopping when the walk returns to the start cell.
	 */
	private static List<int[]> mooreTrace(int[] comp, int lw, int lh, int startX, int startY, int label) {
		List<int[]> contour = new ArrayList<>();
		contour.add(new int[] { startX, startY });
		int px = startX, py = startY;
		int bIdx = 6;   // West — the known outside cell we entered the seed from
		int guard = 0, maxSteps = 8 * lw * lh + 16;
		while (guard++ < maxSteps) {
			int found = -1;
			for (int k = 1; k <= 8; k++) {
				int idx = (bIdx + k) & 7;
				int nx = px + MDX[idx], ny = py + MDY[idx];
				if (nx >= 0 && nx < lw && ny >= 0 && ny < lh && comp[ny * lw + nx] == label) { found = idx; break; }
			}
			if (found == -1) break;                          // isolated cell
			int cx = px + MDX[found], cy = py + MDY[found];
			if (cx == startX && cy == startY) break;         // closed the loop
			contour.add(new int[] { cx, cy });
			// the neighbour just before `found` was the last background cell — it becomes
			// the new backtrack; re-express its position relative to the new current cell.
			int prevIdx = (found + 7) & 7;
			int bx = px + MDX[prevIdx], by = py + MDY[prevIdx];
			px = cx; py = cy;
			bIdx = dirIndex(px, py, bx, by);
		}
		return contour;
	}

	/** Moore index (0..7) of the adjacent cell (bx,by) relative to (px,py); 6 (West) if not adjacent. */
	private static int dirIndex(int px, int py, int bx, int by) {
		for (int i = 0; i < 8; i++) if (px + MDX[i] == bx && py + MDY[i] == by) return i;
		return 6;
	}

	/** Iterative Douglas–Peucker on a closed ring (keeps first==last implicit). */
	private static List<int[]> simplify(List<int[]> ring, double eps) {
		int n = ring.size();
		if (n < 4) return ring;
		boolean[] keep = new boolean[n];
		keep[0] = true; keep[n - 1] = true;
		Deque<int[]> segs = new ArrayDeque<>();
		segs.push(new int[] { 0, n - 1 });
		double eps2 = eps * eps;
		while (!segs.isEmpty()) {
			int[] s = segs.pop();
			int a = s[0], b = s[1];
			double maxD = -1;
			int far = -1;
			for (int i = a + 1; i < b; i++) {
				double d = segDist2(ring.get(i), ring.get(a), ring.get(b));
				if (d > maxD) { maxD = d; far = i; }
			}
			if (far != -1 && maxD > eps2) {
				keep[far] = true;
				segs.push(new int[] { a, far });
				segs.push(new int[] { far, b });
			}
		}
		List<int[]> outp = new ArrayList<>();
		for (int i = 0; i < n; i++) if (keep[i]) outp.add(ring.get(i));
		return outp;
	}

	/** Squared distance from point p to segment a-b. */
	private static double segDist2(int[] p, int[] a, int[] b) {
		double vx = b[0] - a[0], vy = b[1] - a[1];
		double wx = p[0] - a[0], wy = p[1] - a[1];
		double vv = vx * vx + vy * vy;
		double t = vv == 0 ? 0 : (wx * vx + wy * vy) / vv;
		t = Math.max(0, Math.min(1, t));
		double dx = wx - t * vx, dy = wy - t * vy;
		return dx * dx + dy * dy;
	}
}
