package com.civstudio.geo.export;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

import com.civstudio.geo.Bonus;
import com.civstudio.geo.BonusClass;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Dev tool: parses {@code data/CIV4BonusInfos.xml} and emits the bonus resources
 * to the committed {@code /bonuses.json} resource the core {@link
 * com.civstudio.geo.TerrainRegistry} loads. Unlike the terrain/feature/improvement
 * exporters (which curate a model-tied subset), the <b>full</b> bonus set is
 * exported — bonuses have no plot model yet, so subsetting is deferred. Mirrors
 * {@link ImprovementExporter}; run manually:
 *
 * <pre>
 * mvn compile exec:exec -Dsim.main=com.civstudio.geo.export.BonusExporter
 * </pre>
 *
 * Only economically-relevant fields are kept; the map-generator/AI internals
 * ({@code iPlacementOrder}, {@code Rands}, {@code iTilesPer}, {@code
 * iConstAppearance}, {@code iAITradeModifier}, art/sounds) are dropped. The bonus
 * classes themselves are a fixed taxonomy modeled as the {@link BonusClass} enum
 * (from {@code data/CIV4BonusClassInfos.xml}), not a separate resource. See {@code
 * docs/plots.md}.
 */
public final class BonusExporter {

	private static final String INPUT = "data/CIV4BonusInfos.xml";
	private static final String OUTPUT = "src/main/resources/bonuses.json";

	private BonusExporter() {
	}

	public static void main(String[] args) throws Exception {
		Document doc = Civ4Xml.parse(INPUT);
		List<Bonus> out = new ArrayList<>();
		Set<String> seen = new LinkedHashSet<>();
		for (Element info : Civ4Xml.infos(doc, "BonusInfo")) {
			String type = Civ4Xml.text(info, "Type");
			if (type == null || type.isEmpty())
				continue;
			if (!seen.add(type))
				throw new IllegalStateException("duplicate bonus type in XML: " + type);
			out.add(new Bonus(
					type,
					BonusClass.fromKey(Civ4Xml.text(info, "BonusClassType")),
					Civ4Xml.yields(info, "YieldChanges", "iYieldChange"),
					Civ4Xml.text(info, "TechReveal"),
					Civ4Xml.text(info, "TechCityTrade"),
					Civ4Xml.intVal(info, "iHealth", 0),
					Civ4Xml.intVal(info, "iHappiness", 0),
					Civ4Xml.intVal(info, "iMinLatitude", 0),
					Civ4Xml.intVal(info, "iMaxLatitude", 90),
					Civ4Xml.boolVal(info, "bHills"),
					Civ4Xml.boolVal(info, "bFlatlands"),
					Civ4Xml.boolVal(info, "bPeaks"),
					Civ4Xml.validTypes(info, "TerrainBooleans", "TerrainBoolean",
							"TerrainType", "bTerrain"),
					Civ4Xml.validTypes(info, "FeatureBooleans", "FeatureBoolean",
							"FeatureType", "bFeature"),
					Civ4Xml.validTypes(info, "FeatureTerrainBooleans", "FeatureTerrainBoolean",
							"TerrainType", "bFeatureTerrain")));
		}
		if (out.isEmpty())
			throw new IllegalStateException("no bonuses found in " + INPUT);

		File f = new File(OUTPUT);
		new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(f, out);
		System.out.println("wrote " + out.size() + " bonuses to " + f.getAbsolutePath());
	}
}
