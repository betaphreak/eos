package com.civstudio.geo.export;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

import com.civstudio.settlement.HousingBuilding;
import com.fasterxml.jackson.databind.ObjectMapper;

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

	private static final String INPUT = "data/civ4/SpecialBuildings_CIV4BuildingInfos.xml";
	private static final String OUTPUT = "src/main/resources/housing.json";

	/** Every housing building's {@code <Type>} starts with this. */
	private static final String HOUSING_PREFIX = "BUILDING_HOUSING_";

	private HousingExporter() {
	}

	public static void main(String[] args) throws Exception {
		Document doc = Civ4Xml.parse(INPUT);
		List<HousingBuilding> out = new ArrayList<>();
		for (Element info : Civ4Xml.infos(doc, "BuildingInfo")) {
			String type = Civ4Xml.text(info, "Type");
			if (type == null || !type.startsWith(HOUSING_PREFIX))
				continue;
			out.add(new HousingBuilding(
					type,
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

		File f = new File(OUTPUT);
		new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(f, out);
		System.out.println("wrote " + out.size() + " housing buildings to " + f.getAbsolutePath());
	}
}
