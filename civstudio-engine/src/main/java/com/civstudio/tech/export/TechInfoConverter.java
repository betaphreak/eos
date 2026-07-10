package com.civstudio.tech.export;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import tools.jackson.databind.ObjectMapper;

/**
 * Dev tool: regenerate {@code src/main/resources/techs.json} from the vendored
 * Caveman2Cosmos source XML. Unlike the engine's {@link com.civstudio.tech.TechTree}
 * (which keeps only the six fields eos simulates), this converter is a <b>faithful,
 * lossless transliteration</b> of every field in each {@code <TechInfo>} — so the
 * web tech-tree view has the full graph to draw (grid coordinates, flavors, the whole
 * Civ4 flag tail) — <b>plus</b> the resolved English strings the source keeps only as
 * {@code TXT_KEY_*} references: {@code name} (from {@code Description}), {@code help}
 * (from {@code Civilopedia}, the pedia paragraph) and {@code quote} (from
 * {@code Quote}), joined from the localization file.
 * <p>
 * Only the eras eos models are kept — Prehistoric&hellip;Renaissance (the modeled
 * ceiling, matching {@link com.civstudio.era.Era} / {@code TechTree}'s
 * {@code MAX_TECH_ERA}); the ~578 later-era C2C techs (Industrial and beyond) are
 * dropped, since the engine drops them at load and the web tree only shows what a
 * colony can research. Source (document) order — already grid-sorted (x, then y) — is
 * preserved.
 * <p>
 * Sources are vendored under {@code data/civ4/} mirroring the original C2C
 * {@code Assets/} tree. Run from the project root:
 * <pre>
 *   mvn -q compile exec:exec -Dsim.main=com.civstudio.tech.export.TechInfoConverter
 * </pre>
 * The scalar values stay <b>strings</b> (as in the source and the prior file — e.g.
 * {@code "iCost": "3"}); {@link com.civstudio.tech.TechTree} parses the numerics it
 * needs, and {@code BonusExporter} reads {@code Type}/{@code Era} as strings.
 */
public final class TechInfoConverter {

	private static final String TECH_XML =
			"data/civ4/assets/XML/Technologies/CIV4TechInfos.xml";
	private static final String TEXT_XML =
			"data/civ4/assets/XML/GameText/Tech_CIV4GameText.xml";
	private static final String OUTPUT = "src/main/resources/techs.json";

	// the eras eos models; techs of any later C2C era (Industrial, Atomic, …) are dropped
	private static final Set<String> IN_SCOPE = Set.of(
			"C2C_ERA_PREHISTORIC", "C2C_ERA_ANCIENT", "C2C_ERA_CLASSICAL",
			"C2C_ERA_MEDIEVAL", "C2C_ERA_RENAISSANCE");

	// the one exception kept from beyond the ceiling: the Industrial entry tech, retained
	// as the tree's visual end-cap (where the Renaissance leads). The engine still drops it
	// at load (its era is past MAX_TECH_ERA), so only the web tech-tree view shows it.
	private static final String CAP = "TECH_INDUSTRIAL_LIFESTYLE";

	// the religion-founding techs (and Clockpunk) are dropped: eos has no religion-as-tech
	// model, and C2C keeps their display names outside the tech localization file anyway.
	// They are leaves in the graph (no kept tech depends on them), so dropping is clean.
	private static final Set<String> DROP = Set.of(
			"TECH_SHAMANISM", "TECH_DRUIDIC_TRADITIONS", "TECH_TENGRIISM",
			"TECH_MESOPOTAMISM", "TECH_KEMETISM", "TECH_YORUBA", "TECH_SHINTO",
			"TECH_NGAIISM", "TECH_ANDEANISM", "TECH_ZOROASTRIANISM", "TECH_CANAANISM",
			"TECH_HINDUISM", "TECH_JUDAISM", "TECH_BUDDHISM", "TECH_HELLENISM",
			"TECH_NAGHUALISM", "TECH_CONFUCIANISM", "TECH_TAOISM", "TECH_CHRISTIANITY",
			"TECH_JAINISM", "TECH_ASATRU", "TECH_RODNOVERA", "TECH_ISLAM", "TECH_VOODOO",
			"TECH_SIKHISM", "TECH_CLOCKPUNK");

	private TechInfoConverter() {
	}

