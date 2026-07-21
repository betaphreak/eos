package com.civstudio.wiki.export;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.zip.GZIPInputStream;

import com.civstudio.data.Exports;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Dev tool — <b>P0 vertical slice</b> of the wiki lore import (see
 * {@code docs/wiki-lore-import-plan.md}). Fetches a sample of {@code Category:Countries} pages from the
 * Anbennar Fandom wiki via {@link WikiFiles}, parses each with {@link WikitextParser} (infobox + body →
 * cleaned markdown), and <b>correlates</b> the page to an existing EU4 {@code country} tag by a
 * normalized name-join against the committed world-bundle fixture. Writes {@code wiki-country.json} plus
 * an {@code _unmatched-countries.json} report, and prints the correlation hit-rate.
 * <p>
 * The goal is to de-risk the parser and the name→tag join before building the full typed model (P1+).
 * This slice uses the API and the test fixture only — no dump, no Strapi, no bundle wiring yet.
 *
 * <pre>
 * mvn -pl civstudio-engine compile exec:exec -Dsim.main=com.civstudio.wiki.export.WikiExporter
 * </pre>
 */
public final class WikiExporter {

	private static final String CATEGORY = "Category:Countries";
	private static final int SAMPLE = 30;
	private static final String OUT_DIR = "civstudio-engine/target/generated/wiki";
	// network-free correlation source for P0 (the committed test fixture already carries every country)
	private static final String FIXTURE = "civstudio-engine/src/test/resources/world-bundle.json.gz";

	// leading government/organisational words to strip so "Kingdom of Lorent" joins country "Lorent"
	private static final List<String> PREFIXES = List.of(
			"kingdom of", "empire of", "grand duchy of", "archduchy of", "duchy of", "county of",
			"barony of", "principality of", "republic of", "free city of", "hold of", "command of",
			"march of", "lordship of", "confederation of", "dominion of", "theocracy of", "sultanate of",
			"emirate of", "khanate of", "great clan of", "clan of", "tribe of", "city of", "state of",
			"union of", "league of", "akalate of", "jaddari", "the");
	// trailing government/organisational words to strip so "Ayarallen Queendom" joins country "Ayarallen"
	private static final List<String> SUFFIXES = List.of(
			"empire", "queendom", "kingdom", "overclan", "confederation", "republic", "clans", "clan",
			"horde", "dominion", "khaganate", "akalate", "legion", "remnant", "state", "league", "union",
			"hold", "command", "march", "principality", "duchy", "county");

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private WikiExporter() {
	}

	/** One correlated wiki country page. */
	public record CountryRow(String key, String title, String tag, boolean matched,
			String capital, String government, String established,
			String summary, String body, List<String> links, Map<String, String> infobox) {
	}

	public static void main(String[] args) throws Exception {
		// the world data carries accented names (š, é, …); force UTF-8 stdout so logs are legible on the
		// Windows console (the emitted JSON is already UTF-8 — this only affects these diagnostics)
		System.setOut(new java.io.PrintStream(System.out, true, StandardCharsets.UTF_8));
		System.out.println("WikiExporter P0 — snapshot " + WikiFiles.snapshot());

		Map<String, String> nameToTag = loadCountryIndex();
		System.out.println("loaded " + nameToTag.size() + " country names from the fixture");

		List<String> all = allCategoryMembers(CATEGORY);
		List<String> titles = stratifiedSample(all, SAMPLE);
		System.out.println(CATEGORY + " has " + all.size() + " members; sampling " + titles.size()
				+ " spread across the list");

		Map<String, String> wikitext = pageContents(titles);

		List<CountryRow> rows = new ArrayList<>();
		List<String> unmatched = new ArrayList<>();
		int redirects = 0;
		for (String title : titles) {
			String wt = wikitext.get(title);
			if (wt == null || WikitextParser.isRedirect(wt)) {
				redirects++;
				continue;
			}
			Optional<WikitextParser.Infobox> box = WikitextParser.firstInfobox(wt);
			Map<String, String> params = box.map(WikitextParser.Infobox::params).orElseGet(Map::of);
			String tag = correlate(title, nameToTag);
			String body = WikitextParser.toMarkdown(wt);
			rows.add(new CountryRow(
					title.replace(' ', '_'), title, tag, tag != null,
					plain(params.get("Capital")), plain(params.get("Government")),
					plain(params.get("Established")),
					WikitextParser.summary(body), body,
					WikitextParser.links(wt), params));
			if (tag == null)
				unmatched.add(title);
		}

		write("wiki-country.json", rows);
		write("_unmatched-countries.json", unmatched);

		int parsed = rows.size();
		int matched = parsed - unmatched.size();
		System.out.printf("%nparsed %d pages (%d redirects skipped); matched %d → tag, %d unmatched (%.0f%% hit-rate)%n",
				parsed, redirects, matched, unmatched.size(),
				parsed == 0 ? 0 : 100.0 * matched / parsed);
		if (!unmatched.isEmpty())
			System.out.println("unmatched: " + unmatched);

		System.out.println("\n--- samples ---");
		rows.stream().limit(4).forEach(r -> System.out.printf(
				"  [%s] %s  cap=%s gov=%s%n    %s%n",
				r.tag() == null ? "  —  " : r.tag(), r.title(), r.capital(), r.government(),
				ellipsize(r.summary(), 180)));
	}

