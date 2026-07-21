package com.civstudio.wiki.export;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import com.civstudio.data.Exports;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Dev tool — <b>P1+P2</b> of the wiki lore import (see {@code docs/wiki-lore-import-plan.md}). Walks the
 * whole Anbennar Fandom mainspace via {@link WikiFiles}, parses each article with {@link WikitextParser}
 * (infobox + {@code [[link]]} graph + categories + wikitext→cleaned markdown), <b>classifies</b> it into
 * an {@code entityType} (from the infobox template, falling back to categories), and <b>correlates</b> the
 * correlatable types to a canonical engine entity by a normalized name-join ({@link WikiNames}) against
 * the committed world-bundle fixture: country/culture/religion → their key, location → a province id,
 * region → a region/super-region key. The result is the committed {@code wiki-article.json.gz} the
 * {@code wiki-article} Strapi type is seeded from, plus an {@code _unmatched-correlation.json} report.
 *
 * <pre>
 * mvn -pl civstudio-engine compile exec:exec -Dsim.main=com.civstudio.wiki.export.WikiArticleExporter
 * </pre>
 */
public final class WikiArticleExporter {

	// Committed exporter output (like the GeoNames subset) — written straight to src/main/resources, NOT
	// the target/generated/ scratch the other exporters use, so a clean checkout / CI can seed the lore
	// without re-scraping the wiki. Gzipped: 2509 articles of prose is ~11 MB raw, ~3.5 MB compressed.
	private static final String OUTPUT = "civstudio-engine/src/main/resources/wiki/wiki-article.json.gz";
	private static final String UNMATCHED = "civstudio-engine/target/generated/wiki/_unmatched-correlation.json";
	// network-free correlation source (the committed test fixture carries every canonical entity)
	private static final String FIXTURE = "civstudio-engine/src/test/resources/world-bundle.json.gz";
	private static final String WIKI_BASE = "https://anbennar.fandom.com/wiki/";
	private static final int BATCH = 50; // MediaWiki's cap for a multi-title query

	// entityType → the canonical collection its name is joined against (others aren't correlatable)
	private static final Map<String, String> CORRELATABLE = Map.of(
			"COUNTRY", "country", "CULTURE", "culture", "RELIGION", "religion",
			"LOCATION", "province", "REGION", "region");

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private WikiArticleExporter() {
	}

	/** One imported wiki article — the flat base row (typed + correlated; relations are seeded from it). */
	public record ArticleRow(String key, String title, long pageId, String url, String template,
			String entityType, String entityRef, String entityKey, boolean isStub, String summary,
			String body, List<String> categories, List<String> links, Map<String, String> infobox) {
	}

	public static void main(String[] args) throws Exception {
		System.setOut(new java.io.PrintStream(System.out, true, StandardCharsets.UTF_8));
		System.out.println("WikiArticleExporter P1+P2 — snapshot " + WikiFiles.snapshot());

		Map<String, Map<String, String>> canon = loadCanon();
		System.out.printf("loaded canonical indexes: %d countries, %d cultures, %d religions, %d regions, "
				+ "%d super-regions, %d provinces%n",
				canon.get("country").size(), canon.get("culture").size(), canon.get("religion").size(),
				canon.get("region").size(), canon.get("super-region").size(), canon.get("province").size());

		List<String> titles = allArticleTitles();
		System.out.println("enumerated " + titles.size() + " mainspace articles");

		List<ArticleRow> rows = new ArrayList<>(titles.size());
		int redirects = 0, batches = 0;
		for (int i = 0; i < titles.size(); i += BATCH) {
			List<String> slice = titles.subList(i, Math.min(i + BATCH, titles.size()));
			for (JsonNode p : fetchPages(slice)) {
				String content = p.path("revisions").path(0).path("slots").path("main").path("*")
						.asText(null);
				if (content == null)
					continue;
				if (WikitextParser.isRedirect(content)) {
					redirects++;
					continue;
				}
				rows.add(toRow(p.path("title").asText(), p.path("pageid").asLong(), content, canon));
			}
			if (++batches % 10 == 0)
				System.out.println("  ...parsed " + rows.size() + " articles");
		}
		rows.sort((a, b) -> a.key().compareTo(b.key())); // stable natural-key order for a deterministic file

		File out = Exports.outFile(OUTPUT);
		// compact (not pretty) under gzip — the file is machine-read by the seeder, never eyeballed
		try (var os = new GZIPOutputStream(new java.io.FileOutputStream(out))) {
			MAPPER.writeValue(os, rows);
		}

		report(rows, redirects, out);
	}

