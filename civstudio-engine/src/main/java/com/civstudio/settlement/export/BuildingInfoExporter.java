package com.civstudio.settlement.export;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

import com.civstudio.geo.export.Civ4Xml;
import com.civstudio.tech.Advisor;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Dev tool: bake {@code src/main/resources/generated/buildings.json} — the eos
 * building set, imported from Caveman2Cosmos and <b>gated to the eos tech horizon</b>.
 * Sibling of {@link com.civstudio.tech.export.TechInfoExporter}; the design is
 * {@code docs/c2c-building-import.md}.
 * <p>
 * A C2C building is imported iff its <b>whole tech-prerequisite expression resolves
 * within the kept techs</b> — its primary {@code <PrereqTech>} <em>and</em> every
 * {@code <TechTypes>} entry (the AND-list) must name a tech that survives into
 * {@code techs.json} (the Prehistoric&rarr;Renaissance horizon capped at the lone
 * Industrial end-cap; see {@code TechInfoExporter}). A building needing any later-era
 * or dropped tech is unreachable, so it is excluded. The kept set is read straight
 * from the already-baked {@code techs.json}, so it can never drift from the shipped
 * tree. Reading the three building files — {@code Regular}, {@code SpecialBuildings}
 * and {@code zProviders} (the last carries no {@code <PrereqTech>}, so contributes
 * none) — yields <b>1,271</b> gated buildings at the pinned C2C ref.
 * <p>
 * Ids are the C2C {@code <Type>} verbatim ({@code BUILDING_*}) — the same id
 * {@link com.civstudio.settlement.Building} and the tech {@code Unlock} target use, so
 * there is no mapping table. Each row carries {@code {id, name, help, pedia, category,
 * prereqTech, andTechs, artDefineTag, button, cost}}: {@code name}/{@code help}/{@code
 * pedia} resolve the {@code TXT_KEY_BUILDING_*} strings from the building GameText;
 * {@code category} is the building's {@code <Advisor>} folded onto the shared
 * {@link Advisor} taxonomy (omitted when the building has no advisor — the belief /
 * housing module buildings); {@code button} is the {@code <Button>} path resolved
 * through {@code CIV4ArtDefines_Building.xml} by {@code artDefineTag}.
 * <p>
 * Pure data — no economic RNG, no runtime behaviour change (nothing loads
 * {@code buildings.json} yet; the tech {@code Unlock} wiring and the web bake are
 * later phases). Run from the project root:
 * <pre>
 *   mvn -q -pl civstudio-engine compile exec:exec \
 *       -Dsim.main=com.civstudio.settlement.export.BuildingInfoExporter
 * </pre>
 */
public final class BuildingInfoExporter {

	// the three building files in scope (document order preserved across them). zProviders
	// carries no <PrereqTech> so it gates to nothing, but is read for completeness / future.
	private static final List<String> BUILDING_FILES = List.of(
			"Regular_CIV4BuildingInfos.xml",
			"SpecialBuildings_CIV4BuildingInfos.xml",
			"zProviders_CIV4BuildingInfos.xml");

	// the GameText files that hold the TXT_KEY_BUILDING_* name/help/pedia strings for the
	// gated set (empirically: every gated building's name resolves from one of these six)
	private static final List<String> GAMETEXT_FILES = List.of(
			"assets/XML/GameText/Buildings_CIV4GameText.xml",
			"assets/XML/GameText/Buildings_Animals_CIV4GameText.xml",
			"assets/XML/GameText/Slavery_CIV4GameText.xml",
			"assets/XML/GameText/Traditions_CIV4GameText.xml",
			"assets/XML/GameText/Human_Sacrifice_CIV4GameText.xml",
			"assets/XML/GameText/Cannibalism_CIV4GameText.xml");

	private static final String ART_XML = "assets/XML/Art/CIV4ArtDefines_Building.xml";
	private static final String TECHS = "civstudio-engine/src/main/resources/generated/techs.json";
	private static final String OUTPUT = "civstudio-engine/src/main/resources/generated/buildings.json";

	private BuildingInfoExporter() {
	}

