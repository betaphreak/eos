package com.civstudio.wiki.export;

import java.io.File;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import com.civstudio.data.Exports;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Dev tool — <b>P1</b> of the wiki lore import (see {@code docs/wiki-lore-import-plan.md}). Walks the
 * <em>entire</em> Anbennar Fandom mainspace via {@link WikiFiles}, parses each article with
 * {@link WikitextParser} (infobox + {@code [[link]]} graph + categories + wikitext→cleaned markdown), and
 * emits the flat base {@code wiki-article.json} dataset that the {@code wiki-article} Strapi type is
 * seeded from. No correlation or typed subtypes yet — that is P2, a projection over this base data.
 * <p>
 * This slice sources from the MediaWiki API (paginated, cached under {@code .wiki-cache}); swapping in the
 * {@code Special:Statistics} {@code .7z} dump as the bulk source-of-record is a later change behind the
 * same {@link WikiFiles} seam. Redirects and empty pages are dropped; {@code Stub}-category pages are kept
 * but flagged.
 *
 * <pre>
 * mvn -pl civstudio-engine compile exec:exec -Dsim.main=com.civstudio.wiki.export.WikiArticleExporter
 * </pre>
 */
public final class WikiArticleExporter {

	// gzipped — 2509 articles of prose is ~11 MB raw but compresses to ~2-3 MB; the seeder gunzips it
	private static final String OUTPUT = "civstudio-engine/target/generated/wiki/wiki-article.json.gz";
	private static final String WIKI_BASE = "https://anbennar.fandom.com/wiki/";
	private static final int BATCH = 50; // MediaWiki's cap for a multi-title query

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private WikiArticleExporter() {
	}

	/** One imported wiki article — the flat base row (typed subtypes are projected from this in P2). */
	public record ArticleRow(String key, String title, long pageId, String url, String template,
			boolean isStub, String summary, String body, List<String> categories, List<String> links,
			Map<String, String> infobox) {
	}

	public static void main(String[] args) throws Exception {
		System.setOut(new java.io.PrintStream(System.out, true, StandardCharsets.UTF_8));
		System.out.println("WikiArticleExporter P1 — snapshot " + WikiFiles.snapshot());

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
				rows.add(toRow(p.path("title").asText(), p.path("pageid").asLong(), content));
			}
			if (++batches % 10 == 0)
				System.out.println("  ...parsed " + rows.size() + " articles");
		}
		rows.sort((a, b) -> a.key().compareTo(b.key())); // stable natural-key order for a deterministic file

		File out = Exports.outFile(OUTPUT);
		// compact (not pretty) under gzip — the file is machine-read by the seeder, never eyeballed
		try (var os = new java.util.zip.GZIPOutputStream(new java.io.FileOutputStream(out))) {
			MAPPER.writeValue(os, rows);
		}

		report(rows, redirects, out);
	}

	private static ArticleRow toRow(String title, long pageId, String wikitext) {
		Optional<WikitextParser.Infobox> box = WikitextParser.firstInfobox(wikitext);
		String body = WikitextParser.toMarkdown(wikitext);
		List<String> categories = WikitextParser.categories(wikitext);
		return new ArticleRow(
				title.replace(' ', '_'), title, pageId, WIKI_BASE + enc(title.replace(' ', '_')),
				box.map(WikitextParser.Infobox::template).orElse(null),
				categories.contains("Stub") || categories.contains("Stubs"),
				WikitextParser.summary(body), body, categories, WikitextParser.links(wikitext),
				box.map(WikitextParser.Infobox::params).orElseGet(Map::of));
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

	private static void report(List<ArticleRow> rows, int redirects, File out) {
		int stubs = 0, withInfobox = 0;
		Map<String, Integer> templates = new TreeMap<>();
		for (ArticleRow r : rows) {
			if (r.isStub())
				stubs++;
			if (r.template() != null) {
				withInfobox++;
				templates.merge(r.template(), 1, Integer::sum);
			}
		}
		System.out.printf("%nwrote %d articles (%d redirects skipped, %d stubs, %d with an infobox) to %s%n",
				rows.size(), redirects, stubs, withInfobox, out.getPath());
		System.out.println("infobox templates:");
		templates.entrySet().stream()
				.sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
				.limit(20)
				.forEach(e -> System.out.printf("  %5d  %s%n", e.getValue(), e.getKey()));
	}

	private static String enc(String s) {
		return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
	}
}
