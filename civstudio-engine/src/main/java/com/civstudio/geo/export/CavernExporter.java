package com.civstudio.geo.export;

import com.civstudio.data.AnbennarFiles;
import com.civstudio.data.Exports;

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

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Dev tool: reads the Anbennar {@code data/anbennar/terrain.txt} (a Clausewitz file) and
 * overrides the {@code type} of each <b>special-terrain</b> province on {@code
 * map/provinces.json} to a distinct {@link com.civstudio.geo.ProvinceType}, so terrains
 * Anbennar treats specially don't flatten onto generic {@code LAND}. Each mapped terrain
 * block maps to one type — the four underground Dwarovar blocks ({@code cavern} → {@code
 * CAVERN}; {@code dwarven_hold}/{@code dwarven_hold_surface} → the hold types; {@code
 * dwarven_road} → {@code DWARVEN_ROAD}) plus seven surface terrains ({@code ancient_forest},
 * {@code gladeway}, {@code fey_gladeway}, {@code bloodgroves}, {@code mushroom_forest_terrain},
 * {@code shadow_swamp_terrain}, {@code glacier}). The standard terrains and the impassable
 * Serpentspine walls are left untyped.
 * <p>
 * The {@code city_terrain} block is handled separately: it does <b>not</b> get its own
 * province type (a city keeps its real land terrain — see {@code docs/urban-plots.md}), but
 * this tool stamps a boolean {@code city} flag onto its settleable-land provinces so plot
 * generation can give them a denser urban core.
 * <p>
 * The type override is applied only over {@code LAND} <b>or an already-special type</b>: a
 * listed province that is {@code IMPASSABLE} (a wasteland from {@code climate.txt}) or
 * water keeps its type, so only settleable land is typed. Accepting an already-special
 * type makes the tool idempotent and re-runnable — it reclassifies every special-terrain
 * province to the type its terrain block dictates on each run.
 * <p>
 * There is no Strapi table for this, so the field is file-only — a full regen of {@code
 * provinces.json} must rerun this. Run <b>after</b> {@link ProvinceExporter} /
 * {@link AreaExporter} / {@link ContinentExporter} / {@link ClimateExporter} (it
 * re-stamps the committed {@code provinces.json}, and depends on {@code IMPASSABLE}
 * having already been overlaid so cave walls are excluded):
 *
 * <pre>
 * mvn compile exec:exec -Dsim.main=com.civstudio.geo.export.CavernExporter
 * </pre>
 *
 * See {@code docs/underworld.md}.
 */
public final class CavernExporter {

	private static final String INPUT = "map/terrain.txt";
	private static final String PROVINCES = "civstudio-engine/target/generated/map/provinces.json";

	// each special Anbennar terrain block → the ProvinceType it stamps: the underground Dwarovar
	// four, plus seven distinctive surface terrains that would otherwise flatten onto generic LAND.
	// (city_terrain is deliberately excluded — urban plots/walls are a future phase.) Each block is
	// `NAME = { … terrain_override = { ids } … }`; order is the priority for a province listed in
	// more than one block (first wins).
	private static final Map<String, String> TERRAIN_TYPES = new LinkedHashMap<>();
	static {
		TERRAIN_TYPES.put("cavern", "CAVERN");
		TERRAIN_TYPES.put("dwarven_hold", "DWARVEN_HOLD");
		TERRAIN_TYPES.put("dwarven_hold_surface", "DWARVEN_HOLD_SURFACE");
		TERRAIN_TYPES.put("dwarven_road", "DWARVEN_ROAD");
		TERRAIN_TYPES.put("ancient_forest", "ANCIENT_FOREST");
		TERRAIN_TYPES.put("gladeway", "GLADEWAY");
		TERRAIN_TYPES.put("fey_gladeway", "FEY_GLADEWAY");
		TERRAIN_TYPES.put("bloodgroves", "BLOODGROVES");
		TERRAIN_TYPES.put("mushroom_forest_terrain", "MUSHROOM_FOREST");
		TERRAIN_TYPES.put("shadow_swamp_terrain", "SHADOW_SWAMP");
		TERRAIN_TYPES.put("glacier", "GLACIER");
	}
	// the special type names — a province already of one of these may be reclassified (so the tool
	// is re-runnable); only LAND is otherwise stampable, and water/IMPASSABLE are never touched
	private static final Set<String> SPECIAL_TYPES = Set.copyOf(TERRAIN_TYPES.values());
	private static final Pattern OVERRIDE = Pattern.compile(
			"terrain_override\\s*=\\s*\\{([^}]*)\\}");