	private static ArticleRow toRow(String title, long pageId, String wikitext,
			Map<String, Map<String, String>> canon) {
		Optional<WikitextParser.Infobox> box = WikitextParser.firstInfobox(wikitext);
		String template = box.map(WikitextParser.Infobox::template).orElse(null);
		String body = WikitextParser.toMarkdown(wikitext);
		List<String> categories = WikitextParser.categories(wikitext);

		String entityType = classify(template, categories);
		String[] hit = correlate(entityType, title, canon); // {ref, key} or {null, null}

		return new ArticleRow(
				title.replace(' ', '_'), title, pageId, WIKI_BASE + enc(title.replace(' ', '_')),
				template, entityType, hit[0], hit[1],
				categories.contains("Stub") || categories.contains("Stubs"),
				WikitextParser.summary(body), body, categories, WikitextParser.links(wikitext),
				box.map(WikitextParser.Infobox::params).orElseGet(Map::of));
	}

	// ---- classification + correlation ------------------------------------------------------------

	/**
	 * Classify an article into an entityType from its infobox template (normalized, so
	 * {@code Country}/{@code Infobox country}/{@code Infobox_country} all fold to COUNTRY), falling back to
	 * category membership for the types with no infobox template (religions, regions).
	 */
	static String classify(String template, List<String> categories) {
		String t = template == null ? "" : template.toLowerCase(Locale.ROOT)
				.replace('_', ' ').replace("infobox", "").trim();
		switch (t) {
			case "country": return "COUNTRY";
			case "character": return "CHARACTER";
			case "location": return "LOCATION";
			case "race": return "RACE";
			case "culture": return "CULTURE";
			case "deity": return "DEITY";
			case "dynasty": return "DYNASTY";
			case "organization", "organisation", "company": return "ORGANIZATION";
			case "river": return "RIVER";
			case "event", "military conflict": return "EVENT";
			default: break; // fall through to category inference
		}
		Set<String> cats = new HashSet<>(categories);
		if (cats.contains("Deity")) return "DEITY";
		if (cats.contains("Religion")) return "RELIGION";
		if (cats.contains("Culture")) return "CULTURE";
		if (cats.contains("Races")) return "RACE";
		if (cats.contains("Region")) return "REGION";
		if (cats.contains("Cities") || cats.contains("Locations")) return "LOCATION";
		if (cats.contains("Wars and Conflicts")) return "EVENT";
		if (cats.contains("Companies") || cats.contains("Organisations")) return "ORGANIZATION";
		if (cats.contains("Dynasty")) return "DYNASTY";
		if (cats.contains("People")) return "CHARACTER";
		if (cats.contains("Countries")) return "COUNTRY";
		return "ARTICLE"; // untyped fallback
	}

	/** {ref, key} of the canonical entity this article correlates to, or {null, null} (lore-only). */
	private static String[] correlate(String entityType, String title,
			Map<String, Map<String, String>> canon) {
		String ref = CORRELATABLE.get(entityType);
		if (ref == null)
			return new String[] { null, null };
		String key = WikiNames.match(title, canon.get(ref));
		if (key == null && ref.equals("region")) { // a wiki "region" may be an engine super-region
			key = WikiNames.match(title, canon.get("super-region"));
			if (key != null)
				return new String[] { "super-region", key };
		}
		return key == null ? new String[] { null, null } : new String[] { ref, key };
	}

