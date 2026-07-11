package com.civstudio.geo.export;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

import com.civstudio.good.Recipe;
import com.civstudio.good.TierOneSource;
import tools.jackson.databind.ObjectMapper;

/**
 * Dev tool: builds the <b>manufactured producer/recipe graph</b> of
 * {@code docs/manufactured-bonuses.md} (step 1 of its plan) from the committed C2C
 * sources, emitting two resources:
 * <ul>
 * <li>{@code /recipes.json} — the processing tier ({@link Recipe}): every building in
 * {@code data/civ4/Regular_CIV4BuildingInfos.xml} (2403) whose
 * {@code <ExtraFreeBonuses>} output is in the manufactured catalog
 * ({@code Manufactured_CIV4BonusInfos.xml}), with its input legs
 * ({@code <Bonus>} primary + {@code <PrereqBonuses>} secondary +
 * {@code <PrereqVicinityBonuses>}/{@code <PrereqRawVicinityBonuses>} land-sourced),
 * tech gate and building prereqs — the M7 multi-tier chain.</li>
 * <li>{@code /tier1-providers.json} — the extraction tier ({@link TierOneSource}):
 * the 48 {@code BUILDING_RESOURCES_*} providers of
 * {@code data/civ4/zProviders_CIV4BuildingInfos.xml}, each granting one extracted
 * good when any of its tile <b>gatherer</b> buildings exists; each gatherer's
 * terrain/feature/vicinity-bonus prereqs (looked up in the Regular file and inlined)
 * are the good's <b>raw plot source</b> — the M29 tier-1 → plot mapping, where the
 * chain terminates in land (M16).</li>
 * </ul>
 * A provider gatherer not defined in the Regular file (a handful live in other C2C
 * modules that are not committed) is skipped with a note — the committed gatherers
 * are ample coverage per good. Mirrors the other Civ4 exporters; run manually:
 *
 * <pre>
 * mvn compile exec:exec -Dsim.main=com.civstudio.geo.export.RecipeExporter
 * </pre>
 */
public final class RecipeExporter {

	private static final String CATALOG_INPUT = "Manufactured_CIV4BonusInfos.xml";
	private static final String REGULAR_INPUT = "Regular_CIV4BuildingInfos.xml";
	private static final String PROVIDERS_INPUT = "zProviders_CIV4BuildingInfos.xml";
	private static final String RECIPES_OUTPUT = "src/main/resources/recipes.json";
	private static final String TIER1_OUTPUT = "src/main/resources/tier1-providers.json";

	private RecipeExporter() {
	}

	public static void main(String[] args) throws Exception {
		Set<String> catalog = manufacturedTypes();
		Map<String, Element> buildings = buildingIndex();

		List<Recipe> recipes = exportRecipes(catalog, buildings);
		File recipesFile = new File(RECIPES_OUTPUT);
		new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(recipesFile, recipes);
		System.out.println("wrote " + recipes.size() + " recipes to "
				+ recipesFile.getAbsolutePath());

		List<TierOneSource> tier1 = exportTierOne(buildings);
		File tier1File = new File(TIER1_OUTPUT);
		new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(tier1File, tier1);
		System.out.println("wrote " + tier1.size() + " tier-1 providers to "
				+ tier1File.getAbsolutePath());
	}

	/** The manufactured-catalog bonus keys — the output filter for the recipe tier. */
	private static Set<String> manufacturedTypes() {
		Set<String> types = new LinkedHashSet<>();
		for (Element info : Civ4Xml.infos(Civ4Xml.fetch(CATALOG_INPUT), "BonusInfo")) {
			String type = Civ4Xml.text(info, "Type");
			if (type != null && !type.isEmpty())
				types.add(type);
		}
		if (types.isEmpty())
			throw new IllegalStateException("no bonuses found in " + CATALOG_INPUT);
		return types;
	}

	/** All Regular buildings keyed by type, in file order (gatherer lookups + scan). */
	private static Map<String, Element> buildingIndex() {
		Map<String, Element> byType = new LinkedHashMap<>();
		for (Element info : Civ4Xml.infos(Civ4Xml.fetch(REGULAR_INPUT), "BuildingInfo")) {
			String type = Civ4Xml.text(info, "Type");
			if (type == null || type.isEmpty())
				continue;
			if (byType.put(type, info) != null)
				throw new IllegalStateException("duplicate building type in XML: " + type);
		}
		if (byType.isEmpty())
			throw new IllegalStateException("no buildings found in " + REGULAR_INPUT);
		return byType;
	}

