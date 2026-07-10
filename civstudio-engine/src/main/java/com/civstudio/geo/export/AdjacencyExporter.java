package com.civstudio.geo.export;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.civstudio.geo.Adjacency;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Dev tool: parses the Anbennar EU4 {@code data/anbennar/adjacencies.csv} (canonical
 * source {@code gitlab.com/anbennar/anbennar-eu4-dev}) and emits the special province
 * connections — sea straits, canals, lake crossings, and the Dwarovar tunnels/passes — to
 * the committed {@code /map/adjacencies.json} resource. These connect provinces that are
 * <b>not</b> visually adjacent, so they are absent from the raster {@link
 * com.civstudio.geo.Province#neighbors()} pixel adjacency; {@link
 * com.civstudio.geo.WorldMap} merges them into the routing graph and the web viewer draws
 * them as red dotted lines. See {@code docs/land-routing.md}.
 * <p>
 * The CSV is {@code From;To;Type;Through;start_x;start_y;stop_x;stop_y;Comment}; only the
 * endpoints, type and comment are kept (the crossing pixel coords are unused — the map draws
 * centroid-to-centroid). Rows whose endpoints are not both provinces in {@code
 * provinces.json} are dropped (they reference off-map / Random-New-World ids). Run:
 *
 * <pre>
 * mvn compile exec:exec -Dsim.main=com.civstudio.geo.export.AdjacencyExporter
 * </pre>
 */
public final class AdjacencyExporter {

	private static final String INPUT = "data/anbennar/adjacencies.csv";
	private static final String PROVINCES = "src/main/resources/map/provinces.json";
	private static final String OUTPUT = "src/main/resources/map/adjacencies.json";

	// beyond this great-circle km an entry is a leftover vanilla EU4 canal (kiel/panama/suez, …)
	// onto a mismatched Anbennar province — a map-spanning / wrap-around artifact — and is dropped
	// entirely. Genuinely long-but-plausible connections are kept (the web renders those beyond a
	// shorter line threshold as "teleporter" endpoint markers rather than a map-crossing line).
	private static final double MAX_KM = 4000.0;

	private AdjacencyExporter() {
	}

	public static void main(String[] args) throws Exception {
		ObjectMapper mapper = new ObjectMapper();

		// the set of province ids that exist in the map (endpoints must resolve to these)
		List<Map<String, Object>> rows = mapper.readValue(new File(PROVINCES),
				new TypeReference<List<Map<String, Object>>>() {
				});
		Set<Integer> known = new LinkedHashSet<>();
		Map<Integer, double[]> latLon = new java.util.HashMap<>();
		for (Map<String, Object> r : rows) {
			int id = ((Number) r.get("id")).intValue();
			known.add(id);
			latLon.put(id, new double[] { ((Number) r.get("lat")).doubleValue(),
					((Number) r.get("lon")).doubleValue() });
		}

		List<String> lines = Files.readAllLines(new File(INPUT).toPath());
		List<Adjacency> out = new ArrayList<>();
		Set<Long> seen = new LinkedHashSet<>();   // dedup undirected pairs
		int dropped = 0, farDropped = 0;
		for (int i = 1; i < lines.size(); i++) { // skip the header
			String line = lines.get(i).trim();
			if (line.isEmpty() || line.startsWith("#") || line.startsWith("-1"))
				continue;
			String[] f = line.split(";", -1);
			if (f.length < 3 || !f[0].matches("-?\\d+") || !f[1].matches("-?\\d+"))
				continue;
			int from = Integer.parseInt(f[0]);
			int to = Integer.parseInt(f[1]);
			if (from < 0 || to < 0 || !known.contains(from) || !known.contains(to)) {
				dropped++;
				continue;
			}
			// drop implausibly long connections — leftover vanilla EU4 canals (kiel/panama/suez, …)
			// map onto mismatched Anbennar provinces on opposite sides of the map. A real strait /
			// canal / tunnel is local; these would draw map-spanning lines and be wormhole shortcuts
			// in the routing graph.
			if (greatCircleKm(latLon.get(from), latLon.get(to)) > MAX_KM) {
				farDropped++;
				continue;
			}
			long key = ((long) Math.min(from, to) << 32) | (Math.max(from, to) & 0xffffffffL);
			if (!seen.add(key))
				continue;
			String type = f[2].trim();
			String comment = f.length >= 9 ? f[8].trim() : "";
			out.add(new Adjacency(from, to, type, comment));
		}

		new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(new File(OUTPUT), out);
		System.out.println("wrote " + out.size() + " adjacencies (dropped " + dropped
				+ " off-map, " + farDropped + " too far) to " + new File(OUTPUT).getAbsolutePath());
	}

	// great-circle distance (km) between two [lat, lon] points; MAX_VALUE if either is missing
	private static double greatCircleKm(double[] a, double[] b) {
		if (a == null || b == null)
			return Double.MAX_VALUE;
		double la1 = Math.toRadians(a[0]), la2 = Math.toRadians(b[0]);
		double dLa = la2 - la1, dLo = Math.toRadians(b[1] - a[1]);
		double h = Math.sin(dLa / 2) * Math.sin(dLa / 2)
				+ Math.cos(la1) * Math.cos(la2) * Math.sin(dLo / 2) * Math.sin(dLo / 2);
		return 2 * 6371.0 * Math.asin(Math.min(1.0, Math.sqrt(h)));
	}
}
