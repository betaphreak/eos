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
 * Dev tool: parses {@code data/civ4/Manufactured_CIV4BonusInfos.xml} — the C2C
 * catalog of <b>produced</b> (never map-placed) bonuses: wood, bricks, leather,
 * cloth… — and emits the full 326-good set to the committed
 * {@code /manufactured-bonuses.json} resource, as {@link Bonus} records exactly like
 * the map-placed set in {@code /bonuses.json} (same schema, same fields; the
 * placement-constraint fields are simply absent/default here since these goods are
 * made, not placed). This is step 1 of {@code docs/manufactured-bonuses.md}
 * (decisions M4/M5): the whole catalog is exported, tech-gated at runtime by each
 * good's {@code TechReveal} so only the era-appropriate slice is ever live. Mirrors
 * {@link BonusExporter}; run manually:
 *
 * <pre>
 * mvn compile exec:exec -Dsim.main=com.civstudio.geo.export.ManufacturedBonusExporter
 * </pre>
 *
 * Note the catalog is 313 {@code BONUSCLASS_MANUFACTURED} + 13
 * {@code BONUSCLASS_WONDER} entries (wonder-granted pseudo-goods C2C keeps in the
 * same file); all 326 are exported, the {@link BonusClass} telling them apart.
 */
public final class ManufacturedBonusExporter {

	private static final String INPUT = "data/civ4/Manufactured_CIV4BonusInfos.xml";
	private static final String OUTPUT = "src/main/resources/manufactured-bonuses.json";

	private ManufacturedBonusExporter() {
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
							"TerrainType", "bFeatureTerrain"),
					// manufactured bonuses are crafted, never map-placed — no placement data
					0, 0, new int[4], 0, 0, 0, 0));
		}
		if (out.isEmpty())
			throw new IllegalStateException("no bonuses found in " + INPUT);

		File f = new File(OUTPUT);
		new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(f, out);
		System.out.println("wrote " + out.size() + " manufactured bonuses to " + f.getAbsolutePath());
	}
}
