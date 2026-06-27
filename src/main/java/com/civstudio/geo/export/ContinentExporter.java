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
 * Dev tool: flattens the Anbennar {@code data/continent.txt} (a Clausewitz file)
 * into the {@code /continents.json} resource the core {@link
 * com.civstudio.geo.WorldMap} loads alongside {@code provinces.json}, {@code
 * areas.json} and {@code regions.json}, and stamps the owning continent's key onto
 * each province in {@code provinces.json} (the {@code continent} field {@link
 * com.civstudio.geo.Province#continentKey()} reads). Like the sibling exporters
 * this is a build-time/manual step whose output is committed, so the running
 * simulation never parses Clausewitz.
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
 * whole (multi-line) id list. There is <em>no</em> continent table in the Strapi
 * world content (no {@code ContinentImporter}), so this tier is file-only —
 * {@link ProvinceExporter}'s SQL does not emit {@code continent}; a full DB
 * regeneration must be followed by a rerun of this tool to restore it.
 * <p>
 * The file's non-geographic utility pseudo-continents ({@link #SKIP}) are skipped;
 * the geographic continents (including Anbennar's underground {@code serpentspine})
 * are kept. The continent key is the stable {@code raw_key}; the display name is
 * title-cased from it. Run after {@link ProvinceExporter} / {@link AreaExporter}
 * (it reads and re-stamps the committed {@code provinces.json}, preserving the
 * {@code region}/{@code area} fields):
 *
 * <pre>
 * mvn compile exec:exec -Dsim.main=com.civstudio.geo.export.ContinentExporter
 * </pre>
 *
 * See {@code docs/geography.md}.
 */
public final class ContinentExporter {

	private static final String INPUT = "data/continent.txt";
	private static final String CONTINENTS_OUTPUT = "src/main/resources/continents.json";
	private static final String PROVINCES = "src/main/resources/provinces.json";

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
		List<Continent> continents = exporter.parseContinents();
		exporter.writeContinents(continents);
		exporter.stampProvinces(continents);
	}

	/** Parse {@code continent.txt}, skipping the utility pseudo-continents. */
	private List<Continent> parseContinents() throws Exception {
		String content = Files.readString(new File(INPUT).toPath());
		content = content.replaceAll("#.*", ""); // strip line comments

		Matcher m = CONTINENT.matcher(content);
		List<Continent> continents = new ArrayList<>();
		while (m.find()) {
			String rawKey = m.group(1).trim();
			if (rawKey.isEmpty() || SKIP.contains(rawKey))
				continue;
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
			if (ids.isEmpty())
				continue;
			continents.add(new Continent(rawKey, displayName(rawKey), ids));
		}
		return continents;
	}

	private void writeContinents(List<Continent> continents) throws Exception {
		File out = new File(CONTINENTS_OUTPUT);
		mapper.writerWithDefaultPrettyPrinter().writeValue(out, continents);
		System.out.println("wrote " + continents.size() + " continents to "
				+ out.getAbsolutePath());
	}

	/**
	 * Back-fill the {@code continent} field onto each province in {@code
	 * provinces.json}, inserting it after {@code area} (or {@code region}) so the
	 * field order stays province &rarr; area &rarr; continent &rarr; neighbors.
	 */
	private void stampProvinces(List<Continent> continents) throws Exception {
		// province_id -> continent raw_key (a province belongs to one continent;
		// the geographic continents lead the file, so first-wins is correct)
		Map<Integer, String> continentOf = new LinkedHashMap<>();
		for (Continent c : continents)
			for (int pid : c.provinceIds())
				continentOf.putIfAbsent(pid, c.rawKey());

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
			// rebuild preserving order, inserting "continent" after "area" if
			// present, else after "region", else at the end
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

	/** "north_america" -&gt; "North America" (title-case; no suffix to drop). */
	static String displayName(String rawKey) {
		StringBuilder sb = new StringBuilder();
		for (String word : rawKey.split("_")) {
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
