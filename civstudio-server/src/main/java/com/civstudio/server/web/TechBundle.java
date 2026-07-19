package com.civstudio.server.web;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.zip.GZIPOutputStream;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Assembles the tech-tree pack the web viewer's technology modal fetches ({@code GET /api/techs},
 * see {@link AssetController}) — the gzipped tech graph, gunzipped in-page by
 * {@code web/js/techtree.mjs} (the {@code plots.pack} pattern).
 * <p>
 * This <b>replaces the committed {@code web/assets/techs.pack}</b>, on the same rationale that
 * moved {@code data.js} → {@code /api/bundle}: the tech <em>graph</em> (grid coords, eras,
 * prereqs, English name/help/quote) is the drift-prone part, and it already ships in the engine
 * jar as {@code /techs.json}. Serving it from there makes the engine the single source of truth
 * again. The only bits the build adds are art-coupled and can't be regenerated server-side — each
 * tech's {@code icon} sprite rect (a cell in {@code tech-icons.webp}) and the curated
 * {@code beaker} colour — so {@code web/build-techs.mjs} emits them as static metadata
 * ({@code /techs-meta.json}, keyed by tech {@code Type}), which this class merges in. Cached once
 * (world-level, immutable per deploy).
 */
public final class TechBundle {

	private TechBundle() {
	}

	private static final ObjectMapper MAPPER = new ObjectMapper();

	// gzip-compressed pack, built once and cached (served as octet-stream, gunzipped in-page)
	private static volatile byte[] gzipBytes;

	/** The enriched tech graph, minified and gzip-compressed, built once and cached. */
	public static byte[] gzip() {
		byte[] b = gzipBytes;
		return b != null ? b : build();
	}

	private static synchronized byte[] build() {
		if (gzipBytes != null)
			return gzipBytes;
		try {
			JsonNode techs = load("/techs.json");     // array of tech objects
			JsonNode meta = load("/techs-meta.json");  // {Type: {icon:[x,y,w,h], beaker:"green"}}
			for (JsonNode t : techs) {
				JsonNode m = meta.get(t.get("Type").asText());
				if (m != null && t instanceof ObjectNode o) {
					if (m.has("icon"))
						o.set("icon", m.get("icon"));
					if (m.has("beaker"))
						o.set("beaker", m.get("beaker"));
				}
			}
			byte[] json = MAPPER.writeValueAsBytes(techs);
			ByteArrayOutputStream bos = new ByteArrayOutputStream(json.length / 3);
			try (GZIPOutputStream gz = new GZIPOutputStream(bos)) {
				gz.write(json);
			}
			gzipBytes = bos.toByteArray();
			return gzipBytes;
		} catch (IOException e) {
			throw new UncheckedIOException("assembling the tech pack", e);
		}
	}

	private static JsonNode load(String resource) {
		try (InputStream in = com.civstudio.data.WorldSources.current().open(resource)) {
			if (in == null)
				throw new IllegalStateException("tech resource not found on classpath: " + resource
						+ (resource.endsWith("techs-meta.json")
								? " (run `node web/build-techs.mjs` to generate it)" : ""));
			return MAPPER.readTree(in);
		} catch (IOException e) {
			throw new UncheckedIOException("reading " + resource, e);
		}
	}
}