	// ---- fetch -----------------------------------------------------------------------------------

	// every page member of a category, following cmcontinue pagination (validates the paging seam P1 needs)
	private static List<String> allCategoryMembers(String category) throws IOException {
		List<String> titles = new ArrayList<>();
		String cont = "";
		do {
			String json = WikiFiles.api("action=query&list=categorymembers&cmtitle=" + enc(category)
					+ "&cmtype=page&cmlimit=500" + cont);
			JsonNode root = MAPPER.readTree(json);
			for (JsonNode m : root.path("query").path("categorymembers"))
				titles.add(m.path("title").asText());
			JsonNode cn = root.path("continue").path("cmcontinue");
			cont = cn.isMissingNode() ? "" : "&cmcontinue=" + enc(cn.asText());
		} while (!cont.isEmpty());
		return titles;
	}

	// an evenly-spread sample of n titles across the (alphabetical) member list — so the correlation
	// hit-rate isn't skewed by one alphabet-adjacent archetype
	private static List<String> stratifiedSample(List<String> all, int n) {
		if (all.size() <= n)
			return all;
		List<String> out = new ArrayList<>(n);
		for (int i = 0; i < n; i++)
			out.add(all.get((int) ((long) i * all.size() / n)));
		return out;
	}

	// batch-fetch page wikitext, ≤50 titles per request (MediaWiki's cap for a titles query)
	private static Map<String, String> pageContents(List<String> titles) throws IOException {
		Map<String, String> out = new LinkedHashMap<>();
		for (int i = 0; i < titles.size(); i += 50) {
			String joined = String.join("|", titles.subList(i, Math.min(i + 50, titles.size())));
			String json = WikiFiles.api(
					"action=query&prop=revisions&rvprop=content&rvslots=main&titles=" + enc(joined));
			for (JsonNode p : MAPPER.readTree(json).path("query").path("pages")) {
				String title = p.path("title").asText();
				String content = p.path("revisions").path(0).path("slots").path("main").path("*")
						.asText(null);
				if (content != null)
					out.put(title, content);
			}
		}
		return out;
	}

	// ---- correlation -----------------------------------------------------------------------------

	private static Map<String, String> loadCountryIndex() throws IOException {
		Map<String, String> index = new LinkedHashMap<>();
		try (InputStream in = new GZIPInputStream(Files.newInputStream(Path.of(FIXTURE)))) {
			JsonNode countries = MAPPER.readTree(in).path("resources").path("/map/countries.json");
			for (JsonNode c : countries)
				index.putIfAbsent(norm(c.path("name").asText()), c.path("tag").asText());
		}
		return index;
	}

	/**
	 * Normalized name-join: try the whole title, then the title with a leading government word stripped
	 * ("Kingdom of Lorent" → Lorent), then with a trailing one stripped ("Ayarallen Queendom" →
	 * Ayarallen). First candidate that hits the country index wins; null = lore-only (no EU4 tag).
	 */
	private static String correlate(String title, Map<String, String> nameToTag) {
		String n = norm(title);
		for (String cand : candidates(n)) {
			String tag = nameToTag.get(cand);
			if (tag != null)
				return tag;
		}
		return null;
	}

	private static List<String> candidates(String n) {
		List<String> out = new ArrayList<>();
		out.add(n);
		for (String pre : PREFIXES)
			if (n.startsWith(pre + " "))
				out.add(n.substring(pre.length() + 1));
		for (String suf : SUFFIXES)
			if (n.endsWith(" " + suf))
				out.add(n.substring(0, n.length() - suf.length() - 1));
		return out;
	}

	private static String norm(String s) {
		if (s == null)
			return "";
		String x = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
		return x.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim();
	}

	// ---- output / util ---------------------------------------------------------------------------

	private static void write(String name, Object value) throws IOException {
		File out = Exports.outFile(OUT_DIR + "/" + name);
		MAPPER.writerWithDefaultPrettyPrinter().writeValue(out, value);
		System.out.println("wrote " + out.getPath());
	}

	// a wikitext param value → plain text (unwrap links, drop emphasis) for a structured column
	private static String plain(String value) {
		if (value == null)
			return null;
		return WikitextParser.toMarkdown(value).replace("\n", " ").strip();
	}

	private static String ellipsize(String s, int n) {
		s = s.replace("\n", " ");
		return s.length() <= n ? s : s.substring(0, n) + "…";
	}

	private static String enc(String s) {
		return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
	}
}
