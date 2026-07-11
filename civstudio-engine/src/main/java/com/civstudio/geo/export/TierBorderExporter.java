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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import javax.imageio.ImageIO;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Dev tool: exports {@code map/tierborders.json} — dissolved polygon outlines for each
 * geographic <b>tier</b> (continent, super-region, region), so the map can draw region →
 * super-region → continent boundaries zoom-banded, the same way {@link ProvinceBorderExporter}
 * outlines provinces. Precomputed in Java (from the raster, not the browser) and committed;
 * the page loads them as a static asset and projects them with the same source-pixel maps the
 * province rings use, so a region's outline pins to the terrain 1:1.
 * <p>
 * It reuses the province tracing exactly, generalized from one-colour-per-province to
 * one-<em>group</em>-per-tier-key: every land province's pixels are stamped with its tier
 * group, then each group's 8-connected components are Moore-traced and Douglas–Peucker
 * simplified into absolute-pixel rings (multiple rings per tier — a region has islands and
 * exclaves). Interior holes are not traced (the outer ring is enough), matching the province
 * outlines. Coarser tiers simplify harder (they are only ever seen zoomed out).
 * <p>
 * Grouping keys are the raw Clausewitz keys the client already resolves to display names
 * ({@code province.continent}, {@code province.region}, and the region→super-region map from
 * {@code superregions.json}); {@code build.mjs} attaches the rings to the matching label tier.
 *
 * <pre>
 * mvn compile exec:exec -Dsim.main=com.civstudio.geo.export.TierBorderExporter
 * </pre>
 *
 * See {@code docs/geography.md} and {@code web/README.md}.
 */
public final class TierBorderExporter {

	private static final String DEFINITIONS = "map/definition.csv";
	private static final String PROVINCES_BMP = "map/provinces.bmp";
	private static final String PROVINCES_JSON = "civstudio-engine/src/main/resources/generated/map/provinces.json";
	private static final String SUPERREGIONS_JSON = "civstudio-engine/src/main/resources/generated/map/superregions.json";
	private static final String OUTPUT = "civstudio-engine/src/main/resources/generated/map/tierborders.json";

	private static final Pattern PLACEHOLDER_NAME = Pattern.compile("Anbennar\\d+");

	/** Water province types excluded from land-tier silhouettes. */
	private static final Set<String> WATER = Set.of("SEA", "LAKE", "OCEAN");

	// one tier: its per-province key function, plus tracing coarseness (bigger = fewer points /
	// drop smaller islets, since a coarser tier is only viewed further out)
	private record Tier(String name, double simplifyEps, int minComponentPx) {
	}

	private static final Tier CONTINENTS = new Tier("continents", 3.5, 40);
	private static final Tier SUPER_REGIONS = new Tier("superRegions", 2.5, 20);
	private static final Tier REGIONS = new Tier("regions", 2.0, 10);

	private TierBorderExporter() {
	}

	public static void main(String[] args) throws Exception {
		Map<Integer, Integer> idToColor = invertDefinitions();            // province id -> rgb
		List<Map<String, Object>> provs = loadProvinces();
		Map<String, String> regionToSuper = loadRegionToSuper();          // region key -> super key

		BufferedImage img = ImageIO.read(AnbennarFiles.get(PROVINCES_BMP).toFile());
		int w = img.getWidth(), h = img.getHeight();
		int[] pixels = img.getRGB(0, 0, w, h, null, 0, w);
		img = null;

		// colour -> tier key, one map per tier (a land province's colour maps to its continent /
		// super-region / region key; water and keyless provinces are left out)
		Map<Integer, String> byContinent = new HashMap<>();
		Map<Integer, String> bySuper = new HashMap<>();
		Map<Integer, String> byRegion = new HashMap<>();
		for (Map<String, Object> p : provs) {
			int id = ((Number) p.get("id")).intValue();
			Integer color = idToColor.get(id);
			if (color == null || WATER.contains(String.valueOf(p.get("type"))))
				continue;
			String continent = str(p.get("continent")), region = str(p.get("region"));
			if (continent != null) byContinent.put(color, continent);
			if (region != null) {
				byRegion.put(color, region);
				String sup = regionToSuper.get(region);
				if (sup != null) bySuper.put(color, sup);
			}
		}

		Map<String, Object> out = new LinkedHashMap<>();
		int[] group = new int[w * h];   // reused per tier: 1-based group index per pixel, 0 = none
		out.put(CONTINENTS.name(), traceTier(pixels, w, h, byContinent, CONTINENTS, group));
		out.put(SUPER_REGIONS.name(), traceTier(pixels, w, h, bySuper, SUPER_REGIONS, group));
		out.put(REGIONS.name(), traceTier(pixels, w, h, byRegion, REGIONS, group));

		File file = new File(OUTPUT);
		new ObjectMapper().writeValue(file, out);
		System.out.printf("wrote tier outlines (%.0f KB) to %s%n",
				file.length() / 1024.0, file.getAbsolutePath());
	}

