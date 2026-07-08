package com.civstudio.geo.export;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Dev-tool helper: a minimal, brace-aware reader for Paradox/Clausewitz {@code
 * key = value} / {@code key = { ... }} files, shared by the political-metadata
 * exporters ({@link CountryExporter}, {@link CultureExporter}, {@link
 * ReligionExporter}). It parses only the <em>top level</em> of a given string —
 * nested blocks are returned as their raw body text for the caller to re-parse,
 * so a key inside a nested block never leaks up. This is deliberately not a full
 * parser (no lists, no operators); it extracts the shallow structure these
 * exporters need.
 */
final class ClausewitzBlocks {

	/** A top-level {@code name = { body }} block; {@code body} is the raw inner text. */
	record Block(String name, String body) {
	}

	/** The top-level structure of a parsed string: its blocks and scalar assignments. */
	record Parsed(List<Block> blocks, Map<String, String> scalars) {
	}

	private ClausewitzBlocks() {
	}

	static String stripComments(String content) {
		return content.replaceAll("#.*", "");
	}

	/**
	 * Parse the top level of {@code s}: collect each {@code name = { body }} block
	 * (nested contents left raw) and each {@code name = scalar} assignment. Braces
	 * are matched so nested blocks are skipped as whole units.
	 */
	static Parsed parse(String s) {
		List<Block> blocks = new ArrayList<>();
		Map<String, String> scalars = new LinkedHashMap<>();
		int i = 0, n = s.length();
		while (i < n) {
			char c = s.charAt(i);
			if (Character.isWhitespace(c) || c == '{' || c == '}') {
				i++;
				continue;
			}
			int start = i;
			while (i < n && !Character.isWhitespace(s.charAt(i))
					&& s.charAt(i) != '=' && s.charAt(i) != '{' && s.charAt(i) != '}')
				i++;
			String token = s.substring(start, i);
			while (i < n && Character.isWhitespace(s.charAt(i)))
				i++;
			if (i >= n || s.charAt(i) != '=')
				continue; // a bare token (list element) — not an assignment
			i++; // consume '='
			while (i < n && Character.isWhitespace(s.charAt(i)))
				i++;
			if (i < n && s.charAt(i) == '{') {
				int depth = 0, bodyStart = i + 1;
				while (i < n) {
					char d = s.charAt(i);
					if (d == '{')
						depth++;
					else if (d == '}' && --depth == 0) {
						i++;
						break;
					}
					i++;
				}
				blocks.add(new Block(token, s.substring(bodyStart, i - 1)));
			} else {
				int vStart = i;
				while (i < n && !Character.isWhitespace(s.charAt(i))
						&& s.charAt(i) != '{' && s.charAt(i) != '}')
					i++;
				scalars.put(token, s.substring(vStart, i));
			}
		}
		return new Parsed(blocks, scalars);
	}

	/** Title-case a {@code raw_key} (e.g. {@code "regent_court"} &rarr; {@code "Regent Court"}). */
	static String titleCase(String key) {
		String[] parts = key.split("_");
		StringBuilder sb = new StringBuilder();
		for (String p : parts) {
			if (p.isEmpty())
				continue;
			if (sb.length() > 0)
				sb.append(' ');
			sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1));
		}
		return sb.length() == 0 ? key : sb.toString();
	}

	/** Parse a {@code color} block body ({@code "r g b"}) to a {@code "#rrggbb"} hex string, or null. */
	static String colorHex(String body) {
		String[] t = body.trim().split("\\s+");
		if (t.length < 3)
			return null;
		try {
			int r = Integer.parseInt(t[0]) & 0xFF;
			int g = Integer.parseInt(t[1]) & 0xFF;
			int b = Integer.parseInt(t[2]) & 0xFF;
			return String.format("#%02x%02x%02x", r, g, b);
		} catch (NumberFormatException e) {
			return null;
		}
	}
}
