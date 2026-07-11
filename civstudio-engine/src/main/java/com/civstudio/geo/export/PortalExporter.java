package com.civstudio.geo.export;

import com.civstudio.data.AnbennarFiles;

import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import javax.imageio.ImageIO;

import com.civstudio.geo.ProvincePortals;
import com.civstudio.geo.ProvincePortals.Portal;
import tools.jackson.databind.ObjectMapper;

/**
 * Dev tool: precomputes the province graph's <b>border portals</b> — for each ordered
 * neighbour pair {@code (P, Q)}, the midpoint pixel on {@code P}'s side of their shared
 * border — and writes them to the {@code /map/portals.json} resource {@link
 * com.civstudio.geo.WorldMap} loads (Level 2 of {@code docs/land-routing.md}). Static
 * geography (seed-independent), so it is committed rather than recomputed per run; the
 * per-province plot <b>corridor</b> a caravan crosses enters at the {@code (prev, P)}
 * portal and leaves at the {@code (P, next)} portal.
 * <p>
 * One pass over {@code provinces.bmp}: a pixel of province {@code P} whose 4-neighbour is a
 * different known province {@code Q} is a {@code P&rarr;Q} border pixel; the emitted portal
 * is the average (rounded) of those pixels — an anchor the corridor router snaps to the
 * nearest plot. Run via:
 *
 * <pre>
 * mvn compile exec:exec -Dsim.main=com.civstudio.geo.export.PortalExporter
 * </pre>
 */
public final class PortalExporter {

	private static final String DEFINITIONS = "map/definition.csv";
	private static final String PROVINCES_BMP = "map/provinces.bmp";
	private static final String OUTPUT = "civstudio-engine/src/main/resources/generated/map/portals.json";

	private PortalExporter() {
	}

	public static void main(String[] args) throws Exception {
		Map<Integer, Integer> colorToId = loadColorToId();

		BufferedImage img = ImageIO.read(AnbennarFiles.get(PROVINCES_BMP).toFile());
		int w = img.getWidth(), h = img.getHeight();
		int[] pixels = img.getRGB(0, 0, w, h, null, 0, w);
		img = null;

		// directed edge (P,Q) -> [sumX, sumY, count] over P's border pixels touching Q
		Map<Long, long[]> acc = new HashMap<>();
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				Integer p = colorToId.get(pixels[y * w + x] & 0xFFFFFF);
				if (p == null)
					continue;
				accumulate(acc, p, neighbourId(colorToId, pixels, w, h, x + 1, y), x, y);
				accumulate(acc, p, neighbourId(colorToId, pixels, w, h, x - 1, y), x, y);
				accumulate(acc, p, neighbourId(colorToId, pixels, w, h, x, y + 1), x, y);
				accumulate(acc, p, neighbourId(colorToId, pixels, w, h, x, y - 1), x, y);
			}
		}

		// group by province P, one Portal per neighbour Q (border midpoint)
		Map<Integer, List<Portal>> byProvince = new TreeMap<>();
		for (Map.Entry<Long, long[]> e : acc.entrySet()) {
			int from = (int) (e.getKey() >> 32);
			int to = (int) (e.getKey() & 0xFFFFFFFFL);
			long[] a = e.getValue();
			int mx = (int) Math.round(a[0] / (double) a[2]);
			int my = (int) Math.round(a[1] / (double) a[2]);
			byProvince.computeIfAbsent(from, k -> new ArrayList<>()).add(new Portal(to, mx, my));
		}

		List<ProvincePortals> out = new ArrayList<>(byProvince.size());
		long portalCount = 0;
		for (Map.Entry<Integer, List<Portal>> e : byProvince.entrySet()) {
			List<Portal> ps = e.getValue();
			ps.sort((x, y) -> Integer.compare(x.to(), y.to()));
			out.add(new ProvincePortals(e.getKey(), ps));
			portalCount += ps.size();
		}

		ObjectMapper mapper = new ObjectMapper();
		File file = new File(OUTPUT);
		mapper.writerWithDefaultPrettyPrinter().writeValue(file, out);
		System.out.println("wrote " + portalCount + " portals over " + out.size()
				+ " provinces to " + file.getAbsolutePath());
	}

	// the province id at (x,y), or null if out of bounds or not a known province colour
	private static Integer neighbourId(Map<Integer, Integer> colorToId, int[] pixels,
			int w, int h, int x, int y) {
		if (x < 0 || x >= w || y < 0 || y >= h)
			return null;
		return colorToId.get(pixels[y * w + x] & 0xFFFFFF);
	}

	// record (x,y) as a border pixel of P touching Q, when Q is a different known province
	private static void accumulate(Map<Long, long[]> acc, int p, Integer q, int x, int y) {
		if (q == null || q.intValue() == p)
			return;
		long key = ((long) p << 32) | (q & 0xFFFFFFFFL);
		long[] a = acc.computeIfAbsent(key, k -> new long[3]);
		a[0] += x;
		a[1] += y;
		a[2]++;
	}

	// colour -> province id from definition.csv (skipping RNW/Unused placeholder rows)
	private static Map<Integer, Integer> loadColorToId() throws Exception {
		Map<Integer, Integer> map = new HashMap<>();
		try (BufferedReader br = new BufferedReader(new InputStreamReader(
				Files.newInputStream(AnbennarFiles.get(DEFINITIONS)), StandardCharsets.UTF_8))) {
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
					String rawName = parts[4].trim();
					if (rawName.startsWith("RNW") || rawName.startsWith("Unused"))
						continue;
					map.put((r << 16) | (g << 8) | b, id);
				} catch (NumberFormatException ignored) {
					// malformed row — skip
				}
			}
		}
		return map;
	}
}
