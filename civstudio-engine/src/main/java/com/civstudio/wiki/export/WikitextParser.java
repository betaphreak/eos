package com.civstudio.wiki.export;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses MediaWiki wikitext into the pieces the {@link WikiExporter} structures: the leading
 * {@code {{Infobox}}} template (name + named params), the {@code [[wikilink]]} graph, redirect
 * targets, and a cleaned-markdown rendering of the body prose. Pure functions, no I/O.
 * <p>
 * The two hard parts are brace-matching (params/links nest, so a naive split on {@code |} is wrong)
 * and markdown cleanup (strip templates/refs/file-links, unwrap links, convert headings + emphasis).
 * This is the P0 slice — good enough to validate infobox extraction and body readability on a sample;
 * P1/P2 harden it against the long tail.
 */
public final class WikitextParser {

	private WikitextParser() {
	}

	/** Infobox template names whose first-template match we treat as the page's structured header. */
	private static final Set<String> INFOBOX_NAMES = Set.of(
			"country", "character", "location", "race", "culture", "religion", "deity", "dynasty",
			"river", "organisation", "organization", "company", "event", "war");

	private static final Pattern REDIRECT = Pattern.compile(
			"(?is)^\\s*#redirect\\s*\\[\\[\\s*([^\\]|#]+)");
	private static final Pattern HTML_COMMENT = Pattern.compile("(?s)<!--.*?-->");
	private static final Pattern REF_TAG = Pattern.compile("(?is)<ref[^>]*?/>|<ref[^>]*?>.*?</ref>");
	private static final Pattern ANY_TAG = Pattern.compile("(?s)<[^>]+>");
	private static final Pattern HEADING = Pattern.compile("(?m)^(={2,6})\\s*(.*?)\\s*\\1\\s*$");
	private static final Pattern BOLD = Pattern.compile("'''(.*?)'''");
	private static final Pattern ITALIC = Pattern.compile("''(.*?)''");
	private static final Pattern MULTINEWLINE = Pattern.compile("\\n{3,}");

	/** A parsed infobox: the template name (as written) and its named params, in source order. */
	public record Infobox(String template, Map<String, String> params) {
	}

	/** The redirect target title, if this page is a {@code #REDIRECT}. */
	public static Optional<String> redirectTarget(String wikitext) {
		Matcher m = REDIRECT.matcher(wikitext);
		return m.find() ? Optional.of(m.group(1).trim()) : Optional.empty();
	}

	public static boolean isRedirect(String wikitext) {
		return redirectTarget(wikitext).isPresent();
	}

	/**
	 * The first infobox-like template in the text (name in {@link #INFOBOX_NAMES}, or beginning
	 * "Infobox"), with its named params. Positional params are stored under {@code "1"}, {@code "2"}, …
	 */
	public static Optional<Infobox> firstInfobox(String wikitext) {
		int i = 0;
		while ((i = wikitext.indexOf("{{", i)) >= 0) {
			int end = matchBraces(wikitext, i);
			if (end < 0)
				break;
			String inner = wikitext.substring(i + 2, end - 2);
			List<String> parts = splitTop(inner, '|');
			String name = parts.get(0).trim();
			String key = name.toLowerCase(Locale.ROOT);
			if (INFOBOX_NAMES.contains(key) || key.startsWith("infobox")) {
				Map<String, String> params = new LinkedHashMap<>();
				int positional = 0;
				for (int p = 1; p < parts.size(); p++) {
					String seg = parts.get(p);
					List<String> kv = splitTop(seg, '=');
					if (kv.size() >= 2)
						params.put(kv.get(0).trim(), String.join("=", kv.subList(1, kv.size())).trim());
					else
						params.put(String.valueOf(++positional), seg.trim());
				}
				return Optional.of(new Infobox(name, params));
			}
			i = end;
		}
		return Optional.empty();
	}

	/** All distinct {@code [[Target]]} / {@code [[Target|display]]} link targets, in first-seen order. */
	public static List<String> links(String wikitext) {
		List<String> out = new ArrayList<>();
		int i = 0;
		while ((i = wikitext.indexOf("[[", i)) >= 0) {
			int end = wikitext.indexOf("]]", i + 2);
			if (end < 0)
				break;
			String inner = wikitext.substring(i + 2, end);
			String target = splitTop(inner, '|').get(0).trim();
			int hash = target.indexOf('#');
			if (hash >= 0)
				target = target.substring(0, hash).trim(); // drop section anchors
			if (!target.isEmpty() && !isMediaOrMeta(target) && !out.contains(target))
				out.add(target);
			i = end + 2;
		}
		return out;
	}

