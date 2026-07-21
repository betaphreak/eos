package com.civstudio.wiki.export;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Splits a wiki article's cleaned-markdown body into retrieval passages for the lore chatbot's RAG
 * substrate (P5; {@code docs/lore-chatbot-plan.md}). Each article is cut on its {@code ## section}
 * headings (the lead text before the first heading is the "Introduction"), and any section longer than
 * {@link #MAX_CHARS} is further split at paragraph boundaries — so a passage is a coherent,
 * embedding-sized unit that carries its own provenance ({@code wikiKey}/{@code entityRef}/{@code entityKey}/
 * {@code wikiUrl}) for citation + the hybrid join to canonical entities. Pure logic, no I/O — reused by
 * {@link WikiChunkExporter} now and the embedding backfill later.
 */
public final class WikiChunker {

	// soft cap on a passage: ~1200 chars ≈ ~300 tokens, comfortably inside a small embedding model's window
	static final int MAX_CHARS = 1200;

	private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.*)$");

	private WikiChunker() {
	}

	/** One retrieval passage of an article, with the provenance needed for citation + entity join. */
	public record Chunk(String chunkKey, String wikiKey, String title, String entityRef, String entityKey,
			String wikiUrl, String section, int ordinal, String text) {
	}

	/** Section-chunk one article. Empty/blank bodies yield no chunks. */
	public static List<Chunk> chunk(String wikiKey, String title, String entityRef, String entityKey,
			String wikiUrl, String body) {
		List<Chunk> out = new ArrayList<>();
		if (body == null || body.isBlank())
			return out;

		// 1) split the body into (heading, text) sections on markdown heading lines
		List<String[]> sections = new ArrayList<>();
		String heading = "Introduction";
		StringBuilder cur = new StringBuilder();
		for (String line : body.split("\n", -1)) {
			Matcher m = HEADING.matcher(line);
			if (m.matches()) {
				if (!cur.toString().isBlank())
					sections.add(new String[] { heading, cur.toString().strip() });
				heading = m.group(2).strip();
				cur.setLength(0);
			} else {
				cur.append(line).append('\n');
			}
		}
		if (!cur.toString().isBlank())
			sections.add(new String[] { heading, cur.toString().strip() });

		// 2) emit one chunk per section, splitting an over-long section at paragraph boundaries
		int ord = 0;
		for (String[] sec : sections) {
			for (String piece : splitLong(sec[1])) {
				if (piece.isBlank())
					continue;
				out.add(new Chunk(wikiKey + "#" + ord, wikiKey, title, entityRef, entityKey, wikiUrl,
						sec[0], ord, piece));
				ord++;
			}
		}
		return out;
	}

	// Break a section into ≤~MAX_CHARS pieces so every chunk fits a small embedding model's window.
	// Prefer paragraph boundaries; a paragraph over the cap is split on sentences; a lone sentence over
	// the cap is hard-split on whitespace. Adjacent small units are greedily packed back up toward the cap.
	private static List<String> splitLong(String text) {
		if (text.length() <= MAX_CHARS)
			return List.of(text);
		// 1) flatten to ≤MAX units (paragraph → sentence → hard split)
		List<String> units = new ArrayList<>();
		for (String para : text.split("\n{2,}")) {
			para = para.strip();
			if (para.isEmpty())
				continue;
			if (para.length() <= MAX_CHARS) {
				units.add(para);
			} else {
				for (String sent : para.split("(?<=[.!?])\\s+")) {
					sent = sent.strip();
					if (sent.isEmpty())
						continue;
					if (sent.length() <= MAX_CHARS)
						units.add(sent);
					else
						for (int i = 0; i < sent.length(); i += MAX_CHARS)
							units.add(sent.substring(i, Math.min(i + MAX_CHARS, sent.length())));
				}
			}
		}
		// 2) greedily pack units back up toward the cap
		List<String> out = new ArrayList<>();
		StringBuilder buf = new StringBuilder();
		for (String u : units) {
			if (buf.length() > 0 && buf.length() + 1 + u.length() > MAX_CHARS) {
				out.add(buf.toString());
				buf.setLength(0);
			}
			if (buf.length() > 0)
				buf.append(' ');
			buf.append(u);
		}
		if (buf.length() > 0)
			out.add(buf.toString());
		return out;
	}
}
