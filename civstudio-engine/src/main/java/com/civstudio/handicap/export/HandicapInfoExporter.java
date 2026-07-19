package com.civstudio.handicap.export;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import com.civstudio.data.Civ4Files;
import com.civstudio.handicap.Handicap;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Dev-time exporter: fetch {@code CIV4HandicapInfo.xml} from C2C (via {@link Civ4Files}, on demand)
 * and bake the committed {@code /handicaps.json} the {@link com.civstudio.handicap.HandicapCatalog}
 * reads. The sibling of {@code UnitInfoExporter} / {@code TechInfoExporter} — see
 * {@code docs/session-management.md} §Two difficulty selectors.
 * <p>
 * Re-run after bumping the C2C lock, or to pick up new handicap levels:
 *
 * <pre>{@code
 * mvn -pl civstudio-engine exec:java \
 *   -Dexec.mainClass=com.civstudio.handicap.export.HandicapInfoExporter
 * }</pre>
 *
 * Captures each handicap's {@code Type}, its {@code Description} {@code TXT_KEY}, and every integer
 * {@code i*} field (the scaling table) — enough to apply the multipliers when that feature lands; only
 * the keys are consumed today (difficulty validation).
 */
public final class HandicapInfoExporter {

	private HandicapInfoExporter() {
	}

	/** Where the baked catalog is written (committed engine resource). */
	private static final Path OUT = Path.of("civstudio-engine", "src", "main", "resources", "handicaps.json");

	// Civ4's balanced middle rung — mirrored as HandicapCatalog.DEFAULT_KEY
	private static final String DEFAULT_KEY = "noble";

	public static void main(String[] args) throws Exception {
		Path out = args.length > 0 ? Path.of(args[0]) : OUT;
		List<Handicap> handicaps = parse();
		ObjectMapper json = new ObjectMapper();
		ObjectNode root = json.createObjectNode();
		root.put("_source", "CIV4HandicapInfo.xml @ C2C " + Civ4Files.ref());
		root.put("default", DEFAULT_KEY);
		ArrayNode arr = root.putArray("handicaps");
		for (Handicap h : handicaps) {
			ObjectNode n = arr.addObject();
			n.put("type", h.type());
			n.put("key", h.key());
			n.put("description", h.description());
			ObjectNode mods = n.putObject("modifiers");
			h.modifiers().forEach((k, v) -> mods.put(k, v.intValue()));
		}
		Files.createDirectories(out.getParent());
		json.writerWithDefaultPrettyPrinter().writeValue(out.toFile(), root);
		System.out.println("wrote " + handicaps.size() + " handicaps to " + out.toAbsolutePath());
	}

	/** Parse the handicap infos from the C2C XML, in file (ladder) order. */
	public static List<Handicap> parse() throws Exception {
		DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
		DocumentBuilder b = f.newDocumentBuilder();
		Document doc = b.parse(Civ4Files.get("CIV4HandicapInfo.xml").toFile());
		List<Handicap> out = new ArrayList<>();
		NodeList infos = doc.getElementsByTagName("HandicapInfo");
		for (int i = 0; i < infos.getLength(); i++) {
			Element e = (Element) infos.item(i);
			String type = childText(e, "Type");
			if (type == null || type.isBlank())
				continue;
			String description = childText(e, "Description");
			Map<String, Integer> mods = new LinkedHashMap<>();
			NodeList children = e.getChildNodes();
			for (int c = 0; c < children.getLength(); c++) {
				Node ch = children.item(c);
				if (ch.getNodeType() != Node.ELEMENT_NODE)
					continue;
				String tag = ch.getNodeName();
				// only the integer i* scalars — skip Type/Description/Help and nested blocks
				// (PropertyManipulators etc.), whose text is not a number
				if (!(tag.length() > 1 && tag.charAt(0) == 'i' && Character.isUpperCase(tag.charAt(1))))
					continue;
				String txt = ch.getTextContent();
				Integer v = parseInt(txt);
				if (v != null)
					mods.put(tag, v);
			}
			out.add(new Handicap(type, Handicap.keyOf(type), description, mods));
		}
		return out;
	}

	// the text of the first direct child element with this tag, or null
	private static String childText(Element parent, String tag) {
		NodeList kids = parent.getElementsByTagName(tag);
		return kids.getLength() == 0 ? null : kids.item(0).getTextContent();
	}

	private static Integer parseInt(String s) {
		if (s == null)
			return null;
		String t = s.trim();
		if (t.isEmpty())
			return null;
		try {
			return Integer.valueOf(t);
		} catch (NumberFormatException e) {
			return null;
		}
	}
}
