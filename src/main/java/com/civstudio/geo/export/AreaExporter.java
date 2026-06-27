package com.civstudio.geo.export;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.civstudio.geo.Area;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Dev tool: flattens the Anbennar {@code data/area.txt} (a Clausewitz file) into
 * the {@code /areas.json} resource the core {@link com.civstudio.geo.WorldMap}
 * loads alongside {@code provinces.json} and {@code regions.json}, and stamps the
 * owning area's key onto each province in {@code provinces.json} (the {@code
 * area} field {@link com.civstudio.geo.Province#areaKey()} reads). Like {@link
 * ProvinceExporter} and {@link RegionExporter} this is a build-time/manual step
 * whose output is committed, so the running simulation never parses Clausewitz.
 * <p>
 * The source maps each area to its province ids:
 *
 * <pre>
 * inner_rahen_area = {
 *     4379 4385 4411 4412
 * }
 * </pre>
 *
 * A single regex captures area key + id list (the ids carry no nested braces);
 * comments are stripped first and empty placeholder areas ({@code key = { }},
 * voided vanilla areas) are skipped. The area key is the stable {@code raw_key}
 * {@link com.civstudio.geo.Province#areaKey()} and {@link
 * com.civstudio.geo.Region#areaKeys()} reference; the display name is title-cased
 * from it.
 * <p>
 * The province&rarr;area back-fill into {@code provinces.json} keeps that
 * committed snapshot's {@code area} field in sync without a database round-trip;
 * {@link ProvinceExporter}'s SQL emits the same field for a full regeneration
 * from the Strapi world content. Run after {@link ProvinceExporter} (it reads the
 * committed {@code provinces.json}):
 *
 * <pre>
 * mvn compile exec:exec -Dsim.main=com.civstudio.geo.export.AreaExporter
 * </pre>
 *
 * See {@code docs/geography.md}.
 */
public final class AreaExporter {

	private static final String INPUT = "data/area.txt";
	private static final String AREAS_OUTPUT = "src/main/resources/areas.json";
	private static final String PROVINCES = "src/main/resources/provinces.json";

	// area_key = { id id id ... } (ids only, no nested braces)
	private static final Pattern AREA = Pattern.compile(
			"([a-zA-Z0-9_]+)\\s*=\\s*\\{([^}]*)\\}");

	private final ObjectMapper mapper = new ObjectMapper();

	private AreaExporter() {
	}

	public static void main(String[] args) throws Exception {
		AreaExporter exporter = new AreaExporter();
		List<Area> areas = exporter.parseAreas();
		exporter.writeAreas(areas);
		exporter.stampProvinces(areas);
	}

	/** Parse {@code area.txt} into areas, skipping empty placeholder blocks. */
	private List<Area> parseAreas() throws Exception {
		String content = Files.readString(new File(INPUT).toPath());
		content = content.replaceAll("#.*", ""); // strip line comments

		Matcher m = AREA.matcher(content);
		List<Area> areas = new ArrayList<>();
		while (m.find()) {
			String rawKey = m.group(1).trim();
			List<Integer> ids = new ArrayList<>();
			for (String token : m.group(2).trim().split("\\s+")) {
				if (token.isEmpty())
					continue;
				try {
					ids.add(Integer.parseInt(token));
				} catch (NumberFormatException ignored) {
					// a stray non-numeric token; the file is province ids only
				}
			}
			if (rawKey.isEmpty() || ids.isEmpty())
				continue; // an empty placeholder area
			areas.add(new Area(rawKey, displayName(rawKey), ids));
		}
		return areas;
	}

	private void writeAreas(List<Area> areas) throws Exception {
		File out = new File(AREAS_OUTPUT);
		mapper.writerWithDefaultPrettyPrinter().writeValue(out, areas);
		System.out.println("wrote " + areas.size() + " areas to " + out.getAbsolutePath());
	}

	/**
	 * Back-fill the {@code area} field onto each province in {@code
	 * provinces.json}, inserting it right after {@code region} so the field order
	 * matches {@link ProvinceExporter}'s SQL.
	 */
	private void stampProvinces(List<Area> areas) throws Exception {
		// province_id -> area raw_key (a province belongs to exactly one area)
		Map<Integer, String> areaOf = new LinkedHashMap<>();
		for (Area a : areas)
			for (int pid : a.provinceIds())
				areaOf.putIfAbsent(pid, a.rawKey());

		File file = new File(PROVINCES);
		List<Map<String, Object>> rows = mapper.readValue(file,
				new TypeReference<List<Map<String, Object>>>() {
				});

		int stamped = 0;
		List<Map<String, Object>> out = new ArrayList<>(rows.size());
		for (Map<String, Object> row : rows) {
			int id = ((Number) row.get("id")).intValue();
			String areaKey = areaOf.get(id);
			if (areaKey != null)
				stamped++;
			// rebuild preserving order, inserting "area" after "region"
			Map<String, Object> rebuilt = new LinkedHashMap<>();
			boolean inserted = false;
			for (Map.Entry<String, Object> e : row.entrySet()) {
				if (e.getKey().equals("area"))
					continue; // drop any stale value; we re-add in canonical slot
				rebuilt.put(e.getKey(), e.getValue());
				if (e.getKey().equals("region")) {
					rebuilt.put("area", areaKey);
					inserted = true;
				}
			}
			if (!inserted)
				rebuilt.put("area", areaKey);
			out.add(rebuilt);
		}

		mapper.writerWithDefaultPrettyPrinter().writeValue(file, out);
		System.out.println("stamped area onto " + stamped + "/" + rows.size()
				+ " provinces in " + file.getAbsolutePath());
	}

	/** "inner_rahen_area" -&gt; "Inner Rahen" (drop the suffix, title-case). */
	static String displayName(String rawKey) {
		String stem = rawKey.endsWith("_area")
				? rawKey.substring(0, rawKey.length() - "_area".length())
				: rawKey;
		StringBuilder sb = new StringBuilder();
		for (String word : stem.split("_")) {
			if (word.isEmpty())
				continue;
			if (sb.length() > 0)
				sb.append(' ');
			sb.append(Character.toUpperCase(word.charAt(0)))
					.append(word.substring(1).toLowerCase());
		}
		return sb.toString();
	}
}
