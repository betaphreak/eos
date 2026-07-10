package com.civstudio.server.web;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves {@code window.BUNDLE} — the map/geo backbone the web viewer fetches at boot — assembled
 * from the committed map resources by {@link WorldBundle}. Static per deploy, so it is cached and
 * gzipped when the client accepts it (this server is not behind the CDN that used to gzip
 * {@code data.js}). See {@code docs/client-server.md}.
 */
@RestController
public class BundleController {

	@GetMapping("/api/bundle")
	public ResponseEntity<byte[]> bundle(
			@RequestHeader(value = "Accept-Encoding", required = false) String acceptEncoding) {
		boolean gzip = acceptEncoding != null && acceptEncoding.toLowerCase().contains("gzip");
		byte[] body = gzip ? WorldBundle.gzip() : WorldBundle.json();
		ResponseEntity.BodyBuilder b = ResponseEntity.ok()
				.header(HttpHeaders.CONTENT_TYPE, "application/json; charset=utf-8")
				.header(HttpHeaders.CACHE_CONTROL, "public, max-age=3600");
		if (gzip)
			b.header(HttpHeaders.CONTENT_ENCODING, "gzip");
		return b.body(body);
	}
}
