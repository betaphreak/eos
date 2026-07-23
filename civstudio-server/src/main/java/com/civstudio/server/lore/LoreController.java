package com.civstudio.server.lore;

import java.util.Map;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The lore chatbot's read endpoints (P5), the Node {@code lore-service.mjs} folded into the server so the
 * web talks to ONE backend. Registered only when a lore datasource is configured (same
 * {@code civstudio.lore.datasource-url} gate as {@link LoreConfig}). Read-only, no auth gate — like the
 * other public read controllers; CORS is covered by the server-wide config.
 *
 * <ul>
 * <li>{@code GET  /api/lore/search?q=&k=} — pgvector top-k passages (no key needed).</li>
 * <li>{@code POST /api/lore/ask  {question}} — a Claude-grounded, cited answer (needs the Anthropic key;
 *     503 when absent, so callers can degrade to search).</li>
 * </ul>
 */
@RestController
// blank-safe gate (the yml default makes the property present-but-empty) — see LoreConfig
@ConditionalOnExpression("T(org.springframework.util.StringUtils).hasText('${civstudio.lore.datasource-url:}')")
public class LoreController {

	private final LoreService lore;

	public LoreController(LoreService lore) {
		this.lore = lore;
	}

	@GetMapping("/api/lore/search")
	public ResponseEntity<?> search(@RequestParam(required = false) String q,
			@RequestParam(defaultValue = "8") int k) {
		if (q == null || q.isBlank())
			return ResponseEntity.badRequest().body(Map.of("error", "missing ?q"));
		return ResponseEntity.ok(Map.of("results", lore.retrieve(q, Math.min(20, Math.max(1, k)))));
	}

	@PostMapping("/api/lore/ask")
	public ResponseEntity<?> ask(@RequestBody(required = false) Map<String, String> body) {
		String question = body == null ? null : body.get("question");
		if (question == null || question.isBlank())
			return ResponseEntity.badRequest().body(Map.of("error", "missing question"));
		try {
			return ResponseEntity.ok(lore.ask(question.trim()));
		} catch (IllegalStateException e) {
			return ResponseEntity.status(503).body(Map.of("error", e.getMessage())); // no key → degrade to search
		}
	}
}
