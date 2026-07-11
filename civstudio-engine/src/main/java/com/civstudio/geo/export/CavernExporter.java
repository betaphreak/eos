package com.civstudio.geo.export;

import com.civstudio.data.AnbennarFiles;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
 * {@code shadow_swamp_terrain}, {@code glacier}). The standard terrains, {@code city_terrain}
 * (a future phase), and the impassable Serpentspine walls are left alone.
 * <p>
 * The override is applied only over {@code LAND} <b>or an already-special type</b>: a
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
	private static final String PROVINCES = "src/main/resources/map/provinces.json";

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

	private final ObjectMapper mapper = new ObjectMapper();
	private final Map<Integer, String> typeOf = new LinkedHashMap<>();   // province id → target type

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

		for (Map.Entry<String, String> e : TERRAIN_TYPES.entrySet()) {
			String terrain = e.getKey();
			// find `NAME = {`, then its own terrain_override (the first override after the
			// opening brace). NAME requires `=` right after it, so `dwarven_hold` does not
			// also match the longer `dwarven_hold_surface` block.
			Matcher block = Pattern.compile("\\b" + terrain + "\\s*=\\s*\\{").matcher(content);
			if (!block.find())
				throw new IllegalStateException("no `" + terrain + "` terrain block in " + INPUT);
			Matcher ov = OVERRIDE.matcher(content);
			ov.region(block.end(), content.length());
			if (!ov.find())
				throw new IllegalStateException(terrain + " block has no terrain_override in " + INPUT);
			for (int id : ids(ov.group(1)))
				typeOf.putIfAbsent(id, e.getValue()); // first block listing a province wins
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
				// a stray non-numeric token; the block is province ids only
			}
		}
		return ids;
	}

	private void stampProvinces() throws Exception {
		File file = new File(PROVINCES);
		List<Map<String, Object>> rows = mapper.readValue(file,
				new TypeReference<List<Map<String, Object>>>() {
				});

		Map<String, Integer> stamped = new LinkedHashMap<>();
		int skippedNonLand = 0;
		for (Map<String, Object> row : rows) {
			int id = ((Number) row.get("id")).intValue();
			String target = typeOf.get(id);
			if (target == null)
				continue;
			String current = String.valueOf(row.get("type"));
			// stamp over LAND or an already-special type (reclassify); leave water/IMPASSABLE
			if ("LAND".equals(current) || SPECIAL_TYPES.contains(current)) {
				row.put("type", target); // in-place: keeps the key's position in the row
				stamped.merge(target, 1, Integer::sum);
			} else {
				skippedNonLand++;
			}
		}

		mapper.writerWithDefaultPrettyPrinter().writeValue(file, rows);
		System.out.println("stamped underground types " + stamped + " (skipped " + skippedNonLand
				+ " non-land listed ids) of " + rows.size() + " provinces in " + file.getAbsolutePath());
	}
}