	public static void main(String[] args) throws Exception {
		Set<String> keptTechs = loadKeptTechs();
		System.out.println("Loaded " + keptTechs.size() + " kept techs from " + TECHS);

		Map<String, String> english = loadGameText();
		System.out.println("Loaded " + english.size() + " localized English strings");

		Map<String, String> buttons = loadArtButtons();
		System.out.println("Loaded " + buttons.size() + " building art-button paths");

		List<Map<String, Object>> out = new ArrayList<>();
		Map<String, Integer> perFile = new LinkedHashMap<>();
		Map<String, Integer> perCategory = new TreeMap<>();
		Map<String, String> byId = new HashMap<>(); // id -> source file, for duplicate detection
		int scanned = 0, noPrereq = 0, gatedOut = 0;
		int noName = 0, noButton = 0, noCategory = 0;

		for (String file : BUILDING_FILES) {
			Document doc = Civ4Xml.fetch(file);
			int keptHere = 0;
			for (Element b : Civ4Xml.infos(doc, "BuildingInfo")) {
				scanned++;
				String id = Civ4Xml.text(b, "Type");
				String primary = Civ4Xml.text(b, "PrereqTech"); // direct child only (not TechTypes)
				if (primary == null || primary.isEmpty()) {
					noPrereq++;
					continue;
				}
				List<String> andTechs = Civ4Xml.textList(b, "TechTypes", "PrereqTech");
				// the gate: the whole AND expression must resolve within the kept techs
				if (!keptTechs.contains(primary) || !keptTechs.containsAll(andTechs)) {
					gatedOut++;
					continue;
				}
				String prev = byId.putIfAbsent(id, file);
				if (prev != null)
					throw new IllegalStateException("duplicate building id " + id
							+ " (in " + prev + " and " + file + ")");

				Map<String, Object> row = new LinkedHashMap<>();
				row.put("id", id);
				String name = english.get(Civ4Xml.text(b, "Description"));
				String help = english.get(Civ4Xml.text(b, "Help"));
				String pedia = english.get(Civ4Xml.text(b, "Civilopedia"));
				if (name != null)
					row.put("name", name);
				else
					noName++;
				if (help != null)
					row.put("help", help);
				if (pedia != null)
					row.put("pedia", pedia);

				Optional<Advisor> advisor = Advisor.fromKey(Civ4Xml.text(b, "Advisor"));
				if (advisor.isPresent()) {
					String cat = advisor.get().name();
					row.put("category", cat);
					perCategory.merge(cat, 1, Integer::sum);
				} else {
					noCategory++;
				}

				row.put("prereqTech", primary);
				if (!andTechs.isEmpty())
					row.put("andTechs", andTechs);

				String artTag = Civ4Xml.text(b, "ArtDefineTag");
				if (artTag != null)
					row.put("artDefineTag", artTag);
				String button = artTag == null ? null : buttons.get(artTag);
				if (button != null)
					row.put("button", button);
				else
					noButton++;

				String cost = Civ4Xml.text(b, "iCost");
				if (cost != null && !cost.isEmpty())
					row.put("cost", cost);

				out.add(row);
				keptHere++;
			}
			perFile.put(file, keptHere);
		}

		new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(new File(OUTPUT), out);

		System.out.println("Wrote " + out.size() + " buildings to " + OUTPUT);
		System.out.println("  scanned " + scanned + " BuildingInfo records ("
				+ noPrereq + " without <PrereqTech>, " + gatedOut + " gated out by tech scope)");
		perFile.forEach((f, n) -> System.out.println("  " + f + ": " + n));
		System.out.println("  by category: " + perCategory
				+ " (+ " + noCategory + " uncategorized — no <Advisor>)");
		if (noName > 0)
			System.out.println("  WARNING: " + noName + " buildings had no English name");
		if (noButton > 0)
			System.out.println("  " + noButton + " buildings without a resolved <Button> "
					+ "(colour-chip fallback at bake time)");
	}

	/** The kept-tech id set = every {@code Type} in the already-baked {@code techs.json}. */
	private static Set<String> loadKeptTechs() throws Exception {
		List<Map<String, Object>> techs = new ObjectMapper().readValue(new File(TECHS),
				new TypeReference<List<Map<String, Object>>>() {
				});
		Set<String> ids = new java.util.HashSet<>();
		for (Map<String, Object> t : techs)
			ids.add((String) t.get("Type"));
		return ids;
	}

	/**
	 * Merge the {@code TXT_KEY_* -> English} maps of every building GameText file. The
	 * English text is normally the direct text of {@code <English>}; some entries wrap it
	 * in a {@code <Text>} child (the gendered/plural form), which is unwrapped.
	 */
	private static Map<String, String> loadGameText() {
		Map<String, String> map = new HashMap<>();
		for (String file : GAMETEXT_FILES) {
			Document doc = Civ4Xml.fetch(file);
			for (Element t : Civ4Xml.infos(doc, "TEXT")) {
				String tag = Civ4Xml.text(t, "Tag");
				if (tag == null)
					continue;
				Element en = Civ4Xml.child(t, "English");
				if (en == null)
					continue;
				Element inner = Civ4Xml.child(en, "Text");
				String val = (inner != null ? inner.getTextContent() : en.getTextContent());
				if (val != null)
					map.putIfAbsent(tag, val.trim());
			}
		}
		return map;
	}

	/** The {@code artDefineTag -> <Button>} path map from {@code CIV4ArtDefines_Building.xml}. */
	private static Map<String, String> loadArtButtons() {
		Map<String, String> map = new HashMap<>();
		Document doc = Civ4Xml.fetch(ART_XML);
		for (Element art : Civ4Xml.infos(doc, "BuildingArtInfo")) {
			String tag = Civ4Xml.text(art, "Type");
			String button = Civ4Xml.text(art, "Button");
			if (tag != null && button != null && !button.isEmpty())
				map.put(tag, button);
		}
		return map;
	}
}
