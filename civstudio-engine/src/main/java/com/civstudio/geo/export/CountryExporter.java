package com.civstudio.geo.export;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.civstudio.geo.Country;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Dev tool: reads the Anbennar country definitions and writes the committed
 * {@code src/main/resources/map/countries.json} — the per-tag display name and
 * map colour the political map joins each {@link com.civstudio.geo.Province#ownerTag()}
 * to (loaded by {@link com.civstudio.geo.WorldMap} into {@link
 * com.civstudio.geo.Country} records).
 * <p>
 * Sources under {@code data/anbennar/common}: {@code country_tags/*.txt} maps each
 * tag to a definition file ({@code A04 = "countries/Wesdam.txt"}); the referenced
 * {@code countries/<Name>.txt} carries {@code color = { r g b }}. The display name
 * is the definition-file stem (the files carry no name field). A tag whose file or
 * colour is missing falls back to a deterministic hash colour so every owned
 * province still renders.
 *
 * <pre>
 * mvn compile exec:exec -Dsim.main=com.civstudio.geo.export.CountryExporter
 * </pre>
 */
public final class CountryExporter {

	private static final String TAGS_DIR = "data/anbennar/common/country_tags";
	private static final String COUNTRIES_DIR = "data/anbennar/common/countries";
	private static final String OUTPUT = "src/main/resources/map/countries.json";

	// TAG = "countries/Some Name.txt"  (the path may contain spaces)
	private static final Pattern TAG_LINE = Pattern.compile(
			"([A-Za-z0-9_]+)\\s*=\\s*\"countries/([^\"]+?)\\.txt\"");

	private CountryExporter() {
	}

	public static void main(String[] args) throws Exception {
		Map<String, String> tagToStem = new LinkedHashMap<>();
		File[] tagFiles = new File(TAGS_DIR).listFiles((d, n) -> n.endsWith(".txt"));
		if (tagFiles == null)
			throw new IllegalStateException("country_tags dir not found: " + TAGS_DIR);
		for (File f : tagFiles) {
			String content = ClausewitzBlocks.stripComments(
					Files.readString(f.toPath(), StandardCharsets.ISO_8859_1));
			Matcher m = TAG_LINE.matcher(content);
			while (m.find())
				tagToStem.putIfAbsent(m.group(1), m.group(2));
		}

		List<Country> countries = new ArrayList<>();
		int withColor = 0, fallback = 0;
		for (Map.Entry<String, String> e : tagToStem.entrySet()) {
			String tag = e.getKey();
			String stem = e.getValue();
			String color = readColor(new File(COUNTRIES_DIR, stem + ".txt"));
			if (color == null) {
				color = fallbackColor(tag);
				fallback++;
			} else {
				withColor++;
			}
			countries.add(new Country(tag, stem, color));
		}
		countries.sort((a, b) -> a.tag().compareTo(b.tag()));

		File out = new File(OUTPUT);
		new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(out, countries);
		System.out.println("wrote " + countries.size() + " countries (" + withColor
				+ " with source colour, " + fallback + " fallback) to "
				+ out.getAbsolutePath());
	}

	private static String readColor(File file) throws Exception {
		if (!file.isFile())
			return null;
		String content = ClausewitzBlocks.stripComments(
				Files.readString(file.toPath(), StandardCharsets.ISO_8859_1));
		for (ClausewitzBlocks.Block b : ClausewitzBlocks.parse(content).blocks())
			if (b.name().equals("color"))
				return ClausewitzBlocks.colorHex(b.body());
		return null;
	}

	/** A deterministic, mid-tone colour derived from the tag, for tags with no source colour. */
	private static String fallbackColor(String tag) {
		int h = tag.hashCode();
		int r = 60 + Math.floorMod(h, 160);
		int g = 60 + Math.floorMod(h >> 8, 160);
		int b = 60 + Math.floorMod(h >> 16, 160);
		return String.format("#%02x%02x%02x", r, g, b);
	}
}
