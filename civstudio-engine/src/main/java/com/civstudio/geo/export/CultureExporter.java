package com.civstudio.geo.export;

import com.civstudio.data.AnbennarFiles;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.civstudio.geo.Culture;
import tools.jackson.databind.ObjectMapper;

/**
 * Dev tool: reads the Anbennar {@code data/anbennar/anb_cultures.txt} and writes
 * the committed {@code src/main/resources/map/cultures.json} — the per-key display
 * name, culture group and map colour the culture layer joins each {@link
 * com.civstudio.geo.Province#culture()} to (loaded by {@link
 * com.civstudio.geo.WorldMap} into {@link com.civstudio.geo.Culture} records).
 * <p>
 * The source is nested {@code culture_group = { culture_key = { ... } ... }}. Each
 * depth-1 block is a culture, except the shared name lists ({@code dynasty_names},
 * {@code male_names}, {@code female_names}) that a group may carry alongside its
 * cultures. EU4 culture files carry no colour, so a stable colour is generated
 * from the culture key.
 *
 * <pre>
 * mvn compile exec:exec -Dsim.main=com.civstudio.geo.export.CultureExporter
 * </pre>
 */
public final class CultureExporter {

	private static final String INPUT = "common/cultures/anb_cultures.txt";
	private static final String OUTPUT = "src/main/resources/map/cultures.json";

	// group-level blocks that are shared name lists, not cultures
	private static final Set<String> NOT_CULTURE = Set.of(
			"dynasty_names", "male_names", "female_names");

	private CultureExporter() {
	}

	public static void main(String[] args) throws Exception {
		String content = ClausewitzBlocks.stripComments(
				Files.readString(AnbennarFiles.get(INPUT), StandardCharsets.ISO_8859_1));

		List<Culture> cultures = new ArrayList<>();
		for (ClausewitzBlocks.Block group : ClausewitzBlocks.parse(content).blocks()) {
			for (ClausewitzBlocks.Block cul : ClausewitzBlocks.parse(group.body()).blocks()) {
				if (NOT_CULTURE.contains(cul.name()))
					continue;
				cultures.add(new Culture(cul.name(),
						ClausewitzBlocks.titleCase(cul.name()), group.name(),
						autoColor(cul.name())));
			}
		}
		cultures.sort((a, b) -> a.key().compareTo(b.key()));

		File out = new File(OUTPUT);
		new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(out, cultures);
		System.out.println("wrote " + cultures.size() + " cultures to "
				+ out.getAbsolutePath());
	}

	/** A deterministic, mid-tone colour derived from the culture key (source has none). */
	private static String autoColor(String key) {
		int h = key.hashCode();
		int r = 60 + Math.floorMod(h, 160);
		int g = 60 + Math.floorMod(h >> 8, 160);
		int b = 60 + Math.floorMod(h >> 16, 160);
		return String.format("#%02x%02x%02x", r, g, b);
	}
}
