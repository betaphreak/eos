package com.civstudio.server.web;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves a province's per-plot terrain grid at {@code GET /api/plots/{id}} — the on-demand,
 * server-generated replacement for the old static {@code plots.pack} Range-fetch (see
 * {@code docs/plot-serving.md}). The body is the province's <b>gzipped</b> JSON blob returned as
 * raw bytes with <b>no {@code Content-Encoding}</b> — the browser gunzips it in-page via {@code
 * DecompressionStream}, the same contract {@link AssetController} uses (and it stops a proxy from
 * double-gzipping). {@link PlotService} does the generation, caching and sim-pause coordination.
 */
@RestController
public class PlotController {

	private final PlotService service;

	public PlotController(PlotService service) {
		this.service = service;
	}

	@GetMapping("/api/plots/{id}")
	public ResponseEntity<byte[]> plots(@PathVariable int id) {
		byte[] gz = service.gz(id);
		if (gz == null)
			return ResponseEntity.notFound().build();
		return ResponseEntity.ok()
				.header(HttpHeaders.CONTENT_TYPE, "application/octet-stream")
				// map data changes only on a (rare) generation-algorithm change → a day is a safe,
				// volume-cache-backed default; a deploy-versioned URL would let this go immutable.
				.header(HttpHeaders.CACHE_CONTROL, "public, max-age=86400")
				.body(gz);
	}
}