	public static void main(String[] args) throws Exception {
		Map<String, String> english = loadEnglish(parse(TEXT_XML));
		System.out.println("Loaded " + english.size() + " localized English strings");

		Document doc = parse(TECH_XML);
		NodeList all = doc.getElementsByTagName("TechInfo");
		List<Map<String, Object>> out = new ArrayList<>();
		Map<String, Integer> perEra = new TreeMap<>();
		Set<String> droppedNames = new java.util.HashSet<>();
		int dropped = 0, unresolvedName = 0;

		for (int i = 0; i < all.getLength(); i++) {
			Element tech = (Element) all.item(i);
			String type = directText(tech, "Type");
			String era = directText(tech, "Era");
			if (era == null || (!IN_SCOPE.contains(era) && !CAP.equals(type))) {
				dropped++;
				continue;
			}
			if (DROP.contains(type)) {
				droppedNames.add(type);
				continue;
			}
			// skip disabled placeholders (e.g. TECH_DUMMY, bDisable=1) — they can't be
			// researched and exist only as an unmeetable prerequisite marker in C2C
			if ("1".equals(directText(tech, "bDisable")))
				continue;

			// faithful transliteration of every field, in document order
			@SuppressWarnings("unchecked")
			Map<String, Object> raw = (Map<String, Object>) toJson(tech);

			// re-emit with the resolved English strings hoisted next to the id, so the
			// display fields lead and the rest of the Civ4 fields follow unchanged
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("Type", raw.remove("Type"));
			String name = english.get(raw.get("Description"));
			String help = english.get(raw.get("Civilopedia"));
			String quote = english.get(raw.get("Quote"));
			if (name != null)
				row.put("name", name);
			else
				unresolvedName++;
			if (help != null)
				row.put("help", help);
			if (quote != null)
				row.put("quote", quote);
			row.putAll(raw);

			out.add(row);
			perEra.merge(era, 1, Integer::sum);
		}

		new ObjectMapper().writerWithDefaultPrettyPrinter()
				.writeValue(new File(OUTPUT), out);

		System.out.println("Wrote " + out.size() + " techs to " + OUTPUT
				+ " (dropped " + dropped + " out-of-scope era, "
				+ droppedNames.size() + " religion/clockpunk)");
		perEra.forEach((e, n) -> System.out.println("  " + e + ": " + n));
		Set<String> notFound = new java.util.TreeSet<>(DROP);
		notFound.removeAll(droppedNames);
		if (!notFound.isEmpty())
			System.out.println("  WARNING: drop-set entries not present in source "
					+ "(renamed upstream?): " + notFound);
		if (unresolvedName > 0)
			System.out.println("  WARNING: " + unresolvedName
					+ " techs had no English name in " + TEXT_XML);
	}

	/**
	 * Build the {@code TXT_KEY_* → English} map from the localization document. The
	 * English text is normally the direct text of {@code <English>}; some entries wrap
	 * it in a {@code <Text>} child (the gendered/plural form), which we unwrap.
	 */
	private static Map<String, String> loadEnglish(Document doc) {
		Map<String, String> map = new HashMap<>();
		NodeList texts = doc.getElementsByTagName("TEXT");
		for (int i = 0; i < texts.getLength(); i++) {
			Element t = (Element) texts.item(i);
			String tag = directText(t, "Tag");
			if (tag == null)
				continue;
			Element en = directChild(t, "English");
			if (en == null)
				continue;
			Element inner = directChild(en, "Text");
			String val = (inner != null ? inner.getTextContent() : en.getTextContent());
			if (val != null)
				map.put(tag, val.trim());
		}
		return map;
	}

	/**
	 * Generic DOM &rarr; JSON value: a leaf element (no child elements) becomes its
	 * trimmed text; an element with children becomes an ordered map keyed by child tag,
	 * with a repeated tag collapsed to a list (so {@code <Flavors>} &rarr;
	 * {@code {Flavor: [...]}}, a lone {@code <PrereqTech>} &rarr; a string, several
	 * &rarr; a list) — the same shape the prior file used and {@link
	 * com.civstudio.tech.TechTree} tolerates.
	 */
	private static Object toJson(Element e) {
		List<Element> kids = elementChildren(e);
		if (kids.isEmpty())
			return e.getTextContent().trim();
		Map<String, Object> map = new LinkedHashMap<>();
		for (Element k : kids) {
			String tag = k.getNodeName();
			Object val = toJson(k);
			if (!map.containsKey(tag)) {
				map.put(tag, val);
			} else {
				Object existing = map.get(tag);
				List<Object> list;
				if (existing instanceof List<?> l) {
					@SuppressWarnings("unchecked")
					List<Object> cast = (List<Object>) l;
					list = cast;
				} else {
					list = new ArrayList<>();
					list.add(existing);
					map.put(tag, list);
				}
				list.add(val);
			}
		}
		return map;
	}

	private static List<Element> elementChildren(Element parent) {
		List<Element> out = new ArrayList<>();
		for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling())
			if (n.getNodeType() == Node.ELEMENT_NODE)
				out.add((Element) n);
		return out;
	}

	private static Element directChild(Element parent, String tag) {
		for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling())
			if (n.getNodeType() == Node.ELEMENT_NODE && tag.equals(n.getNodeName()))
				return (Element) n;
		return null;
	}

	private static String directText(Element parent, String tag) {
		Element c = directChild(parent, tag);
		return c == null ? null : c.getTextContent().trim();
	}

	private static Document parse(String path) {
		try {
			// namespace-unaware so literal tag names match the files' default
			// x-schema:/firaxis namespaces transparently (as Civ4Xml does)
			DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
			DocumentBuilder b = f.newDocumentBuilder();
			return b.parse(new File(path));
		} catch (Exception e) {
			throw new IllegalStateException("failed to parse " + path, e);
		}
	}
}
