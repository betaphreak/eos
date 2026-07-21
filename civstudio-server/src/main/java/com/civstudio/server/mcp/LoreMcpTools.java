package com.civstudio.server.mcp;

import java.util.List;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.civstudio.server.lore.LoreService;

/**
 * The Anbennar lore corpus as MCP tools (P5, {@code docs/lore-chatbot-plan.md}), so an LLM talking to this
 * server can ground itself in the wiki without knowing the HTTP endpoints. A thin wrapper over
 * {@link LoreService} — the same pgvector retrieval + Claude generation the {@code /api/lore/**} controller
 * serves. Registered only when a lore datasource is configured (same {@code civstudio.lore.datasource-url}
 * gate as the service bean), so on a server without the vector store these tools simply don't appear.
 *
 * <ul>
 * <li>{@code search_lore} — semantic passage retrieval (no Anthropic key needed).</li>
 * <li>{@code ask_lore} — a cited, Claude-grounded answer (needs the server's Anthropic key).</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(name = "civstudio.lore.datasource-url")
public class LoreMcpTools {

	/** Cap the caller-supplied k the same way {@link com.civstudio.server.lore.LoreController} does. */
	private static final int MAX_K = 20;

	private final LoreService lore;

	public LoreMcpTools(LoreService lore) {
		this.lore = lore;
	}

	@McpTool(name = "search_lore",
			description = "Semantic search over the Anbennar wiki lore corpus (~12,700 passages: nations, "
					+ "cultures, religions, races, characters, provinces, events). Returns the top-k passages "
					+ "with their source article title, section, wiki URL, and cosine similarity. Use to ground "
					+ "answers in canonical lore before responding; no generation, just retrieval.")
	public List<LoreService.Passage> searchLore(
			@McpToolParam(description = "Natural-language query, e.g. 'the founding of Escann'",
					required = true) String query,
			@McpToolParam(description = "How many passages to return (1-20, default 8)",
					required = false) Integer k) {
		int limit = k == null ? 8 : Math.min(MAX_K, Math.max(1, k));
		return lore.retrieve(query, limit);
	}

	@McpTool(name = "ask_lore",
			description = "Ask a natural-language question about Anbennar lore and get a concise, cited answer "
					+ "grounded in the wiki. Retrieves the most relevant passages and has Claude answer from them, "
					+ "citing sources inline as [n]. Requires the server's Anthropic key; if it is not configured "
					+ "this call fails and you should fall back to search_lore.")
	public LoreService.Answer askLore(
			@McpToolParam(description = "The lore question to answer", required = true) String question) {
		return lore.ask(question);
	}
}