	// trace one tier: stamp each pixel with its group, then Moore-trace every group's components
	private static Map<String, Object> traceTier(int[] pixels, int w, int h,
			Map<Integer, String> colorToKey, Tier tier, int[] group) {
		// index the distinct tier keys (1-based, so 0 stays "no group")
		List<String> keys = new ArrayList<>(new HashSet<>(colorToKey.values()));
		Map<String, Integer> keyIndex = new HashMap<>();
		for (int i = 0; i < keys.size(); i++)
			keyIndex.put(keys.get(i), i + 1);
		Map<Integer, Integer> colorToGroup = new HashMap<>();
		for (Map.Entry<Integer, String> e : colorToKey.entrySet())
			colorToGroup.put(e.getKey(), keyIndex.get(e.getValue()));

		// pass 1: stamp group index + accumulate a bbox per group
		int[][] bbox = new int[keys.size() + 1][];   // groupIdx -> {minX,minY,maxX,maxY}
		for (int y = 0; y < h; y++) {
			int row = y * w;
			for (int x = 0; x < w; x++) {
				Integer g = colorToGroup.get(pixels[row + x] & 0xFFFFFF);
				int gi = g == null ? 0 : g;
				group[row + x] = gi;
				if (gi == 0)
					continue;
				int[] bb = bbox[gi];
				if (bb == null)
					bbox[gi] = new int[] { x, y, x, y };
				else {
					if (x < bb[0]) bb[0] = x;
					if (y < bb[1]) bb[1] = y;
					if (x > bb[2]) bb[2] = x;
					if (y > bb[3]) bb[3] = y;
				}
			}
		}

		// pass 2: trace each group's rings within its bounding box
		Map<String, Object> tierOut = new LinkedHashMap<>();
		for (int gi = 1; gi <= keys.size(); gi++) {
			int[] bb = bbox[gi];
			if (bb == null)
				continue;
			List<List<int[]>> rings = traceGroup(group, w, gi, bb, tier);
			if (!rings.isEmpty())
				tierOut.put(keys.get(gi - 1), rings);
		}
		return tierOut;
	}

	/**
	 * Frame a group's stamped pixels to a local mask, split into 8-connected components, and
	 * trace + simplify each into an absolute-pixel ring. (The province-tracer, generalized to
	 * match on a group index rather than a raw colour.)
	 */
	private static List<List<int[]>> traceGroup(int[] group, int imgW, int gi, int[] bb, Tier tier) {
		int minX = bb[0], minY = bb[1], lw = bb[2] - bb[0] + 1, lh = bb[3] - bb[1] + 1;
		int[] comp = new int[lw * lh];   // 0 = outside/unvisited, else component label
		boolean[] mask = new boolean[lw * lh];
		for (int ly = 0; ly < lh; ly++)
			for (int lx = 0; lx < lw; lx++)
				if (group[(minY + ly) * imgW + (minX + lx)] == gi)
					mask[ly * lw + lx] = true;

		List<List<int[]>> rings = new ArrayList<>();
		int label = 0;
		int[] dxs = { -1, 1, 0, 0, -1, -1, 1, 1 }, dys = { 0, 0, -1, 1, -1, 1, -1, 1 };
		Deque<int[]> stack = new ArrayDeque<>();
		for (int sy = 0; sy < lh; sy++) {
			for (int sx = 0; sx < lw; sx++) {
				if (!mask[sy * lw + sx] || comp[sy * lw + sx] != 0)
					continue;
				label++;
				int count = 0, seedX = sx, seedY = sy;
				stack.push(new int[] { sx, sy });
				comp[sy * lw + sx] = label;
				while (!stack.isEmpty()) {
					int[] c = stack.pop();
					count++;
					if (c[1] < seedY || (c[1] == seedY && c[0] < seedX)) {
						seedX = c[0];
						seedY = c[1];
					}
					for (int k = 0; k < 8; k++) {
						int nx = c[0] + dxs[k], ny = c[1] + dys[k];
						if (nx < 0 || nx >= lw || ny < 0 || ny >= lh)
							continue;
						if (mask[ny * lw + nx] && comp[ny * lw + nx] == 0) {
							comp[ny * lw + nx] = label;
							stack.push(new int[] { nx, ny });
						}
					}
				}
				if (count < tier.minComponentPx())
					continue;
				List<int[]> ring = mooreTrace(comp, lw, lh, seedX, seedY, label);
				if (ring.size() < 3)
					continue;
				ring = simplify(ring, tier.simplifyEps());
				List<int[]> abs = new ArrayList<>(ring.size());
				for (int[] pt : ring)
					abs.add(new int[] { pt[0] + minX, pt[1] + minY });
				rings.add(abs);
			}
		}
		return rings;
	}

	// ---- shared geometry (identical to ProvinceBorderExporter) ----

