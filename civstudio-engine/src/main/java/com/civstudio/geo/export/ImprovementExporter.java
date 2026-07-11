package com.civstudio.geo.export;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

import com.civstudio.geo.Improvement;
import tools.jackson.databind.ObjectMapper;

/**
 * Dev tool: parses {@code data/civ4/CIV4ImprovementInfos.xml} and emits the curated
 * firm-building subset to the committed {@code /improvements.json} resource the
 * core {@link com.civstudio.geo.TerrainRegistry} loads. Each kept improvement is
 * the building of one on-plot firm type (a {@code FARM} for necessity, a
 * {@code CAMP} for the forage firm, …). Mirrors {@link TerrainExporter}; run
 * manually:
 *
 * <pre>
 * mvn compile exec:exec -Dsim.main=com.civstudio.geo.export.ImprovementExporter
 * </pre>
 *
 * Only economically-relevant fields are kept; the top-level {@code <YieldChanges>}
 * is read (per-bonus {@code <BonusTypeStruct>} yields are scoped out by the
 * direct-child helper). See {@code docs/plots.md}.
 */
public final class ImprovementExporter {

	private static final String INPUT = "CIV4ImprovementInfos.xml";
	private static final String OUTPUT = "src/main/resources/improvements.json";

	/** The curated firm-building subset, in {@code docs/plots.md} order. */
	private static final Set<String> KEEP = new LinkedHashSet<>(List.of(
			"IMPROVEMENT_FARM", "IMPROVEMENT_MINE", "IMPROVEMENT_QUARRY",
			"IMPROVEMENT_LUMBERMILL", "IMPROVEMENT_PASTURE", "IMPROVEMENT_WINERY",
			"IMPROVEMENT_PLANTATION", "IMPROVEMENT_COTTAGE", "IMPROVEMENT_HAMLET",
			"IMPROVEMENT_VILLAGE", "IMPROVEMENT_TOWN", "IMPROVEMENT_HUNTING_CAMP"));

	private ImprovementExporter() {
	}

	public static void main(String[] args) throws Exception {
		Document doc = Civ4Xml.fetch(INPUT);
		List<Improvement> out = new ArrayList<>();
		Set<String> seen = new LinkedHashSet<>();
		for (Element info : Civ4Xml.infos(doc, "ImprovementInfo")) {
			String type = Civ4Xml.text(info, "Type");
			if (type == null || !KEEP.contains(type))
				continue;
			out.add(new Improvement(
					type,
					Civ4Xml.yields(info, "YieldChanges", "iYieldChange"),
					Civ4Xml.text(info, "PrereqTech"),
					Civ4Xml.boolVal(info, "bHillsMakesValid"),
					Civ4Xml.boolVal(info, "bFreshWaterMakesValid"),
					Civ4Xml.validTypes(info, "TerrainMakesValids", "TerrainMakesValid",
							"TerrainType", "bMakesValid"),
					Civ4Xml.validTypes(info, "FeatureMakesValids", "FeatureMakesValid",
							"FeatureType", "bMakesValid"),
					Civ4Xml.intVal(info, "iAdvancedStartCost", 0),
					Civ4Xml.intVal(info, "iHealthPercent", 0)));
			seen.add(type);
		}
		Set<String> missing = new LinkedHashSet<>(KEEP);
		missing.removeAll(seen);
		if (!missing.isEmpty())
			throw new IllegalStateException("curated improvements not found in XML: " + missing);

		File f = new File(OUTPUT);
		new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(f, out);
		System.out.println("wrote " + out.size() + " improvements to " + f.getAbsolutePath());
	}
}
