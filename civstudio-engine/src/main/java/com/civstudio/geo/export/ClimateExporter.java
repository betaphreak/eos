package com.civstudio.geo.export;

import com.civstudio.data.AnbennarFiles;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.civstudio.geo.Climate;
import com.civstudio.geo.Monsoon;
import com.civstudio.geo.WinterSeverity;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Dev tool: reads the Anbennar {@code data/anbennar/climate.txt} (a Clausewitz file) and
 * stamps each province's environmental attributes onto {@code map/provinces.json}
 * — the {@code climate}/{@code winter}/{@code monsoon} keys ({@link
 * com.civstudio.geo.Province#climate()} / {@code winter()} / {@code monsoon()})
 * and, for the {@code impassable} wasteland, an override of the {@code type} field
 * to {@link com.civstudio.geo.ProvinceType#IMPASSABLE}. Like the sibling exporters
 * this is a build-time/manual step whose output is committed; the values are enums
 * with no JSON resource of their own.
 * <p>
 * The file holds several orthogonal blocks, each a flat (multi-line, comment-laced)
 * province-id list — {@code tropical}/{@code arid}/{@code arctic} (climate; unlisted
 * = temperate), {@code mild_winter}/{@code normal_winter}/{@code severe_winter},
 * {@code mild_monsoon}/{@code normal_monsoon}/{@code severe_monsoon}, and
 * {@code impassable} — plus a scalar {@code equator_y_on_province_image} (no braces,
 * so the block regex skips it). Only the default (temperate / no-winter / no-monsoon)
 * is left unstamped, keeping the diff small.
 * <p>
 * The {@code impassable} override is applied only over {@code LAND} (wasteland is a
 * land class); a listed water province keeps its type. There is no Strapi table for
 * these, so the fields are file-only — a full DB regen of {@code provinces.json}
 * must rerun this. Run after {@link ProvinceExporter} / {@link AreaExporter} /
 * {@link ContinentExporter} (it re-stamps the committed {@code provinces.json}):
 *
 * <pre>
 * mvn compile exec:exec -Dsim.main=com.civstudio.geo.export.ClimateExporter
 * </pre>
 *
 * See {@code docs/geography.md}.
 */
public final class ClimateExporter {

	private static final String INPUT = "map/climate.txt";
	private static final String PROVINCES = "src/main/resources/map/provinces.json";

	// key = { id id id ... } (the equator_y scalar has no braces, so it is skipped)
	private static final Pattern BLOCK = Pattern.compile(
			"([a-zA-Z0-9_]+)\\s*=\\s*\\{([^}]*)\\}");

	private final ObjectMapper mapper = new ObjectMapper();

	// per-province overlays, parsed from the file
	private final Map<Integer, String> climateOf = new LinkedHashMap<>();
	private final Map<Integer, String> winterOf = new LinkedHashMap<>();
	private final Map<Integer, String> monsoonOf = new LinkedHashMap<>();
	private final Set<Integer> impassable = new LinkedHashSet<>();

	private ClimateExporter() {
	}

	public static void main(String[] args) throws Exception {
		ClimateExporter exporter = new ClimateExporter();
		exporter.parse();
		exporter.stampProvinces();
	}

	private void parse() throws Exception {
		String content = Files.readString(AnbennarFiles.get(INPUT));
		content = content.replaceAll("#.*", ""); // strip line comments

		Matcher m = BLOCK.matcher(content);
		while (m.find()) {
			String key = m.group(1).trim();
			List<Integer> ids = ids(m.group(2));
			switch (key) {
				case "tropical", "arid", "arctic" -> {
					Climate.fromKey(key); // validate against the enum
					ids.forEach(id -> climateOf.putIfAbsent(id, key));
				}
				case "mild_winter", "normal_winter", "severe_winter" -> {
					String sev = key.substring(0, key.indexOf('_'));
					WinterSeverity.fromKey(sev);
					ids.forEach(id -> winterOf.putIfAbsent(id, sev));
				}
				case "mild_monsoon", "normal_monsoon", "severe_monsoon" -> {
					String sev = key.substring(0, key.indexOf('_'));
					Monsoon.fromKey(sev);
					ids.forEach(id -> monsoonOf.putIfAbsent(id, sev));
				}
				case "impassable" -> impassable.addAll(ids);
				default -> {
					// any other block (none expected) is ignored
				}
			}
		}
	}

	private static List<Integer> ids(String body) {
		List<Integer> ids = new ArrayList<>();
		for (String token : body.trim().split("\\s+")) {
			if (token.isEmpty())
				continue;
			try {
				ids.add(Integer.parseInt(token));
			} catch (NumberFormatException ignored) {
				// a stray non-numeric token; the blocks are province ids only
			}
		}
		return ids;
	}

	private void stampProvinces() throws Exception {
		File file = new File(PROVINCES);
		List<Map<String, Object>> rows = mapper.readValue(file,
				new TypeReference<List<Map<String, Object>>>() {
				});

		int wasteland = 0, climates = 0, winters = 0, monsoons = 0;
		List<Map<String, Object>> out = new ArrayList<>(rows.size());
		for (Map<String, Object> row : rows) {
			int id = ((Number) row.get("id")).intValue();
			String climate = climateOf.get(id);
			String winter = winterOf.get(id);
			String monsoon = monsoonOf.get(id);
			// impassable overrides type, but only over LAND (wasteland is land)
			boolean wastelandHere = impassable.contains(id)
					&& "LAND".equals(row.get("type"));
			if (wastelandHere)
				wasteland++;
			if (climate != null)
				climates++;
			if (winter != null)
				winters++;
			if (monsoon != null)
				monsoons++;

			// anchor the climate/winter/monsoon fields after the last geo key present
			String anchor = row.containsKey("continent") ? "continent"
					: row.containsKey("area") ? "area"
							: row.containsKey("region") ? "region" : null;
			Map<String, Object> rebuilt = new LinkedHashMap<>();
			for (Map.Entry<String, Object> e : row.entrySet()) {
				switch (e.getKey()) {
					case "climate", "winter", "monsoon" -> {
						// drop any stale value; re-added in the canonical slot
					}
					case "type" -> rebuilt.put("type",
							wastelandHere ? "IMPASSABLE" : e.getValue());
					default -> rebuilt.put(e.getKey(), e.getValue());
				}
				if (e.getKey().equals(anchor))
					putOverlays(rebuilt, climate, winter, monsoon);
			}
			if (anchor == null)
				putOverlays(rebuilt, climate, winter, monsoon);
			out.add(rebuilt);
		}

		mapper.writerWithDefaultPrettyPrinter().writeValue(file, out);
		System.out.println("stamped climate onto " + climates + ", winter onto "
				+ winters + ", monsoon onto " + monsoons + ", and IMPASSABLE onto "
				+ wasteland + " of " + rows.size() + " provinces in "
				+ file.getAbsolutePath());
	}

	// add only the non-default overlays, in canonical order
	private static void putOverlays(Map<String, Object> row, String climate,
			String winter, String monsoon) {
		if (climate != null)
			row.put("climate", climate);
		if (winter != null)
			row.put("winter", winter);
		if (monsoon != null)
			row.put("monsoon", monsoon);
	}
}
