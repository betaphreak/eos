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
 * Assembles the unit pack the web tech-tree view fetches ({@code GET /api/units}, see
 * {@link AssetController}) — the gzipped land-unit set, gunzipped in-page by
 * {@code web/js/techtree.mjs} (the {@link TechBundle}/{@link BuildingBundle} pattern). The pack is
 * an object with two arrays:
 * <ul>
 * <li>{@code units} — each kept-tech-gated {@code UNIT_*} with its ids/name/pedia/role/stats/prereqs
 * and its {@code icon} sprite rect (a cell in {@code web/assets/units/unit-icons.webp}), so the view
 * can draw the per-node unit row grouped by {@code caravanRole} and, on click, the rail inspector.</li>
 * <li>{@code combats} — each functional {@code UNITCOMBAT_*} class with its name, folded
 * {@code signatureSkill} and its {@code icon} rect (a cell in
 * {@code web/assets/units/unit-combat-icons.webp}) — the group/category grouping icons.</li>
 * </ul>
 * Same rationale as {@link BuildingBundle}: the unit <em>graph</em> (ids, names, roles, prereqs,
 * stats) is drift-prone and ships in the engine jar as {@code /units.json} / {@code /unit-combats.json}
 * (from {@code UnitInfoExporter}), so serving it from there keeps the engine the single source of
 * truth. The only art-coupled bit — each row's {@code icon} rect, which can't be regenerated
 * server-side — is the committed {@code /units-meta.json} / {@code /unit-combats-meta.json}
 * ({@code web/build-units.mjs}), keyed by id, merged in here. The raw {@code button} /
 * {@code categoryButton} paths are dropped (the client draws from the sheet). Cached once
 * (world-level, immutable per deploy).
 */
public final class UnitBundle {

	private UnitBundle() {
	}

	private static final ObjectMapper MAPPER = new ObjectMapper();

	// gzip-compressed pack, built once and cached (served as octet-stream, gunzipped in-page)
	private static volatile byte[] gzipBytes;

	// units-meta.json parsed once, for the per-unit icon-rect lookup the render layer uses to stamp
	// an embodied unit's sprite rect onto its CaravanView (docs/c2c-unit-import.md §Phase 5)
	private static volatile JsonNode unitMeta;

	/** The enriched unit + unit-combat set, minified and gzip-compressed, built once and cached. */
	public static byte[] gzip() {
		byte[] b = gzipBytes;
		return b != null ? b : build();
	}

	private static synchronized byte[] build() {
		if (gzipBytes != null)
			return gzipBytes;
		try {
			JsonNode units = mergeIcons(load("/units.json"), load("/units-meta.json"));
			JsonNode combats = mergeIcons(load("/unit-combats.json"), load("/unit-combats-meta.json"));
			ObjectNode root = MAPPER.createObjectNode();
			root.set("units", units);
			root.set("combats", combats);
			byte[] json = MAPPER.writeValueAsBytes(root);
			ByteArrayOutputStream bos = new ByteArrayOutputStream(json.length / 3);
			try (GZIPOutputStream gz = new GZIPOutputStream(bos)) {
				gz.write(json);
			}
			gzipBytes = bos.toByteArray();
			return gzipBytes;
		} catch (IOException e) {
			throw new UncheckedIOException("assembling the unit pack", e);
		}
	}

	// merge each row's {icon:[x,y,w,h]} rect (from the meta, keyed by id) onto the row, and drop the
	// raw art paths the client never uses (it draws from the sprite sheet)
	private static JsonNode mergeIcons(JsonNode rows, JsonNode meta) {
		for (JsonNode node : rows) {
			if (!(node instanceof ObjectNode o))
				continue;
			JsonNode m = meta.get(o.get("id").asText());
			if (m != null && m.has("icon"))
				o.set("icon", m.get("icon"));
			o.remove("button");
			o.remove("categoryButton");
			o.remove("artDefineTag");
		}
		return rows;
	}

	/**
	 * The icon sprite rect {@code [x,y,w,h]} of a unit in {@code assets/units/unit-icons.webp}, or
	 * {@code null} if the unit has no baked icon. Read from the committed {@code units-meta.json}
	 * (cached) — used by the render layer to give a band's embodied unit its art on the live map.
	 *
	 * @param unitId the {@code UNIT_*} id
	 * @return the rect, or {@code null}
	 */
	public static int[] iconRect(String unitId) {
		if (unitId == null)
			return null;
		JsonNode meta = unitMeta;
		if (meta == null) {
			meta = load("/units-meta.json");
			unitMeta = meta;
		}
		JsonNode row = meta.get(unitId);
		if (row == null)
			return null;
		JsonNode icon = row.get("icon");
		if (icon == null || !icon.isArray() || icon.size() < 4)
			return null;
		return new int[] { icon.get(0).asInt(), icon.get(1).asInt(),
				icon.get(2).asInt(), icon.get(3).asInt() };
	}

	private static JsonNode load(String resource) {
		try (InputStream in = com.civstudio.data.WorldSources.current().open(resource)) {
			if (in == null)
				throw new IllegalStateException("unit resource not found on classpath: " + resource
						+ (resource.endsWith("-meta.json")
								? " (run `node web/build-units.mjs` to generate it)" : ""));
			return MAPPER.readTree(in);
		} catch (IOException e) {
			throw new UncheckedIOException("reading " + resource, e);
		}
	}
}
