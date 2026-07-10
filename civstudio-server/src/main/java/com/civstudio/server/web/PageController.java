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
 * Serves the self-contained thin-client demo page ({@code web/live.html}) at {@code /}, read from
 * the working directory (the repo root locally, {@code /app} in the image — the Dockerfile copies
 * it in). The full map site lives on Static Web Apps and talks to this server's {@code /api/**}.
 */
@RestController
public class PageController {

	@GetMapping(value = { "/", "/live.html" })
	public ResponseEntity<byte[]> livePage() throws IOException {
		Path page = Path.of("web", "live.html");
		if (!Files.exists(page))
			return ResponseEntity.status(404)
					.body("web/live.html not found (run from the repo root)".getBytes(StandardCharsets.UTF_8));
		return ResponseEntity.ok()
				.header(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_HTML_VALUE + "; charset=utf-8")
				.body(Files.readAllBytes(page));
	}
}
