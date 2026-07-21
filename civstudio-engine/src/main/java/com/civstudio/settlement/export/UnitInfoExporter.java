package com.civstudio.settlement.export;

import com.civstudio.data.Exports;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

import com.civstudio.agent.CaravanRole;
import com.civstudio.geo.export.Civ4Xml;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Dev tool: bake {@code src/main/resources/generated/units.json},
 * {@code unit-unlocks.json} and {@code unit-combats.json} — the eos <b>land buildable</b>
 * unit set, imported from Caveman2Cosmos and <b>gated to the eos tech horizon</b>. Sibling
 * of {@link BuildingInfoExporter}; the design is {@code docs/c2c-unit-import.md}.
 * <p>
 * A C2C unit is imported iff it is {@code DOMAIN_LAND} and its tech prerequisite fits the eos
 * horizon: a unit with a real {@code <PrereqTech>} is kept only if its <b>whole prerequisite
 * expression resolves within the kept techs</b> — the primary {@code <PrereqTech>}
 * <em>and</em> every {@code <TechTypes>} entry (the AND-list) must name a tech that survives
 * into {@code techs.json} (the Prehistoric&rarr;Renaissance horizon capped at the lone
 * Industrial end-cap). Unlike the building import, a unit with <b>no</b> {@code <PrereqTech>}
 * is <b>not dropped</b> but <b>linked to {@code TECH_SEDENTARY_LIFESTYLE}</b> (owner) — so
 * the ancient starters ({@code UNIT_BAND}/{@code UNIT_TRIBE}) and the capture/immigration
 * units enter the catalog with an early unlock. The CAP handling and kept-tech read are
 * otherwise identical to {@link BuildingInfoExporter}. Scope is the three core land files —
 * {@code U_Land}, {@code U_Workers}, {@code CIV4UnitInfos}; the animal/subdued/sea/air and
 * race-specific ({@code U_Neanderthals}) sets are out of scope.
 * <p>
 * Ids are the C2C {@code <Type>} verbatim ({@code UNIT_*}) — the id
 * {@link com.civstudio.settlement.Unit}, the caravan {@code unitId} and the tech
 * {@code Unlock} target share. Each row carries the caravan-relevant core (decision 4):
 * {@code {id, name, pedia, prereqTech, andTechs, combatClass, defaultUnitAI, caravanRole,
 * domain, iMoves, iCombat, iCost, obsoleteTech, bandSizeClass, era, species, quality,
 * builds, artDefineTag, button}}. {@code caravanRole} is the {@code <Combat>}-with-AI-
 * fallback fold ({@link CaravanRole#fromUnit}); {@code builds} is the worker's
 * {@code <Builds>} repertoire (the seam {@code docs/c2c-build-import.md} realizes); art
 * resolves through {@code <UnitMeshGroups>} &rarr; first {@code <EarlyArtDefineTag>} &rarr;
 * {@code CIV4ArtDefines_Unit.xml}.
 * <p>
 * The {@code unit-combats.json} sidecar keeps the <b>functional</b> UnitCombat classes
 * (those that appear as a {@code <Combat>} of an in-scope unit), each with its name,
 * category-icon {@code <Button>}, combat modifiers and folded {@code signatureSkill} — the
 * source of the {@code <Combat>}&rarr;skill fold and the tech-tree grouping icons.
 * <p>
 * Pure data — no economic RNG, no runtime behaviour change (nothing loads the JSON yet; the
 * unlock overlay grants tokens but nothing reads unit tokens). Run from the project root:
 * <pre>
 *   mvn -q -pl civstudio-engine compile exec:exec \
 *       -Dsim.main=com.civstudio.settlement.export.UnitInfoExporter
 * </pre>
 */
public final class UnitInfoExporter {

	// the three core land-unit files (document order preserved across them)
	private static final List<String> UNIT_FILES = List.of(
			"assets/XML/Units/U_Land_CIV4UnitInfos.xml",
			"assets/XML/Units/U_Workers_CIV4UnitInfos.xml",
			"assets/XML/Units/CIV4UnitInfos.xml");

	private static final String UNIT_COMBAT_XML = "assets/XML/Units/CIV4UnitCombatInfos.xml";
	private static final String ART_XML = "assets/XML/Art/CIV4ArtDefines_Unit.xml";

	// the GameText holding the TXT_KEY_UNIT_* (name/pedia) and TXT_KEY_UNITCOMBAT_* strings
	private static final List<String> GAMETEXT_FILES = List.of(
			"assets/XML/GameText/Units_CIV4GameText.xml",
			"assets/XML/GameText/Combat_CIV4GameText.xml");

	private static final String TECHS = "civstudio-engine/target/generated/techs.json";
	private static final String OUTPUT = "civstudio-engine/target/generated/units.json";
	private static final String UNLOCKS_OUTPUT =
			"civstudio-engine/target/generated/unit-unlocks.json";
	private static final String COMBATS_OUTPUT =
			"civstudio-engine/target/generated/unit-combats.json";

	// the lone past-ceiling end-cap tech; the engine drops it at load, so a unit gated on
	// it gets no UNLOCK effect (but still appears in units.json for the web view). The single
	// global home for the horizon tech (mirrors BuildingInfoExporter.CAP_TECH / TechInfoExporter.CAP).
	private static final String CAP_TECH = com.civstudio.tech.TechTree.CAP_TECH;

	// a unit with no <PrereqTech> is not dropped but linked here (owner) — the early tech that
	// makes the ancient starters (UNIT_BAND/UNIT_TRIBE) and the capture/immigration units
	// available near the colony's beginning rather than excluding them from the catalog.
	private static final String FALLBACK_TECH = "TECH_SEDENTARY_LIFESTYLE";

	// the combat-modifier ints captured onto each unit-combats.json row (decision 11)
	private static final List<String> COMBAT_MODIFIERS = List.of(
			"iEarlyWithdrawChange", "iTauntChange", "iDodgeModifierChange",
			"iDamageModifierChange", "iPrecisionModifierChange",
			"iCaptureResistanceModifierChange");

	private UnitInfoExporter() {
	}

	public static void main(String[] args) throws Exception {
		Set<String> keptTechs = loadKeptTechs();
		System.out.println("Loaded " + keptTechs.size() + " kept techs from " + TECHS);

		Map<String, String> english = loadGameText();
		System.out.println("Loaded " + english.size() + " localized English strings");

		Map<String, String> buttons = loadArtButtons();
		System.out.println("Loaded " + buttons.size() + " unit art-button paths");

		List<Map<String, Object>> out = new ArrayList<>();
		// the UNLOCK overlay: primary prereq tech -> the effects that grant its units' tokens
		Map<String, List<Map<String, String>>> unlocks = new LinkedHashMap<>();
		Map<String, Integer> perFile = new LinkedHashMap<>();
		Map<String, Integer> perRole = new TreeMap<>();
		Map<String, Integer> perCombat = new TreeMap<>();
		Set<String> inScopeCombats = new HashSet<>(); // <Combat> classes used by kept units
		Map<String, String> byId = new HashMap<>(); // id -> source file, duplicate detection
		int scanned = 0, notLand = 0, noPrereqLinked = 0, gatedOut = 0;
		int noName = 0, noButton = 0, cappedUnlocks = 0, withBuilds = 0;

		for (String file : UNIT_FILES) {
			Document doc = Civ4Xml.fetch(file);
			int keptHere = 0;
			for (Element u : Civ4Xml.infos(doc, "UnitInfo")) {
				scanned++;
				String id = Civ4Xml.text(u, "Type");
				String domain = Civ4Xml.text(u, "Domain");
				if (!"DOMAIN_LAND".equals(domain)) {
					notLand++;
					continue;
				}
				String primary = Civ4Xml.text(u, "PrereqTech"); // direct child only (not TechTypes)
				List<String> andTechs = Civ4Xml.textList(u, "TechTypes", "PrereqTech");
				if (primary == null || primary.isEmpty()) {
					// no tech prereq → link to the early sedentary-lifestyle tech instead of
					// dropping (owner), so the ancient starters (UNIT_BAND/UNIT_TRIBE) and the
					// capture/immigration units enter the catalog with an unlock of their own.
					primary = FALLBACK_TECH;
					noPrereqLinked++;
				} else if (!keptTechs.contains(primary) || !keptTechs.containsAll(andTechs)) {
					// a real prereq (or AND-entry) beyond the eos horizon → out of scope
					gatedOut++;
					continue;
				}
				// capture/immigration special units (military/civilian captives, captive
				// immigrant, freed slave) are tagged for the future capture/slavery mechanics
				String special = specialCategory(u, id);
				String prev = byId.putIfAbsent(id, file);
				if (prev != null)
					throw new IllegalStateException("duplicate unit id " + id
							+ " (in " + prev + " and " + file + ")");

				String combat = Civ4Xml.text(u, "Combat");
				String ai = Civ4Xml.text(u, "DefaultUnitAI");
				CaravanRole role = CaravanRole.fromUnit(combat, ai);

				Map<String, Object> row = new LinkedHashMap<>();
				row.put("id", id);
				String name = english.get(Civ4Xml.text(u, "Description"));
				String pedia = english.get(Civ4Xml.text(u, "Civilopedia"));
				if (name != null)
					row.put("name", name);
				else
					noName++;
				if (pedia != null)
					row.put("pedia", pedia);

				row.put("prereqTech", primary);
				if (!andTechs.isEmpty())
					row.put("andTechs", andTechs);
				if (special != null)
					row.put("special", special);

				if (combat != null)
					row.put("combatClass", combat);
				if (ai != null)
					row.put("defaultUnitAI", ai);
				row.put("caravanRole", role.name());
				row.put("domain", domain);

				putInt(row, "iMoves", Civ4Xml.text(u, "iMoves"));
				putInt(row, "iCombat", Civ4Xml.text(u, "iCombat"));
				putInt(row, "iCost", Civ4Xml.text(u, "iCost"));
				String obsolete = Civ4Xml.text(u, "ObsoleteTech");
				if (obsolete != null && !obsolete.isEmpty())
					row.put("obsoleteTech", obsolete);

				// the meaningful SubCombatTypes families (decision 12)
				putSubCombats(row, Civ4Xml.textList(u, "SubCombatTypes", "SubCombatType"));

				// the worker's build repertoire (docs/c2c-build-import.md) — worker-family units
				// only carry a meaningful <Builds>; filter to builds surviving nothing here (the
				// build gate is the build importer's job), just capture the list verbatim
				List<String> builds = Civ4Xml.textList(u, "Builds", "BuildType");
				if (!builds.isEmpty()) {
					row.put("builds", builds);
					withBuilds++;
				}

				String artTag = firstArtTag(u);
				if (artTag != null)
					row.put("artDefineTag", artTag);
				String button = artTag == null ? null : buttons.get(artTag);
				if (button != null)
					row.put("button", button);
				else
					noButton++;

				out.add(row);
				keptHere++;
				perRole.merge(role.name(), 1, Integer::sum);
				if (combat != null) {
					perCombat.merge(combat, 1, Integer::sum);
					inScopeCombats.add(combat);
				}

				// hang an UNLOCK effect off the primary prereq tech (the real one, or the
				// sedentary-lifestyle fallback for a no-prereq unit), unless it is the engine-
				// dropped CAP (a CAP-gated unit displays but can't be researched → no token).
				if (CAP_TECH.equals(primary)) {
					cappedUnlocks++;
				} else {
					unlocks.computeIfAbsent(primary, k -> new ArrayList<>())
							.add(Map.of("kind", "UNLOCK", "target", id));
				}
			}
			perFile.put(file, keptHere);
		}

		List<Map<String, Object>> combats = loadUnitCombats(inScopeCombats, english);

		ObjectMapper mapper = new ObjectMapper();
		mapper.writerWithDefaultPrettyPrinter().writeValue(Exports.outFile(OUTPUT), out);
		mapper.writerWithDefaultPrettyPrinter().writeValue(Exports.outFile(UNLOCKS_OUTPUT), unlocks);
		mapper.writerWithDefaultPrettyPrinter().writeValue(Exports.outFile(COMBATS_OUTPUT), combats);

		int unlockEffects = unlocks.values().stream().mapToInt(List::size).sum();
		System.out.println("Wrote " + out.size() + " units to " + OUTPUT);
		System.out.println("Wrote " + unlockEffects + " UNLOCK effects over " + unlocks.size()
				+ " techs to " + UNLOCKS_OUTPUT
				+ " (" + cappedUnlocks + " CAP-gated units excluded — engine drops the tech)");
		System.out.println("Wrote " + combats.size() + " functional UnitCombat classes to "
				+ COMBATS_OUTPUT);
		System.out.println("  scanned " + scanned + " UnitInfo records (" + notLand
				+ " non-land, " + gatedOut + " gated out by tech scope; " + noPrereqLinked
				+ " no-prereq units linked to " + FALLBACK_TECH + ")");
		perFile.forEach((f, n) -> System.out.println("  " + f + ": " + n));
		System.out.println("  by role: " + perRole);
		System.out.println("  by combat class: " + perCombat);
		System.out.println("  " + withBuilds + " units carry a <Builds> repertoire");
		if (noName > 0)
			System.out.println("  WARNING: " + noName + " units had no English name");
		if (noButton > 0)
			System.out.println("  " + noButton + " units without a resolved <Button> "
					+ "(colour-chip fallback at bake time)");
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

	/**
	 * Merge the {@code TXT_KEY_* -> English} maps of the unit GameText files. The English text
	 * is normally the direct text of {@code <English>}; some entries wrap it in a {@code <Text>}
	 * child (the gendered/plural form), which is unwrapped.
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

	/** The {@code artDefineTag -> <Button>} path map from {@code CIV4ArtDefines_Unit.xml}. */
	private static Map<String, String> loadArtButtons() {
		Map<String, String> map = new HashMap<>();
		Document doc = Civ4Xml.fetch(ART_XML);
		for (Element art : Civ4Xml.infos(doc, "UnitArtInfo")) {
			String tag = Civ4Xml.text(art, "Type");
			String button = Civ4Xml.text(art, "Button");
			if (tag != null && button != null && !button.isEmpty())
				map.put(tag, button);
		}
		return map;
	}

	/**
	 * The functional UnitCombat classes — those that appear as a {@code <Combat>} of an
	 * in-scope unit (~50 of the 724; the rest are weapon/armor/animal/era/religion tag
	 * taxonomy). Each row carries id, name, the category-icon {@code <Button>}, the combat
	 * modifiers, {@code bForMilitary}, and the folded {@code signatureSkill}.
	 */
	private static List<Map<String, Object>> loadUnitCombats(
			Set<String> inScope, Map<String, String> english) {
		List<Map<String, Object>> rows = new ArrayList<>();
		Document doc = Civ4Xml.fetch(UNIT_COMBAT_XML);
		for (Element c : Civ4Xml.infos(doc, "UnitCombatInfo")) {
			String id = Civ4Xml.text(c, "Type");
			if (id == null || !inScope.contains(id))
				continue;
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("id", id);
			String name = english.get(Civ4Xml.text(c, "Description"));
			if (name != null)
				row.put("name", name);
			row.put("signatureSkill", CaravanRole.signatureSkillOf(id, null).name());
			String button = Civ4Xml.text(c, "Button");
			if (button != null && !button.isEmpty())
				row.put("categoryButton", button);
			for (String mod : COMBAT_MODIFIERS)
				putInt(row, mod, Civ4Xml.text(c, mod));
			String forMilitary = Civ4Xml.text(c, "bForMilitary");
			if ("1".equals(forMilitary))
				row.put("bForMilitary", true);
			rows.add(row);
		}
		return rows;
	}

	// the special (non-tech-buildable) category of a capture/immigration unit to keep despite
	// no <PrereqTech>, or null for a normal unit. SPECIALUNIT_CAPTIVE marks the military/civilian
	// captives; the captive-immigrant and freed-slave carry no marker, so match by id. These feed
	// the future capture/slavery/property mechanics (the SUBTERFUGE seam, docs/c2c-unit-import.md).
	private static String specialCategory(Element u, String id) {
		if ("SPECIALUNIT_CAPTIVE".equals(Civ4Xml.text(u, "Special")))
			return "CAPTIVE";
		if (id == null)
			return null;
		if (id.contains("CAPTIVE"))
			return "CAPTIVE";
		if (id.contains("FREED_SLAVE"))
			return "FREED_SLAVE";
		return null;
	}

	// the unit's representative art tag: the first <EarlyArtDefineTag> across its
	// <UnitMeshGroups> (a multi-mesh band lists male/female/child — the first is representative)
	private static String firstArtTag(Element unit) {
		Element groups = Civ4Xml.child(unit, "UnitMeshGroups");
		if (groups == null)
			return null;
		for (Element grp : Civ4Xml.children(groups, "UnitMeshGroup")) {
			String tag = Civ4Xml.text(grp, "EarlyArtDefineTag");
			if (tag != null && !tag.isEmpty())
				return tag;
		}
		return null;
	}

	// classify each SubCombatType into the whitelisted families and stamp the (prefix-stripped)
	// value onto the row: GROUP_* -> bandSizeClass, ERA_* -> era, SPECIES_* -> species,
	// QUALITY_* -> quality. The rest (weapon/armor/attack-form/motility noise) is skipped.
	private static void putSubCombats(Map<String, Object> row, List<String> subs) {
		for (String s : subs) {
			String bare = s.startsWith("UNITCOMBAT_") ? s.substring("UNITCOMBAT_".length()) : s;
			if (bare.startsWith("GROUP_"))
				row.putIfAbsent("bandSizeClass", bare);
			else if (bare.startsWith("ERA_"))
				row.putIfAbsent("era", bare);
			else if (bare.startsWith("SPECIES_"))
				row.putIfAbsent("species", bare);
			else if (bare.startsWith("QUALITY_"))
				row.putIfAbsent("quality", bare);
		}
	}

	// parse a C2C int field, omitting it from the row when absent/blank/non-numeric
	private static void putInt(Map<String, Object> row, String key, String raw) {
		if (raw == null || raw.isEmpty())
			return;
		try {
			row.put(key, Integer.parseInt(raw.trim()));
		} catch (NumberFormatException ignored) {
			// leave it out rather than emit a bad value
		}
	}
}
