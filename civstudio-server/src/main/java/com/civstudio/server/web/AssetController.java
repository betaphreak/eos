package com.civstudio.server.web;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.zip.GZIPOutputStream;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves the two map/reference assets the web viewer used to ship as committed
 * {@code web/assets/*} files but which the server now owns — so the server becomes their
 * single source (the {@code data.js} → {@code /api/bundle} rationale, applied to the last two
 * jar-derivable assets). {@code tierborders.json} is a web-only serving artifact, committed in
 * this module's resources; {@code techs.json} (the graph) still ships in the engine jar:
 * <ul>
 * <li>{@code GET /api/tiers} — the dissolved geographic-tier outlines, served verbatim from this
 * module's {@code /map/tierborders.json} (byte-identical to the old {@code assets/tiers.json}).</li>
 * <li>{@code GET /api/techs} — the tech-tree pack assembled by {@link TechBundle} (was
 * {@code assets/techs.pack}).</li>
 * </ul>
 * Both are world-level and immutable per deploy, so they are cached and gzipped like the bundle
 * ({@link BundleController}). The tech pack is served as {@code application/octet-stream} gzip
 * bytes (no {@code Content-Encoding}) so the client gunzips it in-page via {@code
 * DecompressionStream} — the {@code plots.pack}/{@code techs.pack} contract that stops a proxy
 * double-encoding it.
 */
@RestController
public class AssetController {

	// tierborders.json bytes (raw + gzip), read once from the classpath and cached
	private static volatile byte[] tiersJson;
	private static volatile byte[] tiersGzip;

	@GetMapping("/api/tiers")
	public ResponseEntity<byte[]> tiers(
			@RequestHeader(value = "Accept-Encoding", required = false) String acceptEncoding)
			throws IOException {
		ensureTiers();
		boolean gzip = acceptEncoding != null && acceptEncoding.toLowerCase().contains("gzip");
		ResponseEntity.BodyBuilder b = ResponseEntity.ok()
				.header(HttpHeaders.CONTENT_TYPE, "application/json; charset=utf-8")
				.header(HttpHeaders.CACHE_CONTROL, "public, max-age=3600");
		if (gzip)
			b.header(HttpHeaders.CONTENT_ENCODING, "gzip");
		return b.body(gzip ? tiersGzip : tiersJson);
	}

	@GetMapping("/api/techs")
	public ResponseEntity<byte[]> techs() {
		return ResponseEntity.ok()
				.header(HttpHeaders.CONTENT_TYPE, "application/octet-stream")
				.header(HttpHeaders.CACHE_CONTROL, "public, max-age=3600")
				.body(TechBundle.gzip());
	}

	private static synchronized void ensureTiers() throws IOException {
		if (tiersJson != null)
			return;
		byte[] raw;
		try (InputStream in = AssetController.class.getResourceAsStream("/map/tierborders.json")) {
			if (in == null)
				throw new IllegalStateException("/map/tierborders.json not on classpath");
			raw = in.readAllBytes();
		}
		ByteArrayOutputStream bos = new ByteArrayOutputStream(raw.length / 3);
		try (GZIPOutputStream gz = new GZIPOutputStream(bos)) {
			gz.write(raw);
		}
		tiersGzip = bos.toByteArray();
		tiersJson = raw; // publish last: tiersJson non-null guards both
	}
}