	private static final int[] MDX = { 0, 1, 1, 1, 0, -1, -1, -1 };
	private static final int[] MDY = { -1, -1, 0, 1, 1, 1, 0, -1 };

	private static List<int[]> mooreTrace(int[] comp, int lw, int lh, int startX, int startY, int label) {
		List<int[]> contour = new ArrayList<>();
		contour.add(new int[] { startX, startY });
		int px = startX, py = startY;
		int bIdx = 6;
		int guard = 0, maxSteps = 8 * lw * lh + 16;
		while (guard++ < maxSteps) {
			int found = -1;
			for (int k = 1; k <= 8; k++) {
				int idx = (bIdx + k) & 7;
				int nx = px + MDX[idx], ny = py + MDY[idx];
				if (nx >= 0 && nx < lw && ny >= 0 && ny < lh && comp[ny * lw + nx] == label) {
					found = idx;
					break;
				}
			}
			if (found == -1)
				break;
			int cx = px + MDX[found], cy = py + MDY[found];
			if (cx == startX && cy == startY)
				break;
			contour.add(new int[] { cx, cy });
			int prevIdx = (found + 7) & 7;
			int bx = px + MDX[prevIdx], by = py + MDY[prevIdx];
			px = cx;
			py = cy;
			bIdx = dirIndex(px, py, bx, by);
		}
		return contour;
	}

	private static int dirIndex(int px, int py, int bx, int by) {
		for (int i = 0; i < 8; i++)
			if (px + MDX[i] == bx && py + MDY[i] == by)
				return i;
		return 6;
	}

	private static List<int[]> simplify(List<int[]> ring, double eps) {
		int n = ring.size();
		if (n < 4)
			return ring;
		boolean[] keep = new boolean[n];
		keep[0] = true;
		keep[n - 1] = true;
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
				if (d > maxD) {
					maxD = d;
					far = i;
				}
			}
			if (far != -1 && maxD > eps2) {
				keep[far] = true;
				segs.push(new int[] { a, far });
				segs.push(new int[] { far, b });
			}
		}
		List<int[]> outp = new ArrayList<>();
		for (int i = 0; i < n; i++)
			if (keep[i])
				outp.add(ring.get(i));
		return outp;
	}

	private static double segDist2(int[] p, int[] a, int[] b) {
		double vx = b[0] - a[0], vy = b[1] - a[1];
		double wx = p[0] - a[0], wy = p[1] - a[1];
		double vv = vx * vx + vy * vy;
		double t = vv == 0 ? 0 : (wx * vx + wy * vy) / vv;
		t = Math.max(0, Math.min(1, t));
		double dx = wx - t * vx, dy = wy - t * vy;
		return dx * dx + dy * dy;
	}

	// ---- inputs ----

	private static String str(Object o) {
		return o == null ? null : String.valueOf(o);
	}

	private static Map<Integer, Integer> invertDefinitions() throws Exception {
		Map<Integer, Integer> idToColor = new HashMap<>();
		try (BufferedReader br = new BufferedReader(new InputStreamReader(
				Files.newInputStream(AnbennarFiles.get(DEFINITIONS)), StandardCharsets.UTF_8))) {
			br.readLine();
			String line;
			while ((line = br.readLine()) != null) {
				line = line.trim();
				if (line.isEmpty() || line.startsWith("#"))
					continue;
				String[] p = line.split(";", -1);
				if (p.length < 5)
					continue;
				try {
					int id = Integer.parseInt(p[0].trim());
					int r = Integer.parseInt(p[1].trim()), g = Integer.parseInt(p[2].trim()),
							b = Integer.parseInt(p[3].trim());
					String name = p[4].trim();
					if (name.startsWith("RNW") || name.startsWith("Unused"))
						continue;
					if (PLACEHOLDER_NAME.matcher(name.split("_#")[0].replace('_', ' ').trim()).matches())
						continue;
					idToColor.put(id, (r << 16) | (g << 8) | b);
				} catch (NumberFormatException ignored) {
					// malformed line — skip
				}
			}
		}
		return idToColor;
	}

	private static List<Map<String, Object>> loadProvinces() throws Exception {
		return new ObjectMapper().readValue(new File(PROVINCES_JSON),
				new TypeReference<List<Map<String, Object>>>() {
				});
	}

	// region key -> super-region key, from superregions.json ([{key, name, regions:[…]}])
	private static Map<String, String> loadRegionToSuper() throws Exception {
		List<Map<String, Object>> supers = new ObjectMapper().readValue(new File(SUPERREGIONS_JSON),
				new TypeReference<List<Map<String, Object>>>() {
				});
		Map<String, String> regionToSuper = new HashMap<>();
		for (Map<String, Object> s : supers) {
			String key = String.valueOf(s.get("key"));
			Object regions = s.get("regions");
			if (regions instanceof List<?> list)
				for (Object rk : list)
					regionToSuper.put(String.valueOf(rk), key);
		}
		return regionToSuper;
	}
}
