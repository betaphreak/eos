package com.civstudio.geo.export;

import com.civstudio.data.Exports;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

import com.civstudio.settlement.HousingBuilding;
import tools.jackson.databind.ObjectMapper;

/**
 * Dev tool: parses {@code data/civ4/SpecialBuildings_CIV4BuildingInfos.xml} and emits the
 * <b>housing</b> subset — every {@code BUILDING_HOUSING_*} rung of the C2C housing
 * ladder — to the committed {@code /housing.json} resource. These are the buildings
 * a village <b>auto-builds at its center (plot 0) once their prerequisites are met</b>
 * (see {@code docs/plots.md}, <i>Buildings vs. improvements</i>); each emitted
 * {@link HousingBuilding} carries the prereq legs (tech, population, fresh water,
 * bonuses, prereq buildings/features/terrains) the auto-build decision reads, the
 * upgrade-ladder structure, and the (dormant) effect fields.
 * <p>
 * Mirrors {@link ImprovementExporter}; run manually:
 *
 * <pre>
 * mvn compile exec:exec -Dsim.main=com.civstudio.geo.export.HousingExporter
 * </pre>
 *
 * Unlike the curated {@link ImprovementExporter} this keeps <b>all</b> housing rungs
 * (the prefix {@code BUILDING_HOUSING_} is the filter) — far-future rungs are gated
 * naturally by their {@code PrereqTech}, so there is no hand-picked keep-list.
 */
public final class HousingExporter {

	private static final String INPUT = "SpecialBuildings_CIV4BuildingInfos.xml";
	private static final String OUTPUT = "civstudio-engine/target/generated/housing.json";

	/** Every housing building's {@code <Type>} starts with this. */
	private static final String HOUSING_PREFIX = "BUILDING_HOUSING_";

	/**
	 * The <b>hand-priced default build costs</b> (docs/build-queue-plan.md B2): under the
	 * build economy households hammer-build their housing, but C2C auto-grants it and so
	 * ships no {@code iCost} — these are eos-authored, in C2C-hammer-like units (the B4
	 * {@code BUILD_COST_SCALE} maps them to hammer-days). Roughly a ladder by prereq-tech
	 * era, with the class variants (hovel &lt; insula &lt; domus &lt; villa &lt; palace)
	 * priced apart. Studio's {@code authoredCost} overrides these per rung; a rung absent
	 * here (the {@code HOMELESS} marker, the past-horizon rungs) has no cost and is
	 * <b>unbuildable</b>. UNCALIBRATED.
	 */
	private static final java.util.Map<String, Integer> HAND_COSTS = java.util.Map.ofEntries(
			// primitive shelters
			java.util.Map.entry("BUILDING_HOUSING_ANIMAL_BURROW", 5),
			java.util.Map.entry("BUILDING_HOUSING_TREE_HOLLOW", 5),
			java.util.Map.entry("BUILDING_HOUSING_CAVE_DWELLING", 8),
			java.util.Map.entry("BUILDING_HOUSING_LEAN_TOS", 10),
			// early huts and tents
			java.util.Map.entry("BUILDING_HOUSING_ANIMAL_HIDE_TENTS", 12),
			java.util.Map.entry("BUILDING_HOUSING_BARK_HUTS", 15),
			java.util.Map.entry("BUILDING_HOUSING_BONE_HUTS", 15),
			java.util.Map.entry("BUILDING_HOUSING_GRASS_HUTS", 15),
			java.util.Map.entry("BUILDING_HOUSING_TIPIS", 15),
			java.util.Map.entry("BUILDING_HOUSING_YURTS", 15),
			java.util.Map.entry("BUILDING_HOUSING_IGLOOS", 15),
			java.util.Map.entry("BUILDING_HOUSING_STILT_HUTS", 18),
			java.util.Map.entry("BUILDING_HOUSING_TREEHOUSES", 20),
			// ancient solid construction
			java.util.Map.entry("BUILDING_HOUSING_MUD_HUTS", 25),
			java.util.Map.entry("BUILDING_HOUSING_LONGHOUSE", 30),
			java.util.Map.entry("BUILDING_HOUSING_HOGANS", 30),
			java.util.Map.entry("BUILDING_HOUSING_STONE_HUTS", 35),
			java.util.Map.entry("BUILDING_HOUSING_PUEBLOS", 40),
			java.util.Map.entry("BUILDING_HOUSING_CLIFF_DWELLING", 40),
			// classical class-variant housing (TECH_SANITATION)
			java.util.Map.entry("BUILDING_HOUSING_HOVELS", 40),
			java.util.Map.entry("BUILDING_HOUSING_SLUMS", 45),
			java.util.Map.entry("BUILDING_HOUSING_INSULAE", 60),
			java.util.Map.entry("BUILDING_HOUSING_DOMUS", 80),
			java.util.Map.entry("BUILDING_HOUSING_VILLAS", 120),
			java.util.Map.entry("BUILDING_HOUSING_PALACES", 250),
			// medieval class-variant housing (TECH_SURVEYING)
			java.util.Map.entry("BUILDING_HOUSING_SHACKS", 50),
			java.util.Map.entry("BUILDING_HOUSING_SHANTY_TOWN", 55),
			java.util.Map.entry("BUILDING_HOUSING_COMMONS", 70),
			java.util.Map.entry("BUILDING_HOUSING_COTTAGES", 80),
			java.util.Map.entry("BUILDING_HOUSING_MANORS", 180),
			java.util.Map.entry("BUILDING_HOUSING_ESTATES", 300),
			// late-horizon oddballs
			java.util.Map.entry("BUILDING_HOUSING_FRATERNITY_HOUSE", 120));

