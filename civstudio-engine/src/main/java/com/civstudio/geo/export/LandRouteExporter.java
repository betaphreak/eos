package com.civstudio.geo.export;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import com.civstudio.geo.Province;
import com.civstudio.geo.ProvinceEdges;
import com.civstudio.geo.WorldMap;
import tools.jackson.databind.ObjectMapper;

/**
 * Dev tool: precomputes the province graph's <b>edge weights</b> — the travel km of
 * every province&rarr;neighbour edge — and writes them to the {@code /map/edges.json}
 * resource the core {@link WorldMap} loads (see {@code docs/land-routing.md}, Level 1).
 * Like the other {@code geo.export} tools this is a build-time/manual step whose output
 * is committed, so the running simulation reads a plain JSON table rather than
 * recomputing geometry.
 * <p>
 * This cut uses the <b>centroid-to-centroid great-circle</b> distance (the same {@link
 * WorldMap#distanceKm(int, int)} the runtime exposes). Committing it is the seam by
 * which a later revision can substitute a <em>border-aware</em> weight
 * (centroid&rarr;shared-border-midpoint&rarr;centroid, from the province raster) with
 * <b>no change</b> to {@link com.civstudio.geo.LandRouter} — it reads {@link
 * WorldMap#edgeKm(int, int)}, which prefers this table. Each record's {@code km[]} is
 * aligned to its province's {@link Province#neighbors()} order.
 * <p>
 * Run via:
 *
 * <pre>
 * mvn compile exec:exec -Dsim.main=com.civstudio.geo.export.LandRouteExporter
 * </pre>
 */
public final class LandRouteExporter {

	private static final String OUTPUT = "civstudio-engine/src/main/resources/generated/map/edges.json";

	private LandRouteExporter() {
	}

	public static void main(String[] args) throws Exception {
		// loads without /map/edges.json (WorldMap treats it as optional), so this
		// bootstraps cleanly on the first run
		WorldMap map = WorldMap.load();
		List<ProvinceEdges> edges = new ArrayList<>(map.size());
		long edgeCount = 0;
		for (Province p : map.provinces()) {
			List<Integer> nb = p.neighbors();
			double[] km = new double[nb.size()];
			for (int i = 0; i < km.length; i++)
				km[i] = round2(map.distanceKm(p.id(), nb.get(i)));
			edges.add(new ProvinceEdges(p.id(), km));
			edgeCount += km.length;
		}

		ObjectMapper mapper = new ObjectMapper();
		File out = new File(OUTPUT);
		mapper.writerWithDefaultPrettyPrinter().writeValue(out, edges);
		System.out.println("wrote edge weights for " + edges.size() + " provinces ("
				+ edgeCount + " directed edges) to " + out.getAbsolutePath());
	}

	// two-decimal km, to keep the committed file compact and diff-stable
	private static double round2(double km) {
		return Math.round(km * 100.0) / 100.0;
	}
}
