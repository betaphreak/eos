package com.civstudio.geo.export;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

import com.civstudio.geo.Feature;
import tools.jackson.databind.ObjectMapper;

/**
 * Dev tool: parses {@code data/civ4/CIV4FeatureInfos.xml} and emits the curated
 * land-feature subset to the committed {@code /features.json} resource the core
 * {@link com.civstudio.geo.TerrainRegistry} loads. Mirrors {@link
 * TerrainExporter}; run manually:
 *
 * <pre>
 * mvn compile exec:exec -Dsim.main=com.civstudio.geo.export.FeatureExporter
 * </pre>
 *
 * Impassable / named-wonder / rock-formation / ruins / pollution / water-reef
 * features are dropped (see {@code docs/plots.md}).
 */
public final class FeatureExporter {

	private static final String INPUT = "CIV4FeatureInfos.xml";
	private static final String OUTPUT = "src/main/resources/features.json";

	/**
	 * The curated feature subset, in {@code docs/plots.md} order: the land features, plus
	 * {@code FEATURE_ICE} — the one water feature, placed on polar coastal-shelf water
	 * (its {@code validTerrains} are the polar sea/coast terrains). See {@code docs/coastlines.md}.
	 */
	private static final Set<String> KEEP = new LinkedHashSet<>(List.of(
			"FEATURE_FOREST", "FEATURE_FOREST_ANCIENT", "FEATURE_JUNGLE",
			"FEATURE_BAMBOO", "FEATURE_SAVANNA", "FEATURE_VERY_TALL_GRASS",
			"FEATURE_CACTUS", "FEATURE_OASIS", "FEATURE_FLOOD_PLAINS",
			"FEATURE_SWAMP", "FEATURE_ICE"));

	/**
	 * Extra host terrains appended to a feature's {@code validTerrains}. The authored,
	 * source-less Anbennar terrains (see {@link TerrainExporter#SYNTHETIC}) have no XML
	 * feature bindings, so without this trees/swamps would be rejected on them by the
	 * {@code ProvincePlotField} validity gate — a bloodgrove would be bare. The
	 * forest-family terrains host the forest features; the shadow swamp hosts the swamp.
	 * See {@code docs/underworld.md}.
	 */
	private static final Map<String, List<String>> EXTRA_HOSTS = Map.of(
			"FEATURE_FOREST", List.of("TERRAIN_ANCIENT_FOREST", "TERRAIN_GLADEWAY",
					"TERRAIN_FEY_GLADEWAY", "TERRAIN_BLOODGROVES"),
			"FEATURE_FOREST_ANCIENT", List.of("TERRAIN_ANCIENT_FOREST", "TERRAIN_GLADEWAY",
					"TERRAIN_FEY_GLADEWAY", "TERRAIN_BLOODGROVES"),
			"FEATURE_SWAMP", List.of("TERRAIN_SHADOW_SWAMP"));

	private FeatureExporter() {
	}

	public static void main(String[] args) throws Exception {
		Document doc = Civ4Xml.fetch(INPUT);
		List<Feature> out = new ArrayList<>();
		Set<String> seen = new LinkedHashSet<>();
		for (Element info : Civ4Xml.infos(doc, "FeatureInfo")) {
			String type = Civ4Xml.text(info, "Type");
			if (type == null || !KEEP.contains(type))
				continue;
			// the XML host terrains, plus any authored Anbennar terrains this feature hosts
			List<String> validTerrains = new ArrayList<>(Civ4Xml.validTypes(info,
					"TerrainBooleans", "TerrainBoolean", "TerrainType", "bTerrain"));
			validTerrains.addAll(EXTRA_HOSTS.getOrDefault(type, List.of()));
			out.add(new Feature(
					type,
					Civ4Xml.yields(info, "YieldChanges", "iYieldChange"),
					Civ4Xml.intVal(info, "iAdvancedStartRemoveCost", 0),
					Civ4Xml.boolVal(info, "bRequiresFlatlands"),
					Civ4Xml.boolVal(info, "bRequiresRiver"),
					validTerrains,
					Civ4Xml.intVal(info, "iHealthPercent", 0),
					Civ4Xml.intVal(info, "iGrowth", 0),
					Civ4Xml.intVal(info, "iMovement", 0),
					Civ4Xml.intVal(info, "iAppearance", 0)));
			seen.add(type);
		}
		Set<String> missing = new LinkedHashSet<>(KEEP);
		missing.removeAll(seen);
		if (!missing.isEmpty())
			throw new IllegalStateException("curated features not found in XML: " + missing);

		File f = new File(OUTPUT);
		new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(f, out);
		System.out.println("wrote " + out.size() + " features to " + f.getAbsolutePath());
	}
}