	private HousingExporter() {
	}

	public static void main(String[] args) throws Exception {
		Document doc = Civ4Xml.fetch(INPUT);
		// TXT_KEY_BUILDING_* -> English display names (shared with the building importer)
		var english = com.civstudio.settlement.export.BuildingInfoExporter.loadGameText();
		List<HousingBuilding> out = new ArrayList<>();
		for (Element info : Civ4Xml.infos(doc, "BuildingInfo")) {
			String type = Civ4Xml.text(info, "Type");
			if (type == null || !type.startsWith(HOUSING_PREFIX))
				continue;
			out.add(new HousingBuilding(
					type,
					english.get(Civ4Xml.text(info, "Description")),
					HAND_COSTS.get(type),
					null, // authoredCost is studio content — never exported
					Civ4Xml.text(info, "PrereqTech"),
					Civ4Xml.text(info, "ObsoleteTech"),
					Civ4Xml.text(info, "ObsoletesToBuilding"),
					Civ4Xml.intVal(info, "iPrereqPopulation", 0),
					Civ4Xml.boolVal(info, "bFreshWater"),
					Civ4Xml.boolVal(info, "bAutoBuild"),
					Civ4Xml.text(info, "Bonus"),
					Civ4Xml.textList(info, "PrereqBonuses", "Bonus"),
					Civ4Xml.textList(info, "PrereqInCityBuildings", "BuildingType"),
					Civ4Xml.textList(info, "PrereqOrBuildings", "BuildingType"),
					Civ4Xml.validTypes(info, "PrereqOrFeature", "PrereqFeature",
							"FeatureType", "bPrereqFeature"),
					Civ4Xml.validTypes(info, "PrereqOrTerrain", "PrereqTerrain",
							"TerrainType", "bPrereqTerrain"),
					Civ4Xml.textList(info, "ReplacementBuildings", "BuildingType"),
					Civ4Xml.intVal(info, "iHealth", 0),
					Civ4Xml.intVal(info, "iHappiness", 0),
					Civ4Xml.yields(info, "YieldChanges", "iYield"),
					Civ4Xml.ints(info, "CommerceChanges", "iCommerce", 4)));
		}
		if (out.isEmpty())
			throw new IllegalStateException("no " + HOUSING_PREFIX + "* buildings found in " + INPUT);

		File f = Exports.outFile(OUTPUT);
		new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(f, out);
		System.out.println("wrote " + out.size() + " housing buildings to " + f.getAbsolutePath());
	}
}
