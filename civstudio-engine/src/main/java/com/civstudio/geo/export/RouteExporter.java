package com.civstudio.geo.export;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

import com.civstudio.geo.RouteType;
import tools.jackson.databind.ObjectMapper;

/**
 * Dev tool: parses C2C's {@code CIV4RouteInfos.xml} and emits the road-tier definitions to the
 * committed {@code /routes.json} resource the core {@link com.civstudio.geo.TerrainRegistry}
 * loads. Sibling of {@link TerrainExporter} / {@link FeatureExporter}; the running simulation
 * never touches the XML. Run manually:
 *
 * <pre>
 * mvn -pl civstudio-engine compile exec:exec -Dsim.main=com.civstudio.geo.export.RouteExporter
 * </pre>
 *
 * <b>No curation</b> — all C2C routes are kept ({@code TRAIL → PATH → ROAD → PAVED_ROAD →
 * RAILROAD → …} up to {@code JUMPLANE}, plus the sea {@code TUNNEL}). The tiers past the
 * Renaissance tech cap (highway, maglev, vactrain, gravity/jumplane) are imported but sit
 * dormant beyond the horizon; keeping the whole ladder costs nothing (a dozen small records) and
 * keeps the {@code iValue} ranks contiguous. See {@code docs/explorer-caravan.md} §Phase 3.
 */
public final class RouteExporter {

	private static final String INPUT = "CIV4RouteInfos.xml";
	private static final String OUTPUT = "civstudio-engine/src/main/resources/generated/routes.json";

	private RouteExporter() {
	}

	public static void main(String[] args) throws Exception {
		Document doc = Civ4Xml.fetch(INPUT);
		List<RouteType> out = new ArrayList<>();
		for (Element info : Civ4Xml.infos(doc, "RouteInfo")) {
			String type = Civ4Xml.text(info, "Type");
			if (type == null || type.isEmpty())
				continue;
			String bonus = Civ4Xml.text(info, "BonusType");
			if (bonus != null && (bonus.isEmpty() || "NONE".equals(bonus)))
				bonus = null;
			out.add(new RouteType(
					type,
					Civ4Xml.intVal(info, "iValue", 0),
					Civ4Xml.intVal(info, "iMovement", 0),
					Civ4Xml.intVal(info, "iFlatMovement", 0),
					Civ4Xml.intVal(info, "iAdvancedStartCost", 0),
					bonus,
					Civ4Xml.boolVal(info, "bSeaTunnel"),
					Civ4Xml.yields(info, "Yields", "iYield")));
		}
		if (out.isEmpty())
			throw new IllegalStateException("no routes parsed from " + INPUT);

		File f = new File(OUTPUT);
		new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(f, out);
		System.out.println("wrote " + out.size() + " routes to " + f.getAbsolutePath());
	}
}
