package com.civstudio.geo.export;

import com.civstudio.data.Exports;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

import com.civstudio.geo.Improvement;
import tools.jackson.core.type.TypeReference;
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
	private static final String OUTPUT = "civstudio-engine/target/generated/improvements.json";
	// the baked tech set — the Prehistoric→Renaissance horizon, ending on the single tech cap
	// (TechTree.CAP_TECH); a tech beyond it is unreachable in eos, so any improvement data gated
	// on it is dropped, exactly as BuildingInfoExporter caps the building set.
	private static final String TECHS = "civstudio-engine/target/generated/techs.json";

	/**
	 * The curated firm-building + settlement-ladder subset, in {@code docs/plots.md} order. The
	 * cottage line (Cottage→Hamlet→Village→Town→Suburbs) is the settlement growth ladder a camped
	 * caravan climbs (see {@code docs/settlement-tiers.md}); the rest are the on-plot firm buildings.
	 */
	private static final Set<String> KEEP = new LinkedHashSet<>(List.of(
			"IMPROVEMENT_FARM", "IMPROVEMENT_MINE", "IMPROVEMENT_QUARRY",
			"IMPROVEMENT_LUMBERMILL", "IMPROVEMENT_PASTURE", "IMPROVEMENT_WINERY",
			"IMPROVEMENT_PLANTATION", "IMPROVEMENT_COTTAGE", "IMPROVEMENT_HAMLET",
			"IMPROVEMENT_VILLAGE", "IMPROVEMENT_TOWN", "IMPROVEMENT_HUNTING_CAMP"));
	// note: IMPROVEMENT_SUBURBS (the rung above Town) is deliberately absent — its prereq tech is
	// past the eos tech horizon (TechTree.CAP_TECH), so it is out of scope for now; Suburbs is a
	// future feature (merging several Towns in a province into a City — docs/settlement-tiers.md).

	private ImprovementExporter() {
	}

	public static void main(String[] args) throws Exception {
		Document doc = Civ4Xml.fetch(INPUT);
		Set<String> keptTechs = loadKeptTechs();
		List<Improvement> out = new ArrayList<>();
		Set<String> seen = new LinkedHashSet<>();
		for (Element info : Civ4Xml.infos(doc, "ImprovementInfo")) {
			String type = Civ4Xml.text(info, "Type");
			if (type == null || !KEEP.contains(type))
				continue;
			// gate the improvement itself and its tech-scaled yields to the eos tech horizon
			String prereq = Civ4Xml.text(info, "PrereqTech");
			if (prereq != null && !prereq.isEmpty() && !keptTechs.contains(prereq))
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
					Civ4Xml.intVal(info, "iHealthPercent", 0),
					Civ4Xml.text(info, "ImprovementUpgrade"),
					Civ4Xml.intVal(info, "iUpgradeTime", 0),
					Civ4Xml.intVal(info, "iCulture", 0),
					Civ4Xml.boolVal(info, "bActsAsCity"),
					techYields(info, keptTechs)));
			seen.add(type);
		}
		Set<String> missing = new LinkedHashSet<>(KEEP);
		missing.removeAll(seen);
		if (!missing.isEmpty())
			throw new IllegalStateException("curated improvements not found in XML: " + missing);

		File f = Exports.outFile(OUTPUT);
		new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(f, out);
		System.out.println("wrote " + out.size() + " improvements to " + f.getAbsolutePath());
	}

	// the improvement's tech-scaled yields — each <TechYieldChange> is a yield triple that
	// switches on once its <PrereqTech> is researched (the cottage line's growth: a Town keeps
	// gaining commerce at Printing Press / Economics / …). Gated to the eos tech horizon: a yield
	// keyed on a tech beyond it (not in keptTechs) is unreachable, so dropped. See
	// docs/settlement-tiers.md.
	private static List<Improvement.TechYield> techYields(Element info, Set<String> keptTechs) {
		List<Improvement.TechYield> out = new ArrayList<>();
		Element c = Civ4Xml.child(info, "TechYieldChanges");
		if (c == null)
			return out;
		for (Element e : Civ4Xml.children(c, "TechYieldChange")) {
			String tech = Civ4Xml.text(e, "PrereqTech");
			if (tech != null && !tech.isEmpty() && keptTechs.contains(tech))
				out.add(new Improvement.TechYield(tech,
						Civ4Xml.ints(e, "TechYields", "iYield", 3)));
		}
		return out;
	}

	/** The kept-tech id set = every {@code Type} in the already-baked {@code techs.json}. */
	private static Set<String> loadKeptTechs() throws Exception {
		List<Map<String, Object>> techs = new ObjectMapper().readValue(new File(TECHS),
				new TypeReference<List<Map<String, Object>>>() {
				});
		Set<String> ids = new HashSet<>();
		for (Map<String, Object> t : techs)
			ids.add((String) t.get("Type"));
		return ids;
	}
}
