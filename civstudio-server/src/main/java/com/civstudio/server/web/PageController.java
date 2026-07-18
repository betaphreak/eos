package com.civstudio.server.web;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.civstudio.server.CivStudioProperties;

/**
 * Serves the server's self-contained HTML pages, read from the working directory (the repo root
 * locally, {@code /app} in the image — the Dockerfile copies each in). The <b>admin console</b> is
 * no longer a page here: its UI moved to homepage widgets in the Strapi admin, so {@code /} now
 * <b>redirects</b> to {@link CivStudioProperties.Admin#getConsoleUrl()} and the widgets call this
 * server's admin API ({@link AdminController}, {@code /api/sessions/**}) cross-origin. The spectator
 * {@code web/lobby.html} is at {@code /lobby}. The full map site lives on Static Web Apps and talks
 * to this server's {@code /api/**}. See {@code docs/admin-console.md}.
 */
@RestController
public class PageController {

	private final CivStudioProperties props;

	public PageController(CivStudioProperties props) {
		this.props = props;
	}

	/** Redirect to the Strapi admin that now hosts the ops widgets (the old admin.html is retired). */
	@GetMapping("/")
	public ResponseEntity<Void> root() {
		return ResponseEntity.status(302)
				.location(URI.create(props.getAdmin().getConsoleUrl()))
				.build();
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