	/**
	 * Wikitext → cleaned markdown for in-game display: strip all templates, refs, comments and
	 * file/category links; unwrap ordinary links to their display text; convert headings and emphasis.
	 */
	public static String toMarkdown(String wikitext) {
		String s = HTML_COMMENT.matcher(wikitext).replaceAll("");
		s = REF_TAG.matcher(s).replaceAll("");
		s = stripTemplates(s);
		s = unwrapLinks(s);
		s = HEADING.matcher(s).replaceAll(mr -> "#".repeat(mr.group(1).length()) + " "
				+ Matcher.quoteReplacement(mr.group(2)));
		s = BOLD.matcher(s).replaceAll("**$1**");
		s = ITALIC.matcher(s).replaceAll("*$1*");
		s = ANY_TAG.matcher(s).replaceAll(""); // leftover <br>, <gallery>, …
		s = MULTINEWLINE.matcher(s).replaceAll("\n\n");
		return s.strip();
	}

	/** The lead paragraph (first non-empty, non-heading block) of a cleaned-markdown body. */
	public static String summary(String markdown) {
		for (String block : markdown.split("\n\n")) {
			String b = block.strip();
			if (!b.isEmpty() && !b.startsWith("#"))
				return b;
		}
		return "";
	}

	// ---- internals -------------------------------------------------------------------------------

	// Remove every {{…}} template (brace-matched, so nested templates go too).
	private static String stripTemplates(String s) {
		StringBuilder out = new StringBuilder(s.length());
		int i = 0;
		while (i < s.length()) {
			if (i + 1 < s.length() && s.charAt(i) == '{' && s.charAt(i + 1) == '{') {
				int end = matchBraces(s, i);
				if (end < 0) {
					out.append(s.substring(i));
					break;
				}
				i = end; // skip the whole template
			} else {
				out.append(s.charAt(i++));
			}
		}
		return out.toString();
	}

	// [[a|b]] → b ; [[a]] → a ; [[File:/Category:/Image:…]] → removed.
	private static String unwrapLinks(String s) {
		StringBuilder out = new StringBuilder(s.length());
		int i = 0;
		while (i < s.length()) {
			if (i + 1 < s.length() && s.charAt(i) == '[' && s.charAt(i + 1) == '[') {
				int end = s.indexOf("]]", i + 2);
				if (end < 0) {
					out.append(s.substring(i));
					break;
				}
				String inner = s.substring(i + 2, end);
				List<String> parts = splitTop(inner, '|');
				String target = parts.get(0).trim();
				if (!isMediaOrMeta(target)) {
					String display = parts.get(parts.size() - 1).trim();
					out.append(display);
				}
				i = end + 2;
			} else {
				out.append(s.charAt(i++));
			}
		}
		return out.toString();
	}

	private static boolean isMediaOrMeta(String target) {
		String t = target.toLowerCase(Locale.ROOT);
		return t.startsWith("file:") || t.startsWith("image:") || t.startsWith("category:")
				|| t.startsWith("media:");
	}

	// Index just past the '}}' that closes the '{{' at `open`, or -1 if unbalanced.
	private static int matchBraces(String s, int open) {
		int depth = 0;
		for (int i = open; i < s.length() - 1; i++) {
			char c = s.charAt(i), n = s.charAt(i + 1);
			if (c == '{' && n == '{') {
				depth++;
				i++;
			} else if (c == '}' && n == '}') {
				depth--;
				i++;
				if (depth == 0)
					return i + 1;
			}
		}
		return -1;
	}

	// Split on `delim` only at top level — not inside nested {{…}} or [[…]].
	static List<String> splitTop(String s, char delim) {
		List<String> parts = new ArrayList<>();
		int curly = 0, square = 0;
		StringBuilder cur = new StringBuilder();
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i), n = i + 1 < s.length() ? s.charAt(i + 1) : '\0';
			if (c == '{' && n == '{') {
				curly++;
				cur.append("{{");
				i++;
			} else if (c == '}' && n == '}') {
				if (curly > 0)
					curly--;
				cur.append("}}");
				i++;
			} else if (c == '[' && n == '[') {
				square++;
				cur.append("[[");
				i++;
			} else if (c == ']' && n == ']') {
				if (square > 0)
					square--;
				cur.append("]]");
				i++;
			} else if (c == delim && curly == 0 && square == 0) {
				parts.add(cur.toString());
				cur.setLength(0);
			} else {
				cur.append(c);
			}
		}
		parts.add(cur.toString());
		return parts;
	}
}
