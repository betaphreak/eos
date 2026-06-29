package com.civstudio.geo.export;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Small DOM helper shared by the Civ4 data exporters ({@link TerrainExporter} /
 * {@link FeatureExporter} / {@link ImprovementExporter}). The
 * {@code data/CIV4*.xml} files all conform to the same Civ4 info schema
 * ({@code data/C2C_CIV4TerrainSchema.xml}): a list of {@code <*Info>} elements,
 * each a flat bag of scalar tags plus a few nested ordered-yield lists.
 * <p>
 * It reads tags by their literal name (the parser is left namespace-unaware, so
 * the file's default {@code x-schema:} namespace is ignored) and — crucially —
 * scopes every lookup to <em>direct</em> children, because some tags (notably
 * {@code <YieldChanges>}) recur deeper in the tree (e.g. inside an improvement's
 * per-bonus {@code <BonusTypeStruct>}) and {@code getElementsByTagName} would
 * otherwise pick the wrong one.
 */
final class Civ4Xml {

	private Civ4Xml() {
	}

	/** Parse a Civ4 info XML file under {@code data/} into a DOM document. */
	static Document parse(String path) {
		try {
			DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
			// leave namespace-awareness off so literal tag names match the
			// schema's default x-schema: namespace transparently
			DocumentBuilder b = f.newDocumentBuilder();
			return b.parse(new File(path));
		} catch (Exception e) {
			throw new IllegalStateException("failed to parse " + path, e);
		}
	}

	/** Every {@code <infoTag>} element in the document (top-level info records). */
	static List<Element> infos(Document doc, String infoTag) {
		NodeList nl = doc.getElementsByTagName(infoTag);
		List<Element> out = new ArrayList<>(nl.getLength());
		for (int i = 0; i < nl.getLength(); i++)
			out.add((Element) nl.item(i));
		return out;
	}

	/** The direct child elements of {@code parent} with the given tag. */
	static List<Element> children(Element parent, String tag) {
		List<Element> out = new ArrayList<>();
		for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling())
			if (n.getNodeType() == Node.ELEMENT_NODE && tag.equals(n.getNodeName()))
				out.add((Element) n);
		return out;
	}

	/** The first direct child element with the given tag, or {@code null}. */
	static Element child(Element parent, String tag) {
		List<Element> c = children(parent, tag);
		return c.isEmpty() ? null : c.get(0);
	}

	/** Text of the first direct child with the given tag, or {@code null}. */
	static String text(Element parent, String tag) {
		Element e = child(parent, tag);
		return e == null ? null : e.getTextContent().trim();
	}

	/** Integer value of a direct child tag, or {@code def} if absent/blank. */
	static int intVal(Element parent, String tag, int def) {
		String s = text(parent, tag);
		return (s == null || s.isEmpty()) ? def : Integer.parseInt(s);
	}

	/** A Civ4 boolean tag: present and {@code "1"} means true. */
	static boolean boolVal(Element parent, String tag) {
		return "1".equals(text(parent, tag));
	}

	/**
	 * Read an ordered yield list — a direct-child {@code <container>} holding
	 * {@code <item>} ints in Food/Production/Commerce order — into a length-3
	 * array, missing trailing entries padded with 0. Returns all-zero if the
	 * container is absent.
	 */
	static int[] yields(Element parent, String container, String item) {
		int[] out = new int[3];
		Element c = child(parent, container);
		if (c == null)
			return out;
		List<Element> items = children(c, item);
		for (int i = 0; i < items.size() && i < 3; i++) {
			String s = items.get(i).getTextContent().trim();
			out[i] = s.isEmpty() ? 0 : Integer.parseInt(s);
		}
		return out;
	}

	/**
	 * Read the {@code <TerrainType>}/{@code <FeatureType>} of each entry in a
	 * "makes valid" list — a direct-child {@code <container>} of {@code <entry>}
	 * structs each naming a type via {@code <typeTag>} — keeping only the ones
	 * flagged valid via {@code <flagTag>}.
	 */
	static List<String> validTypes(Element parent, String container, String entry,
			String typeTag, String flagTag) {
		List<String> out = new ArrayList<>();
		Element c = child(parent, container);
		if (c == null)
			return out;
		for (Element e : children(c, entry))
			if (boolVal(e, flagTag)) {
				String t = text(e, typeTag);
				if (t != null && !t.isEmpty())
					out.add(t);
			}
		return out;
	}
}
