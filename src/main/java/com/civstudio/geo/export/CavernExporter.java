package com.civstudio.geo.export;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Dev tool: reads the Anbennar {@code data/anbennar/terrain.txt} (a Clausewitz file) and
 * overrides the {@code type} of each underground Serpentspine province to {@link
 * com.civstudio.geo.ProvinceType#CAVERN} on {@code map/provinces.json}. The underground
 * set is the {@code terrain_override} list of the file's {@code cavern} terrain block
 * (the {@code mushroom_forest_terrain} block is a per-plot food terrain handled
 * elsewhere, not a province-type marker).
 * <p>
 * Like {@link ClimateExporter}'s {@code impassable} override, {@code CAVERN} is applied
 * <b>only over {@code LAND}</b>: a listed province that is already {@code IMPASSABLE}
 * (a cave-wall wasteland from {@code climate.txt}) or water keeps its type, so only the
 * settleable cave floors become {@code CAVERN}. That also makes the override idempotent —
 * a province already stamped {@code CAVERN} is no longer {@code LAND}, so a re-run leaves
 * it untouched.
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

	private static final String INPUT = "data/anbennar/terrain.txt";
	private static final String PROVINCES = "src/main/resources/map/provinces.json";

	// the `cavern = {` terrain block, then its `terrain_override = { ids }` (no nested braces)
	private static final Pattern CAVERN_BLOCK = Pattern.compile("\\bcavern\\s*=\\s*\\{");
	private static final Pattern OVERRIDE = Pattern.compile(
			"terrain_override\\s*=\\s*\\{([^}]*)\\}");

	private final ObjectMapper mapper = new ObjectMapper();
	private final Set<Integer> cavernIds = new LinkedHashSet<>();

	private CavernExporter() {
	}

	public static void main(String[] args) throws Exception {
		CavernExporter exporter = new CavernExporter();
		exporter.parse();
		exporter.stampProvinces();
	}

	private void parse() throws Exception {
		String content = Files.readString(new File(INPUT).toPath());
		content = content.replaceAll("#.*", ""); // strip line comments (region labels)

		Matcher block = CAVERN_BLOCK.matcher(content);
		if (!block.find())
			throw new IllegalStateException("no `cavern` terrain block in " + INPUT);
		// the cavern block's own terrain_override is the first one after its opening brace
		Matcher ov = OVERRIDE.matcher(content);
		ov.region(block.end(), content.length());
		if (!ov.find())
			throw new IllegalStateException("cavern block has no terrain_override in " + INPUT);
		cavernIds.addAll(ids(ov.group(1)));
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

		int caverns = 0, skippedNonLand = 0;
		for (Map<String, Object> row : rows) {
			int id = ((Number) row.get("id")).intValue();
			if (!cavernIds.contains(id))
				continue;
			// CAVERN overrides only LAND (already-CAVERN / IMPASSABLE / water keep their type)
			if ("LAND".equals(row.get("type"))) {
				row.put("type", "CAVERN"); // in-place: keeps the key's position in the row
				caverns++;
			} else if (!"CAVERN".equals(row.get("type"))) {
				skippedNonLand++;
			}
		}

		mapper.writerWithDefaultPrettyPrinter().writeValue(file, rows);
		System.out.println("stamped CAVERN onto " + caverns + " provinces (skipped "
				+ skippedNonLand + " non-LAND cave-list ids) of " + rows.size()
				+ " in " + file.getAbsolutePath());
	}
}
