package com.civstudio.geo.export;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.civstudio.geo.Continent;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Dev tool: reads the Anbennar {@code data/continent.txt} (a Clausewitz file) and
 * stamps each province's continent {@code raw_key} onto {@code provinces.json} (the
 * {@code continent} field {@link com.civstudio.geo.Province#continent()} reads).
 * Unlike the area/region exporters there is <em>no</em> {@code continents.json}:
 * {@link Continent} is a fixed enum, so the only persisted continent data is the
 * per-province key. Like the sibling exporters this is a build-time/manual step
 * whose output is committed, so the running simulation never parses Clausewitz.
 * <p>
 * The source maps each continent to a flat list of province ids spanning many
 * lines, with {@code #} comments interspersed:
 *
 * <pre>
 * asia = {
 *     4405 4406 4410 4411 4412 ...
 *     #Anbennar New
 *     899 910 905 ...
 * }
 * </pre>
 *
 * Comments are stripped first; a single regex then captures continent key + the
 * whole (multi-line) id list. The file's non-geographic utility pseudo-continents
 * ({@link #SKIP}) are skipped; every other key must be a known {@link Continent}
 * (a guard against the source drifting from the enum). There is no continent table
 * in the Strapi world content, so this tier is file-only. Run after {@link
 * ProvinceExporter} (it reads and re-stamps the committed {@code provinces.json},
 * preserving the {@code region}/{@code area} fields):
 *
 * <pre>
 * mvn compile exec:exec -Dsim.main=com.civstudio.geo.export.ContinentExporter
 * </pre>
 *
 * See {@code docs/geography.md}.
 */
public final class ContinentExporter {

	private static final String INPUT = "data/continent.txt";
	private static final String PROVINCES = "src/main/resources/map/provinces.json";

	/** Non-geographic utility blocks in {@code continent.txt} (not continents). */
	private static final Set<String> SKIP = Set.of(
			"debug_continent", "island_check_provinces", "new_world");

	// continent_key = { id id id ... } (ids only, possibly across many lines)
	private static final Pattern CONTINENT = Pattern.compile(
			"([a-zA-Z0-9_]+)\\s*=\\s*\\{([^}]*)\\}");

	private final ObjectMapper mapper = new ObjectMapper();

	private ContinentExporter() {
	}

	public static void main(String[] args) throws Exception {
		ContinentExporter exporter = new ContinentExporter();
		Map<Integer, String> continentOf = exporter.parseProvinceContinents();
		exporter.stampProvinces(continentOf);
	}

	/** province_id -> continent raw_key, parsed from {@code continent.txt}. */
	private Map<Integer, String> parseProvinceContinents() throws Exception {
		String content = Files.readString(new File(INPUT).toPath());
		content = content.replaceAll("#.*", ""); // strip line comments

		Matcher m = CONTINENT.matcher(content);
		Map<Integer, String> continentOf = new LinkedHashMap<>();
		while (m.find()) {
			String rawKey = m.group(1).trim();
			if (rawKey.isEmpty() || SKIP.contains(rawKey))
				continue;
			// guard: every non-utility block must be a known continent
			Continent.fromKey(rawKey);
			for (String token : m.group(2).trim().split("\\s+")) {
				if (token.isEmpty())
					continue;
				try {
					continentOf.putIfAbsent(Integer.parseInt(token), rawKey);
				} catch (NumberFormatException ignored) {
					// a stray non-numeric token; the file is province ids only
				}
			}
		}
		return continentOf;
	}

	/**
	 * Back-fill the {@code continent} field onto each province in {@code
	 * provinces.json}, inserting it after {@code area} (or {@code region}) so the
	 * field order stays province &rarr; area &rarr; continent &rarr; neighbors.
	 */
	private void stampProvinces(Map<Integer, String> continentOf) throws Exception {
		File file = new File(PROVINCES);
		List<Map<String, Object>> rows = mapper.readValue(file,
				new TypeReference<List<Map<String, Object>>>() {
				});

		int stamped = 0;
		List<Map<String, Object>> out = new ArrayList<>(rows.size());
		for (Map<String, Object> row : rows) {
			int id = ((Number) row.get("id")).intValue();
			String key = continentOf.get(id);
			if (key != null)
				stamped++;
			boolean hasArea = row.containsKey("area");
			boolean hasRegion = row.containsKey("region");
			String anchor = hasArea ? "area" : (hasRegion ? "region" : null);
			Map<String, Object> rebuilt = new LinkedHashMap<>();
			boolean inserted = false;
			for (Map.Entry<String, Object> e : row.entrySet()) {
				if (e.getKey().equals("continent"))
					continue; // drop any stale value; re-added in canonical slot
				rebuilt.put(e.getKey(), e.getValue());
				if (e.getKey().equals(anchor)) {
					rebuilt.put("continent", key);
					inserted = true;
				}
			}
			if (!inserted)
				rebuilt.put("continent", key);
			out.add(rebuilt);
		}

		mapper.writerWithDefaultPrettyPrinter().writeValue(file, out);
		System.out.println("stamped continent onto " + stamped + "/" + rows.size()
				+ " provinces in " + file.getAbsolutePath());
	}
}
