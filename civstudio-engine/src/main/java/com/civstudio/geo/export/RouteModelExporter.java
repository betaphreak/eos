package com.civstudio.geo.export;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

import com.civstudio.geo.RouteModelInfo;
import tools.jackson.databind.ObjectMapper;

/**
 * Dev tool: parses {@code data/civ4/CIV4RouteModelInfos.xml} (the {@code RouteModelInfo}
 * art bindings for roads / rails / paths — Civ4's route auto-tiling table) and emits the
 * curated, era-appropriate subset to the committed {@code /map/route-models.json}
 * resource, keyed by {@code ROUTE_*} type. Sibling of {@link TerrainArtExporter}; the
 * running simulation never touches the XML. Run manually, like the other geo exporters:
 *
 * <pre>
 * mvn compile exec:exec -Dsim.main=com.civstudio.geo.export.RouteModelExporter
 * </pre>
 *
 * This is the <b>routes</b> feature (roads/rails), the natural one after rivers (see
 * {@code docs/ported-terrain-art-system.md} §6). It is <b>not</b> Phase 3 of the river
 * work — Civ4 binds no river art in XML, so rivers have no exporter (see {@code
 * docs/river-rendering.md} §4). The route {@code ModelFile}s are 3D {@code .nif}; the web
 * client still needs the offline {@code .nif}→sprite bake (§10/§11) before it can draw
 * them — this exporter only emits the binding manifest.
 */
public final class RouteModelExporter {

	private static final String INPUT = "CIV4RouteModelInfos.xml";
	private static final String OUTPUT = "civstudio-engine/target/generated/map/route-models.json";

	/**
	 * The curated route subset — the pre-modern types that fit the game's era; the far-future
	 * C2C tiers (highway, maglev, vactrain, gravity/electric rail, jumplane, tunnel) are
	 * skipped. Widen this set when a later era or feature needs them.
	 */
	private static final Set<String> KEEP = new LinkedHashSet<>(List.of(
			"ROUTE_TRAIL", "ROUTE_PATH", "ROUTE_ROAD", "ROUTE_PAVED_ROAD", "ROUTE_RAILROAD"));

	private RouteModelExporter() {
	}

	public static void main(String[] args) throws Exception {
		Document doc = Civ4Xml.fetch(INPUT);
		List<RouteModelInfo> out = new ArrayList<>();
		for (Element e : Civ4Xml.infos(doc, "RouteModelInfo")) {
			String routeType = Civ4Xml.text(e, "RouteType");
			if (routeType == null || !KEEP.contains(routeType))
				continue;
			out.add(new RouteModelInfo(
					routeType,
					Civ4Xml.text(e, "ModelFileKey"),
					Civ4Xml.text(e, "ModelFile"),
					Civ4Xml.text(e, "LateModelFile"),
					Civ4Xml.boolVal(e, "Animated"),
					Civ4Xml.text(e, "Connections"),
					Civ4Xml.text(e, "ModelConnections"),
					rotations(Civ4Xml.text(e, "Rotations"))));
		}
		if (out.isEmpty())
			throw new IllegalStateException("no RouteModelInfo entries matched " + KEEP + " in " + INPUT);

		File f = new File(OUTPUT);
		f.getParentFile().mkdirs();
		new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(f, out);
		System.out.println("wrote " + out.size() + " route-model entries (" + KEEP.size()
				+ " curated route types) to " + f.getAbsolutePath());
	}

	// parse a "0 90 180 270" rotation list into the yaw angles; empty/absent -> {0}
	private static int[] rotations(String raw) {
		if (raw == null || raw.isBlank())
			return new int[] { 0 };
		String[] parts = raw.trim().split("\\s+");
		int[] r = new int[parts.length];
		for (int i = 0; i < parts.length; i++)
			r[i] = Integer.parseInt(parts[i]);
		return r;
	}
}
