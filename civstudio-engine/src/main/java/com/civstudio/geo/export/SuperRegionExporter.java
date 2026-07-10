package com.civstudio.geo.export;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.civstudio.geo.SuperRegion;
import tools.jackson.databind.ObjectMapper;

/**
 * Dev tool: flattens the Anbennar {@code data/anbennar/superregion.txt} (a Clausewitz
 * file) into the {@code /superregions.json} resource the core {@link
 * com.civstudio.geo.WorldMap} loads alongside the other geographic tiers. Like
 * the sibling exporters this is a build-time/manual step whose output is
 * committed, so the running simulation never parses Clausewitz.
 * <p>
 * The source maps each super-region to its constituent regions:
 *
 * <pre>
 * bulwar_superregion = {
 *     restrict_charter        # harder to get TC here
 *     bahar_region
 *     bulwar_proper_region
 *     ...
 * }
 * </pre>
 *
 * Comments are stripped first; a single regex captures super-region key + token
 * list. Only {@code _region}-suffixed tokens are kept as members — this drops the
 * {@code restrict_charter} keyword and any stray token — and empty super-regions
 * (voided EU4-vanilla blocks) are skipped. The key is the stable {@code raw_key};
 * the display name is title-cased from it. Like {@link RegionExporter} it does not
 * touch {@code provinces.json} (a super-region holds regions, not provinces
 * directly; the {@link com.civstudio.geo.WorldMap} resolves membership through the
 * region tier). Run via:
 *
 * <pre>
 * mvn compile exec:exec -Dsim.main=com.civstudio.geo.export.SuperRegionExporter
 * </pre>
 *
 * See {@code docs/geography.md}.
 */
public final class SuperRegionExporter {

	private static final String INPUT = "data/anbennar/superregion.txt";
	private static final String OUTPUT = "src/main/resources/map/superregions.json";

	// superregion_key = { region_key region_key ... } (no nested braces)
	private static final Pattern SUPERREGION = Pattern.compile(
			"([a-zA-Z0-9_]+)\\s*=\\s*\\{([^}]*)\\}");

	private SuperRegionExporter() {
	}

	public static void main(String[] args) throws Exception {
		String content = Files.readString(new File(INPUT).toPath());
		content = content.replaceAll("#.*", ""); // strip line comments

		Matcher m = SUPERREGION.matcher(content);
		List<SuperRegion> superRegions = new ArrayList<>();
		while (m.find()) {
			String rawKey = m.group(1).trim();
			List<String> regions = new ArrayList<>();
			for (String token : m.group(2).trim().split("\\s+"))
				// keep only region members; drops restrict_charter and blanks
				if (token.endsWith("_region"))
					regions.add(token);
			if (rawKey.isEmpty() || regions.isEmpty())
				continue; // an empty placeholder super-region
			superRegions.add(new SuperRegion(rawKey, displayName(rawKey), regions));
		}

		ObjectMapper mapper = new ObjectMapper();
		File out = new File(OUTPUT);
		mapper.writerWithDefaultPrettyPrinter().writeValue(out, superRegions);
		System.out.println("wrote " + superRegions.size() + " super-regions to "
				+ out.getAbsolutePath());
	}

	/** "rahen_superregion" -&gt; "Rahen" (drop the suffix, title-case). */
	static String displayName(String rawKey) {
		String stem = rawKey.endsWith("_superregion")
				? rawKey.substring(0, rawKey.length() - "_superregion".length())
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
