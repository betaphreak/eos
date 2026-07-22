package com.civstudio.server.lore;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;

import com.civstudio.server.CivStudioProperties;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The lore chatbot backend (P5, {@code docs/lore-chatbot-plan.md}) — the Node {@code lore-service.mjs}
 * ported into the server. Question embeddings come from a self-hosted TEI (bge-small, 384-dim) over HTTP;
 * retrieval is a pgvector cosine search of {@code wiki_chunk} via {@link JdbcTemplate}; the grounded answer
 * is Claude ({@code claude-haiku-4-5}) over the retrieved passages. Wired only when a lore datasource is
 * configured — see {@link LoreConfig}. Dependency-free: JDK {@link HttpClient} + Jackson (no SDK).
 *
 * <p>First cut is retrieve-then-generate (C3). The agentic {@code search_lore} tool-loop (C4, in the Node
 * version) and the hybrid {@code lookup_entity} join are the next refinements.
 */
public class LoreService {

	private static final String ANTHROPIC_URL = "https://api.anthropic.com/v1/messages";
	private static final String SYSTEM = "You are the Loremaster of CivStudio — an in-world chronicler and "
			+ "guide for players of CivStudio, a day-by-day civilization simulation set in the world of Anbennar "
			+ "(imported from the Anbennar EU4 mod). You help players understand both the lore of the world and "
			+ "how the game itself works — its settlements, households, markets, banks, caravans, rulers and the "
			+ "passage of its days. Answer the player's question using the numbered excerpts below; they may "
			+ "describe the world's history and peoples or the workings of CivStudio itself. Cite the excerpts you "
			+ "draw on inline as [n], and explain the game's systems plainly and helpfully. Stay grounded in the "
			+ "excerpts — if they don't cover the answer, say so briefly rather than inventing detail. Be concise "
			+ "and clear, with a light flavor befitting a loremaster.";

	private final JdbcTemplate jdbc;
	private final CivStudioProperties.Lore config;
	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
	private final ObjectMapper json = new ObjectMapper();

	public LoreService(JdbcTemplate loreJdbc, CivStudioProperties.Lore config) {
		this.jdbc = loreJdbc;
		this.config = config;
	}

	/** One retrieved passage with its provenance. */
	public record Passage(String title, String section, String entityRef, String entityKey, String wikiUrl,
			String text, double sim) {
	}

	/** A grounded answer with the sources it was built from. */
	public record Answer(String answer, List<Source> sources) {
	}

	public record Source(String title, String wikiUrl) {
	}

	/** Top-k passages by cosine similarity for a query. */
	public List<Passage> retrieve(String question, int k) {
		String vec = embed(question);
		return jdbc.query(
				"SELECT title, section, entity_ref, entity_key, wiki_url, text, 1 - (embedding <=> ?::vector) AS sim "
						+ "FROM wiki_chunk ORDER BY embedding <=> ?::vector LIMIT ?",
				(rs, i) -> new Passage(rs.getString("title"), rs.getString("section"), rs.getString("entity_ref"),
						rs.getString("entity_key"), rs.getString("wiki_url"), rs.getString("text"), rs.getDouble("sim")),
				vec, vec, k);
	}

	/** Retrieve → ground Claude on the passages → a cited answer. Requires the Anthropic key. */
	public Answer ask(String question) {
		if (config.getAnthropicKey() == null || config.getAnthropicKey().isBlank())
			throw new IllegalStateException("ANTHROPIC_API_KEY not set");
		List<Passage> rows = retrieve(question, config.getSearchK());
		StringBuilder ctx = new StringBuilder();
		for (int i = 0; i < rows.size(); i++) {
			Passage p = rows.get(i);
			ctx.append('[').append(i + 1).append("] \"").append(p.title()).append("\" (").append(p.section())
					.append(")\n").append(p.text()).append("\n\n---\n\n");
		}
		String userMsg = "Question: " + question + "\n\nLore excerpts:\n\n" + ctx;
		Map<String, Object> reqBody = new LinkedHashMap<>();
		reqBody.put("model", config.getModel());
		reqBody.put("max_tokens", 1024);
		reqBody.put("system", SYSTEM);
		reqBody.put("messages", List.of(Map.of("role", "user", "content", userMsg)));

		JsonNode resp = anthropic(json.writeValueAsString(reqBody));
		StringBuilder answer = new StringBuilder();
		for (JsonNode b : resp.path("content"))
			if ("text".equals(b.path("type").asText()))
				answer.append(b.path("text").asText());

		// dedupe sources by title, preserving order
		Map<String, String> byTitle = new LinkedHashMap<>();
		for (Passage p : rows)
			byTitle.putIfAbsent(p.title(), p.wikiUrl());
		List<Source> sources = new ArrayList<>();
		byTitle.forEach((t, u) -> sources.add(new Source(t, u)));
		return new Answer(answer.toString(), sources);
	}

	// ---- HTTP ------------------------------------------------------------------------------------

	// Embed one text via TEI → the vector as a pgvector literal string "[f,f,...]" (bound as a SQL param).
	private String embed(String text) {
		String body;
		try {
			body = json.writeValueAsString(Map.of("inputs", List.of(text), "truncate", true));
			HttpRequest req = HttpRequest.newBuilder(URI.create(config.getTeiUrl()))
					.timeout(Duration.ofSeconds(30)).header("content-type", "application/json")
					.POST(HttpRequest.BodyPublishers.ofString(body)).build();
			HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
			if (res.statusCode() != 200)
				throw new IOException("TEI " + res.statusCode() + ": " + res.body());
			JsonNode v = json.readTree(res.body()).get(0); // [[...384...]]
			StringBuilder sb = new StringBuilder("[");
			for (int i = 0; i < v.size(); i++) {
				if (i > 0)
					sb.append(',');
				sb.append(v.get(i).asDouble());
			}
			return sb.append(']').toString();
		} catch (IOException e) {
			throw new RuntimeException("lore embed failed: " + e.getMessage(), e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new RuntimeException("lore embed interrupted", e);
		}
	}

	private JsonNode anthropic(String body) {
		try {
			HttpRequest req = HttpRequest.newBuilder(URI.create(ANTHROPIC_URL))
					.timeout(Duration.ofSeconds(60))
					.header("x-api-key", config.getAnthropicKey())
					.header("anthropic-version", "2023-06-01")
					.header("content-type", "application/json")
					.POST(HttpRequest.BodyPublishers.ofString(body)).build();
			HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
			if (res.statusCode() != 200)
				throw new IOException("Anthropic " + res.statusCode() + ": " + res.body());
			return json.readTree(res.body());
		} catch (IOException e) {
			throw new RuntimeException("lore generation failed: " + e.getMessage(), e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new RuntimeException("lore generation interrupted", e);
		}
	}
}