	/** The Anbennar city block: stamps the {@code city} flag (not a type). */
	private static final String CITY_BLOCK = "city_terrain";

	private final ObjectMapper mapper = new ObjectMapper();
	private final Map<Integer, String> typeOf = new LinkedHashMap<>();   // province id → target type
	private final Set<Integer> cityIds = new LinkedHashSet<>();          // city_terrain provinces → city flag

	private CavernExporter() {
	}

	public static void main(String[] args) throws Exception {
		CavernExporter exporter = new CavernExporter();
		exporter.parse();
		exporter.stampProvinces();
	}

	private void parse() throws Exception {
		String content = Files.readString(AnbennarFiles.get(INPUT));
		content = content.replaceAll("#.*", ""); // strip line comments (region labels)

		for (Map.Entry<String, String> e : TERRAIN_TYPES.entrySet())
			for (int id : blockOverrideIds(content, e.getKey()))
				typeOf.putIfAbsent(id, e.getValue()); // first block listing a province wins
		// city_terrain drives the boolean city flag, not a province type
		cityIds.addAll(blockOverrideIds(content, CITY_BLOCK));
	}

	/**
	 * The province ids in a terrain block's {@code terrain_override}. Finds {@code NAME = {}
	 * (the {@code =} is required so {@code dwarven_hold} does not also match the longer
	 * {@code dwarven_hold_surface} block), then the first {@code terrain_override} after it.
	 */
	private static List<Integer> blockOverrideIds(String content, String block) {
		Matcher b = Pattern.compile("\\b" + block + "\\s*=\\s*\\{").matcher(content);
		if (!b.find())
			throw new IllegalStateException("no `" + block + "` terrain block in " + INPUT);
		Matcher ov = OVERRIDE.matcher(content);
		ov.region(b.end(), content.length());
		if (!ov.find())
			throw new IllegalStateException(block + " block has no terrain_override in " + INPUT);
		return ids(ov.group(1));
	}

	private static List<Integer> ids(String body) {
		List<Integer> ids = new ArrayList<>();
		for (String token : body.trim().split("\\s+")) {
			if (token.isEmpty())
				continue;
			try {
				ids.add(Integer.parseInt(token));
			} catch (NumberFormatException ignored) {
				// a stray non-numeric token; the block is province ids only
			}
		}
		return ids;
	}

	private void stampProvinces() throws Exception {
		File file = Exports.outFile(PROVINCES);
		List<Map<String, Object>> rows = mapper.readValue(file,
				new TypeReference<List<Map<String, Object>>>() {
				});

		Map<String, Integer> stamped = new LinkedHashMap<>();
		int skippedNonLand = 0;
		int cities = 0;
		for (Map<String, Object> row : rows) {
			int id = ((Number) row.get("id")).intValue();
			// only settleable land is typed/flagged (LAND or an already-special type);
			// water/IMPASSABLE is left alone
			boolean settleableLand = "LAND".equals(String.valueOf(row.get("type")))
					|| SPECIAL_TYPES.contains(String.valueOf(row.get("type")));
			String target = typeOf.get(id);
			if (target != null) {
				if (settleableLand) {
					row.put("type", target); // in-place: keeps the key's position in the row
					stamped.merge(target, 1, Integer::sum);
				} else {
					skippedNonLand++;
				}
			}
			// the city flag (city keeps its LAND type; the flag only asks for a denser core)
			if (cityIds.contains(id) && settleableLand) {
				row.put("city", true);
				cities++;
			}
		}

		mapper.writerWithDefaultPrettyPrinter().writeValue(file, rows);
		System.out.println("stamped underground types " + stamped + " (skipped " + skippedNonLand
				+ " non-land listed ids), city flag onto " + cities + " of " + rows.size()
				+ " provinces in " + file.getAbsolutePath());
	}
}
