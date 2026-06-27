package com.civstudio.geo.export;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.civstudio.geo.Region;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Dev tool: flattens the Anbennar {@code data/region.txt} (a Clausewitz file)
 * into the {@code /regions.json} resource the core {@link
 * com.civstudio.geo.WorldMap} loads alongside {@code provinces.json}. Like {@link
 * ProvinceExporter} this is a build-time/manual step whose output is committed to
 * the repo, so the running simulation never parses Clausewitz — it reads plain
 * JSON.
 * <p>
 * The source maps each region to its constituent areas:
 *
 * <pre>
 * rahen_coast_region = {
 *     areas = { inner_rahen_area ... }
 *     monsoon = { 00.01.01 00.04.30 }   # optional, ignored
 * }
 * </pre>
 *
 * {@code areas} is always the first key in a region body and its members carry no
 * nested braces, so a single regex captures region key + area list; comments and
 * the dated {@code monsoon} sub-blocks are stripped first so nothing precedes
 * {@code areas}. The empty {@code random_new_world_region} (no areas) is skipped.
 * The region key is the stable {@code raw_key} {@link
 * com.civstudio.geo.Province#regionKey()} references; the display name is
 * title-cased from it. Run via:
 *
 * <pre>
 * mvn compile exec:exec -Dsim.main=com.civstudio.geo.export.RegionExporter
 * </pre>
 *
 * See {@code docs/geography.md}.
 */
public final class RegionExporter {

	private static final String INPUT = "data/region.txt";
	private static final String OUTPUT = "src/main/resources/map/regions.json";

	// region_key = { areas = { area1 area2 ... } ...rest of body ignored }
	// (comments + monsoon blocks are stripped first, so 'areas' leads the body
	// and its closing brace is the first '}' the area capture meets).
	private static final Pattern REGION = Pattern.compile(
			"([a-zA-Z0-9_]+)\\s*=\\s*\\{\\s*areas\\s*=\\s*\\{([^}]*)\\}");

	private RegionExporter() {
	}

	public static void main(String[] args) throws Exception {
		String content = Files.readString(new File(INPUT).toPath());
		content = content.replaceAll("#.*", ""); // strip line comments
		// strip the dated monsoon sub-blocks (no nested braces) so 'areas' is
		// the first key in every region body
		content = content.replaceAll("(?s)monsoon\\s*=\\s*\\{[^}]*\\}", "");

		Matcher m = REGION.matcher(content);
		List<Region> regions = new ArrayList<>();
		while (m.find()) {
			String rawKey = m.group(1).trim();
			List<String> areas = new ArrayList<>();
			for (String token : m.group(2).trim().split("\\s+"))
				if (!token.isEmpty())
					areas.add(token);
			if (rawKey.isEmpty() || areas.isEmpty())
				continue; // the empty RNW region, or a malformed body
			regions.add(new Region(rawKey, displayName(rawKey), areas));
		}

		ObjectMapper mapper = new ObjectMapper();
		File out = new File(OUTPUT);
		mapper.writerWithDefaultPrettyPrinter().writeValue(out, regions);
		System.out.println(
				"wrote " + regions.size() + " regions to " + out.getAbsolutePath());
	}

	/** "rahen_coast_region" -&gt; "Rahen Coast" (drop the suffix, title-case). */
	static String displayName(String rawKey) {
		String stem = rawKey.endsWith("_region")
				? rawKey.substring(0, rawKey.length() - "_region".length())
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
