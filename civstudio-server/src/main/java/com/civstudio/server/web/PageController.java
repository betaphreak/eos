package com.civstudio.server.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves the server's self-contained HTML pages, read from the working directory (the repo root
 * locally, {@code /app} in the image — the Dockerfile copies each in). The <b>admin console</b>
 * ({@code web/admin.html}) is at {@code /} (its actions are all admin-gated server-side, see {@link
 * AdminController} / {@code docs/admin-console.md}); the spectator {@code web/lobby.html} moved to
 * {@code /lobby}. The full map site lives on Static Web Apps and talks to this server's {@code
 * /api/**}.
 */
@RestController
public class PageController {

	/** The admin console — the default page on the server host. */
	@GetMapping("/")
	public ResponseEntity<byte[]> adminPage() throws IOException {
		return page("admin.html");
	}

	/** The spectator chat lobby (moved off {@code /}). */
	@GetMapping("/lobby")
	public ResponseEntity<byte[]> lobbyPage() throws IOException {
		return page("lobby.html");
	}

	private static ResponseEntity<byte[]> page(String name) throws IOException {
		Path page = Path.of("web", name);
		if (!Files.exists(page))
			return ResponseEntity.status(404)
					.body(("web/" + name + " not found (run from the repo root)").getBytes(StandardCharsets.UTF_8));
		return ResponseEntity.ok()
				.header(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_HTML_VALUE + "; charset=utf-8")
				.body(Files.readAllBytes(page));
	}
}
