package com.civstudio.geo.export;

import com.civstudio.data.AnbennarFiles;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.civstudio.geo.Area;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Dev tool: flattens the Anbennar {@code data/anbennar/area.txt} (a Clausewitz file) into
 * the {@code /areas.json} resource the core {@link com.civstudio.geo.WorldMap}
 * loads, and stamps each province's {@code area} <em>and</em> {@code region} keys
 * onto {@code provinces.json}. Like the sibling exporters this is a
 * build-time/manual step whose output is committed, so the running simulation
 * never parses Clausewitz.
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
 * voided vanilla areas) are skipped.
 * <p>
 * <b>Areas are the source of truth for region membership.</b> This exporter reads
 * the committed {@code regions.json} (a region&rarr;areas map) to derive each
 * province's region <em>through its area</em> ({@code province → area → region}),
 * and stamps that region onto {@code provinces.json} — overwriting the value
 * {@link ProvinceExporter} took straight from the Strapi DB, a few of which
 * disagree with the file tier. So the committed {@code region} field always
 * matches {@link com.civstudio.geo.WorldMap#regionOf(int)}. Run after {@link
 * ProvinceExporter} and {@link RegionExporter} (it reads {@code provinces.json}
 * and {@code regions.json}):
 *
 * <pre>
 * mvn compile exec:exec -Dsim.main=com.civstudio.geo.export.AreaExporter
 * </pre>
 *
 * See {@code docs/geography.md}.
 */
public final class AreaExporter {

	private static final String INPUT = "map/area.txt";
	private static final String AREAS_OUTPUT = "src/main/resources/map/areas.json";
	private static final String REGIONS = "src/main/resources/map/regions.json";
	private static final String PROVINCES = "src/main/resources/map/provinces.json";

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
		exporter.stampProvinces(areas, exporter.areaToRegion());
	}

	/** Parse {@code area.txt} into areas, skipping empty placeholder blocks. */
	private List<Area> parseAreas() throws Exception {
		String content = Files.readString(AnbennarFiles.get(INPUT));
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

	/** area raw_key -> region raw_key, inverted from the committed regions.json. */
	private Map<String, String> areaToRegion() throws Exception {
		List<Map<String, Object>> regions = mapper.readValue(new File(REGIONS),
				new TypeReference<List<Map<String, Object>>>() {
				});
		Map<String, String> areaToRegion = new LinkedHashMap<>();
		for (Map<String, Object> region : regions) {
			String regionKey = (String) region.get("key");
			@SuppressWarnings("unchecked")
			List<String> areaKeys = (List<String>) region.get("areas");
			if (areaKeys != null)
				for (String areaKey : areaKeys)
					areaToRegion.putIfAbsent(areaKey, regionKey);
		}
		return areaToRegion;
	}

	/**
	 * Back-fill the {@code area} and {@code region} fields onto each province in
	 * {@code provinces.json}: {@code area} from {@code area.txt}, {@code region}
	 * derived through that area (the area tier is authoritative). Field order stays
	 * province &rarr; region &rarr; area &rarr; neighbors.
	 */
	private void stampProvinces(List<Area> areas, Map<String, String> areaToRegion)
			throws Exception {
		// province_id -> area raw_key (a province belongs to exactly one area)
		Map<Integer, String> areaOf = new LinkedHashMap<>();
		for (Area a : areas)
			for (int pid : a.provinceIds())
				areaOf.putIfAbsent(pid, a.rawKey());

		File file = new File(PROVINCES);
		List<Map<String, Object>> rows = mapper.readValue(file,
				new TypeReference<List<Map<String, Object>>>() {
				});

		int areaStamped = 0, regionStamped = 0;
		List<Map<String, Object>> out = new ArrayList<>(rows.size());
		for (Map<String, Object> row : rows) {
			int id = ((Number) row.get("id")).intValue();
			String areaKey = areaOf.get(id);
			String regionKey = areaKey == null ? null : areaToRegion.get(areaKey);
			if (areaKey != null)
				areaStamped++;
			if (regionKey != null)
				regionStamped++;
			// rebuild preserving order: overwrite region in place, overwrite area
			// in place (or insert it right after region), keep everything else
			boolean hasArea = row.containsKey("area");
			Map<String, Object> rebuilt = new LinkedHashMap<>();
			for (Map.Entry<String, Object> e : row.entrySet()) {
				switch (e.getKey()) {
					case "region" -> {
						rebuilt.put("region", regionKey);
						if (!hasArea)
							rebuilt.put("area", areaKey);
					}
					case "area" -> rebuilt.put("area", areaKey);
					default -> rebuilt.put(e.getKey(), e.getValue());
				}
			}
			out.add(rebuilt);
		}

		mapper.writerWithDefaultPrettyPrinter().writeValue(file, out);
		System.out.println("stamped area onto " + areaStamped + "/" + rows.size()
				+ " and region onto " + regionStamped + "/" + rows.size()
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
