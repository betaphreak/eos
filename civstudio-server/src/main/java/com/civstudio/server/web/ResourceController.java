package com.civstudio.server.web;

import java.io.IOException;
import java.util.List;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Two small endpoints the boot path needs:
 *
 * <ul>
 * <li>{@code GET /api/resources} — the {@link ResourceManifest} bill of materials, so the loading
 * screen can total the bytes and prefetch the eager set with real progress rather than discovering
 * each asset lazily on first use.</li>
 * <li>{@code GET /api/ping} — a server timestamp, for the top bar's latency readout.</li>
 * </ul>
 *
 * <p>The manifest is cache-able per deploy like the assets it describes. {@code /api/ping} is the
 * exact opposite and must say so loudly: a cached ping would report a latency of ~0ms forever, which
 * is worse than no readout at all, so it is explicitly {@code no-store}.
 */
@RestController
public class ResourceController {

	@GetMapping("/api/resources")
	public ResponseEntity<Manifest> resources() throws IOException {
		List<ResourceManifest.Entry> entries = ResourceManifest.entries();
		return ResponseEntity.ok()
				.header(HttpHeaders.CACHE_CONTROL, "public, max-age=3600")
				.body(new Manifest(entries, ResourceManifest.eagerBytes()));
	}

	/**
	 * @param resources every servable world-level resource
	 * @param eagerBytes gzip bytes the client should expect to prefetch (the eager subset)
	 */
	public record Manifest(List<ResourceManifest.Entry> resources, long eagerBytes) {
	}

	/**
	 * Server wall-clock, for the client's round-trip measurement. The client times the request and
	 * reports RTT; {@code t} additionally lets it estimate clock skew if it ever wants to. Kept
	 * deliberately trivial — this endpoint's whole job is to be the cheapest honest thing the server
	 * can answer, so its timing reflects the network rather than any work done here.
	 */
	@GetMapping("/api/ping")
	public ResponseEntity<Pong> ping() {
		return ResponseEntity.ok()
				.header(HttpHeaders.CACHE_CONTROL, "no-store")
				.body(new Pong(System.currentTimeMillis()));
	}

	/** @param t server epoch milliseconds at the moment the ping was answered */
	public record Pong(long t) {
	}
}