	/** The processing tier: Regular buildings granting a manufactured bonus. */
	private static List<Recipe> exportRecipes(Set<String> catalog, Map<String, Element> buildings) {
		List<Recipe> out = new ArrayList<>();
		for (Map.Entry<String, Element> e : buildings.entrySet()) {
			Element info = e.getValue();
			List<String> outputs = new ArrayList<>();
			for (String granted : freeBonuses(info))
				if (catalog.contains(granted))
					outputs.add(granted);
			if (outputs.isEmpty())
				continue;
			out.add(new Recipe(
					e.getKey(),
					outputs,
					Civ4Xml.text(info, "Bonus"),
					Civ4Xml.textList(info, "PrereqBonuses", "Bonus"),
					Civ4Xml.textList(info, "PrereqVicinityBonuses", "VicinityBonus"),
					Civ4Xml.textList(info, "PrereqRawVicinityBonuses", "VicinityBonus"),
					Civ4Xml.text(info, "PrereqTech"),
					Civ4Xml.text(info, "ObsoleteTech"),
					Civ4Xml.textList(info, "PrereqInCityBuildings", "BuildingType"),
					Civ4Xml.textList(info, "PrereqOrBuildings", "BuildingType"),
					Civ4Xml.validTypes(info, "PrereqOrTerrain", "PrereqTerrain",
							"TerrainType", "bPrereqTerrain"),
					Civ4Xml.validTypes(info, "PrereqOrFeature", "PrereqFeature",
							"FeatureType", "bPrereqFeature"),
					Civ4Xml.boolVal(info, "bRiver"),
					Civ4Xml.boolVal(info, "bFreshWater")));
		}
		if (out.isEmpty())
			throw new IllegalStateException("no manufactured-good recipes found in " + REGULAR_INPUT);
		return out;
	}

	/** The extraction tier: providers with their gatherers' plot prereqs inlined. */
	private static List<TierOneSource> exportTierOne(Map<String, Element> buildings) {
		List<TierOneSource> out = new ArrayList<>();
		List<String> missing = new ArrayList<>();
		for (Element info : Civ4Xml.infos(Civ4Xml.fetch(PROVIDERS_INPUT), "BuildingInfo")) {
			String type = Civ4Xml.text(info, "Type");
			if (type == null || type.isEmpty())
				continue;
			List<String> granted = freeBonuses(info);
			if (granted.size() != 1)
				throw new IllegalStateException(
						"provider " + type + " grants " + granted + " (expected exactly one)");
			List<TierOneSource.Gatherer> gatherers = new ArrayList<>();
			for (String gathererType : Civ4Xml.textList(info, "PrereqOrBuildings", "BuildingType")) {
				Element g = buildings.get(gathererType);
				if (g == null) {
					missing.add(type + " <- " + gathererType);
					continue;
				}
				gatherers.add(new TierOneSource.Gatherer(
						gathererType,
						Civ4Xml.text(g, "PrereqTech"),
						Civ4Xml.text(g, "Bonus"),
						Civ4Xml.textList(g, "PrereqBonuses", "Bonus"),
						Civ4Xml.textList(g, "PrereqVicinityBonuses", "VicinityBonus"),
						Civ4Xml.textList(g, "PrereqRawVicinityBonuses", "VicinityBonus"),
						Civ4Xml.validTypes(g, "PrereqOrTerrain", "PrereqTerrain",
								"TerrainType", "bPrereqTerrain"),
						Civ4Xml.validTypes(g, "PrereqOrFeature", "PrereqFeature",
								"FeatureType", "bPrereqFeature"),
						Civ4Xml.boolVal(g, "bRiver"),
						Civ4Xml.boolVal(g, "bFreshWater")));
			}
			if (gatherers.isEmpty())
				throw new IllegalStateException("provider " + type + " has no known gatherers");
			out.add(new TierOneSource(type, granted.get(0), gatherers));
		}
		if (out.isEmpty())
			throw new IllegalStateException("no providers found in " + PROVIDERS_INPUT);
		if (!missing.isEmpty())
			System.out.println("skipped " + missing.size()
					+ " gatherers not defined in the Regular file: " + missing);
		return out;
	}

	/** The bonuses a building grants: each {@code <ExtraFreeBonuses>} entry's key. */
	private static List<String> freeBonuses(Element info) {
		List<String> out = new ArrayList<>();
		Element container = Civ4Xml.child(info, "ExtraFreeBonuses");
		if (container == null)
			return out;
		for (Element entry : Civ4Xml.children(container, "ExtraFreeBonus")) {
			String bonus = Civ4Xml.text(entry, "FreeBonus");
			if (bonus != null && !bonus.isEmpty())
				out.add(bonus);
		}
		return out;
	}
}
