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
 * Assembles the building pack the web tech-tree view fetches ({@code GET /api/buildings}, see
 * {@link AssetController}) — the gzipped building set, gunzipped in-page by
 * {@code web/js/techtree.mjs} (the {@link TechBundle}/{@code plots.pack} pattern). Each building
 * carries its ids/name/pedia/category/prereqs and its {@code icon} sprite rect (a cell in
 * {@code web/assets/buildings/building-icons.webp}), so the view can draw the per-node building
 * grid and, on click, the rail inspector.
 * <p>
 * Same rationale as {@link TechBundle}: the building <em>graph</em> (ids, names, prereqs,
 * category, cost) is the drift-prone part and already ships in the engine jar as
 * {@code /buildings.json} (from {@code BuildingInfoExporter}), so serving it from there keeps the
 * engine the single source of truth. The only art-coupled bit — each building's {@code icon}
 * rect, which can't be regenerated server-side — is the committed {@code /buildings-meta.json}
 * ({@code web/build-buildings.mjs}), keyed by building id, which this class merges in. The raw
 * {@code button} .dds path and {@code artDefineTag} are dropped (the client draws from the sheet,
 * never the DDS). Cached once (world-level, immutable per deploy).
 */
public final class BuildingBundle {

	private BuildingBundle() {
	}

	private static final ObjectMapper MAPPER = new ObjectMapper();

	// gzip-compressed pack, built once and cached (served as octet-stream, gunzipped in-page)
	private static volatile byte[] gzipBytes;

	/** The enriched building set, minified and gzip-compressed, built once and cached. */
	public static byte[] gzip() {
		byte[] b = gzipBytes;
		return b != null ? b : build();
	}

	private static synchronized byte[] build() {
		if (gzipBytes != null)
			return gzipBytes;
		try {
			JsonNode buildings = load("/buildings.json");      // array of building objects
			JsonNode meta = load("/buildings-meta.json");      // {id: {icon:[x,y,w,h]}}
			for (JsonNode node : buildings) {
				if (!(node instanceof ObjectNode o))
					continue;
				JsonNode m = meta.get(o.get("id").asText());
				if (m != null && m.has("icon"))
					o.set("icon", m.get("icon"));
				// the raw art paths are useless client-side (the view draws from the sheet)
				o.remove("button");
				o.remove("artDefineTag");
			}
			byte[] json = MAPPER.writeValueAsBytes(buildings);
			ByteArrayOutputStream bos = new ByteArrayOutputStream(json.length / 3);
			try (GZIPOutputStream gz = new GZIPOutputStream(bos)) {
				gz.write(json);
			}
			gzipBytes = bos.toByteArray();
			return gzipBytes;
		} catch (IOException e) {
			throw new UncheckedIOException("assembling the building pack", e);
		}
	}

	private static JsonNode load(String resource) {
		try (InputStream in = BuildingBundle.class.getResourceAsStream(resource)) {
			if (in == null)
				throw new IllegalStateException("building resource not found on classpath: " + resource
						+ (resource.endsWith("buildings-meta.json")
								? " (run `node web/build-buildings.mjs` to generate it)" : ""));
			return MAPPER.readTree(in);
		} catch (IOException e) {
			throw new UncheckedIOException("reading " + resource, e);
		}
	}
}
