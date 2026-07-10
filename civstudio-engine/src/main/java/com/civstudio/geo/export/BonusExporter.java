package com.civstudio.geo.export;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

import com.civstudio.geo.Bonus;
import com.civstudio.geo.BonusClass;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Dev tool: parses {@code data/civ4/CIV4BonusInfos.xml} and emits the bonus resources
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
 * The economically-relevant fields plus the <b>map-placement</b> constraints
 * ({@code iPlacementOrder}, {@code Rands}, {@code iTilesPer}, {@code
 * iConstAppearance}, {@code iMinAreaSize}, {@code iGroupRange}/{@code iGroupRand}) that
 * {@link com.civstudio.geo.BonusGenerator} places by are kept; the AI internals
 * ({@code iAITradeModifier}, art/sounds) are dropped. The bonus
 * classes themselves are a fixed taxonomy modeled as the {@link BonusClass} enum
 * (from {@code data/civ4/CIV4BonusClassInfos.xml}), not a separate resource. See {@code
 * docs/plots.md}.
 */
public final class BonusExporter {

	private static final String INPUT = "data/civ4/CIV4BonusInfos.xml";
	private static final String OUTPUT = "src/main/resources/bonuses.json";
	private static final String TECHS = "src/main/resources/techs.json";

	// C2C era key → ordinal (later = higher); the exported tree stops at industrial, so a reveal
	// tech absent from it is a modern/future tech (Bonus.ERA_MODERN).
	private static final Map<String, Integer> ERA_ORDINAL = Map.of(
			"C2C_ERA_PREHISTORIC", 0, "C2C_ERA_ANCIENT", 1, "C2C_ERA_CLASSICAL", 2,
			"C2C_ERA_MEDIEVAL", 3, "C2C_ERA_RENAISSANCE", 4, "C2C_ERA_INDUSTRIAL", 5);

	private BonusExporter() {
	}

	public static void main(String[] args) throws Exception {
		Document doc = Civ4Xml.parse(INPUT);
		// tech Type → era ordinal, so each bonus can carry its reveal tech's era
		Map<String, Integer> techEras = new HashMap<>();
		for (Map<String, Object> t : new ObjectMapper().readValue(new File(TECHS),
				new TypeReference<List<Map<String, Object>>>() {
				}))
			techEras.put((String) t.get("Type"), ERA_ORDINAL.getOrDefault(t.get("Era"), Bonus.ERA_MODERN));
		List<Bonus> out = new ArrayList<>();
		Set<String> seen = new LinkedHashSet<>();
		for (Element info : Civ4Xml.infos(doc, "BonusInfo")) {
			String type = Civ4Xml.text(info, "Type");
			if (type == null || type.isEmpty())
				continue;
			if (!seen.add(type))
				throw new IllegalStateException("duplicate bonus type in XML: " + type);
			// the four appearance rands live in a nested <Rands> element
			Element rands = Civ4Xml.child(info, "Rands");
			int[] randApps = {
					rands == null ? 0 : Civ4Xml.intVal(rands, "iRandApp1", 0),
					rands == null ? 0 : Civ4Xml.intVal(rands, "iRandApp2", 0),
					rands == null ? 0 : Civ4Xml.intVal(rands, "iRandApp3", 0),
					rands == null ? 0 : Civ4Xml.intVal(rands, "iRandApp4", 0) };
			// the reveal tech's era (no tech → prehistoric/none; tech beyond the tree → modern)
			String techReveal = Civ4Xml.text(info, "TechReveal");
			int techEra = (techReveal == null || techReveal.isEmpty()) ? Bonus.ERA_NONE
					: techEras.getOrDefault(techReveal, Bonus.ERA_MODERN);
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
							"TerrainType", "bFeatureTerrain"),
					Civ4Xml.intVal(info, "iPlacementOrder", 0),
					Civ4Xml.intVal(info, "iConstAppearance", 0),
					randApps,
					Civ4Xml.intVal(info, "iTilesPer", 0),
					Civ4Xml.intVal(info, "iMinAreaSize", 0),
					Civ4Xml.intVal(info, "iGroupRange", 0),
					Civ4Xml.intVal(info, "iGroupRand", 0),
					techEra));
		}
		if (out.isEmpty())
			throw new IllegalStateException("no bonuses found in " + INPUT);

		File f = new File(OUTPUT);
		new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(f, out);
		System.out.println("wrote " + out.size() + " bonuses to " + f.getAbsolutePath());
	}
}