	private static Map<String, Map<String, String>> loadCanon() throws IOException {
		Map<String, Map<String, String>> canon = new HashMap<>();
		try (InputStream in = new GZIPInputStream(Files.newInputStream(Path.of(FIXTURE)))) {
			JsonNode res = MAPPER.readTree(in).path("resources");
			canon.put("country", index(res.path("/map/countries.json"), "tag"));
			canon.put("culture", index(res.path("/map/cultures.json"), "key"));
			canon.put("religion", index(res.path("/map/religions.json"), "key"));
			canon.put("region", index(res.path("/map/regions.json"), "key"));
			canon.put("super-region", index(res.path("/map/superregions.json"), "key"));
			canon.put("province", index(res.path("/map/provinces.json"), "id"));
		}
		return canon;
	}

	// a norm(name) → key index over a canonical dataset (name is the display field, key the natural key)
	private static Map<String, String> index(JsonNode arr, String keyField) {
		Map<String, String> m = new LinkedHashMap<>();
		for (JsonNode r : arr) {
			String name = r.path("name").asText(null);
			if (name != null && !name.isBlank())
				m.putIfAbsent(WikiNames.norm(name), r.path(keyField).asText());
		}
		return m;
	}

	// ---- fetch -----------------------------------------------------------------------------------

	// every non-redirect mainspace (ns 0) article title, following apcontinue pagination
	private static List<String> allArticleTitles() throws IOException {
		List<String> titles = new ArrayList<>();
		String cont = "";
		do {
			String json = WikiFiles.api("action=query&list=allpages&apnamespace=0"
					+ "&apfilterredir=nonredirects&aplimit=500" + cont);
			JsonNode root = MAPPER.readTree(json);
			for (JsonNode p : root.path("query").path("allpages"))
				titles.add(p.path("title").asText());
			JsonNode c = root.path("continue").path("apcontinue");
			cont = c.isMissingNode() ? "" : "&apcontinue=" + enc(c.asText());
		} while (!cont.isEmpty());
		return titles;
	}

	// the page objects (title, pageid, current wikitext) for a batch of ≤50 titles
	private static List<JsonNode> fetchPages(List<String> slice) throws IOException {
		String json = WikiFiles.api("action=query&prop=revisions&rvprop=content&rvslots=main&titles="
				+ enc(String.join("|", slice)));
		List<JsonNode> pages = new ArrayList<>();
		for (JsonNode p : MAPPER.readTree(json).path("query").path("pages"))
			pages.add(p);
		return pages;
	}

	// ---- reporting -------------------------------------------------------------------------------

	private static void report(List<ArticleRow> rows, int redirects, File out) throws IOException {
		// per-entityType: total + (for correlatable types) how many matched a canonical key
		Map<String, int[]> byType = new TreeMap<>(); // entityType → {total, matched}
		List<Map<String, String>> unmatched = new ArrayList<>();
		int stubs = 0, withInfobox = 0;
		for (ArticleRow r : rows) {
			if (r.isStub())
				stubs++;
			if (r.template() != null)
				withInfobox++;
			int[] c = byType.computeIfAbsent(r.entityType(), k -> new int[2]);
			c[0]++;
			if (CORRELATABLE.containsKey(r.entityType())) {
				if (r.entityKey() != null)
					c[1]++;
				else
					unmatched.add(Map.of("title", r.title(), "entityType", r.entityType()));
			}
		}
		MAPPER.writerWithDefaultPrettyPrinter().writeValue(Exports.outFile(UNMATCHED), unmatched);

		System.out.printf("%nwrote %d articles (%d redirects skipped, %d stubs, %d with an infobox) to %s%n",
				rows.size(), redirects, stubs, withInfobox, out.getPath());
		System.out.println("entityType  (matched/total for correlatable):");
		byType.entrySet().stream()
				.sorted((a, b) -> Integer.compare(b.getValue()[0], a.getValue()[0]))
				.forEach(e -> {
					int[] c = e.getValue();
					String rate = CORRELATABLE.containsKey(e.getKey())
							? String.format("  %d/%d matched (%.0f%%)", c[1], c[0],
									c[0] == 0 ? 0.0 : 100.0 * c[1] / c[0])
							: "";
					System.out.printf("  %-14s %5d%s%n", e.getKey(), c[0], rate);
				});
		System.out.println("wrote " + unmatched.size() + " unmatched correlatable articles to "
				+ UNMATCHED);
	}

	private static String enc(String s) {
		return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
	}
}
