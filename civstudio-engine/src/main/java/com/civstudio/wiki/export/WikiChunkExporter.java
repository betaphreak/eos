package com.civstudio.wiki.export;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import com.civstudio.data.Exports;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Dev tool — <b>P5 (C1)</b> of the lore chatbot ({@code docs/lore-chatbot-plan.md}). Reads the committed
 * {@code wiki-article.json.gz} corpus and section-chunks every article via {@link WikiChunker} into the
 * retrieval passages the RAG substrate embeds. Emits {@code target/generated/wiki/wiki-chunk.json.gz}
 * (build scratch — chunks are deterministic from the committed article corpus, so they're re-derived by
 * the embedding backfill rather than committed). No external infra: this validates the chunking before
 * the embedding-model / pgvector work.
 *
 * <pre>
 * mvn -pl civstudio-engine compile exec:exec -Dsim.main=com.civstudio.wiki.export.WikiChunkExporter
 * </pre>
 */
public final class WikiChunkExporter {

	private static final String INPUT = "civstudio-engine/src/main/resources/wiki/wiki-article.json.gz";
	private static final String OUTPUT = "civstudio-engine/target/generated/wiki/wiki-chunk.json.gz";

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private WikiChunkExporter() {
	}

	public static void main(String[] args) throws Exception {
		System.setOut(new java.io.PrintStream(System.out, true, StandardCharsets.UTF_8));

		JsonNode articles;
		try (InputStream in = new GZIPInputStream(Files.newInputStream(Path.of(INPUT)))) {
			articles = MAPPER.readTree(in);
		}

		List<WikiChunker.Chunk> chunks = new ArrayList<>();
		int withBody = 0;
		for (JsonNode a : articles) {
			String body = a.path("body").asText("");
			if (!body.isBlank())
				withBody++;
			chunks.addAll(WikiChunker.chunk(
					a.path("key").asText(), a.path("title").asText(),
					a.path("entityRef").asText(null), a.path("entityKey").asText(null),
					a.path("url").asText(null), body));
		}

		File out = Exports.outFile(OUTPUT);
		try (var os = new GZIPOutputStream(new java.io.FileOutputStream(out))) {
			MAPPER.writeValue(os, chunks);
		}
		report(articles.size(), withBody, chunks, out);
	}

	private static void report(int articles, int withBody, List<WikiChunker.Chunk> chunks, File out) {
		int correlated = 0, total = 0, max = 0;
		for (WikiChunker.Chunk c : chunks) {
			total += c.text().length();
			max = Math.max(max, c.text().length());
			if (c.entityKey() != null)
				correlated++;
		}
		System.out.printf("%d articles (%d with a body) → %d chunks to %s%n",
				articles, withBody, chunks.size(), out.getPath());
		System.out.printf("  avg %d chars/chunk, max %d; %d chunks (%.0f%%) carry an entity correlation%n",
				chunks.isEmpty() ? 0 : total / chunks.size(), max, correlated,
				chunks.isEmpty() ? 0.0 : 100.0 * correlated / chunks.size());
	}
}
