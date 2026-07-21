package com.civstudio.geo.export;

import com.civstudio.data.AnbennarFiles;
import com.civstudio.data.Exports;

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
import tools.jackson.databind.ObjectMapper;

/**
 * Dev tool: reads the Anbennar country definitions and writes the committed
 * {@code src/main/resources/map/countries.json} — the per-tag display name and
 * map colour the political map joins each {@link com.civstudio.geo.Province#ownerTag()}
 * to (loaded by {@link com.civstudio.geo.WorldMap} into {@link
 * com.civstudio.geo.Country} records).
 * <p>
 * Sources under {@code common}: {@code country_tags/*.txt} maps each tag to a
 * definition file ({@code A04 = "countries/Wesdam.txt"}); the referenced
 * {@code countries/<Name>.txt} carries {@code color = { r g b }}. The display name
 * is the localised country name from {@code localisation/*countries*l_english.yml}
 * ({@code A04:0 "Wesdam"}), falling back to the definition-file stem for a tag with
 * no localised entry. A tag whose file or colour is missing falls back to a
 * deterministic hash colour so every owned province still renders.
 *
 * <pre>
 * mvn compile exec:exec -Dsim.main=com.civstudio.geo.export.CountryExporter
 * </pre>
 */
public final class CountryExporter {

	private static final String TAGS_DIR = "common/country_tags";
	private static final String COUNTRIES_DIR = "common/countries";
	private static final String LOC_DIR = "localisation";
	private static final String OUTPUT = "civstudio-engine/target/generated/map/countries.json";

	// TAG = "countries/Some Name.txt"  (the path may contain spaces)
	private static final Pattern TAG_LINE = Pattern.compile(
			"([A-Za-z0-9_]+)\\s*=\\s*\"countries/([^\"]+?)\\.txt\"");

	// a country-name localisation line:  A04:0 "Wesdam"  (leading space, version int, quoted value)
	private static final Pattern LOC_LINE = Pattern.compile(
			"^\\s*([A-Za-z0-9_]+):\\d*\\s*\"([^\"]*)\"", Pattern.MULTILINE);
	// only the English country-name loc files carry the tag → name lines we want
	private static final Pattern LOC_FILE = Pattern.compile("(?i)countries.*l_english\\.yml$");
	// EU4 in-line colour codes: §Y … §!  — strip them out of a display name
	private static final Pattern COLOR_CODE = Pattern.compile("§.");

	private CountryExporter() {
	}

	public static void main(String[] args) throws Exception {
		Map<String, String> tagToStem = new LinkedHashMap<>();
		File[] tagFiles = AnbennarFiles.getDir(TAGS_DIR).toFile().listFiles((d, n) -> n.endsWith(".txt"));
		if (tagFiles == null)
			throw new IllegalStateException("country_tags dir not found: " + TAGS_DIR);
		File countriesDir = AnbennarFiles.getDir(COUNTRIES_DIR).toFile();
		for (File f : tagFiles) {
			String content = ClausewitzBlocks.stripComments(
					Files.readString(f.toPath(), StandardCharsets.ISO_8859_1));
			Matcher m = TAG_LINE.matcher(content);
			while (m.find())
				tagToStem.putIfAbsent(m.group(1), m.group(2));
		}

		Map<String, String> tagToName = loadLocNames();

		List<Country> countries = new ArrayList<>();
		int withColor = 0, fallback = 0, named = 0;
		for (Map.Entry<String, String> e : tagToStem.entrySet()) {
			String tag = e.getKey();
			String stem = e.getValue();
			String color = readColor(new File(countriesDir, stem + ".txt"));
			if (color == null) {
				color = fallbackColor(tag);
				fallback++;
			} else {
				withColor++;
			}
			String name = tagToName.get(tag);
			if (name != null && !name.isBlank())
				named++;
			else
				name = stem;
			countries.add(new Country(tag, name, color));
		}
		countries.sort((a, b) -> a.tag().compareTo(b.tag()));

		File out = Exports.outFile(OUTPUT);
		new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(out, countries);
		System.out.println("wrote " + countries.size() + " countries (" + withColor
				+ " with source colour, " + fallback + " fallback; " + named
				+ " localised names, " + (countries.size() - named) + " stem fallback) to "
				+ out.getAbsolutePath());
	}

	/**
	 * Tag → localised English display name, read from every {@code *countries*l_english.yml}
	 * localisation file (e.g. {@code A04:0 "Wesdam"}). Later files win, so a mod override of a
	 * vanilla name takes effect. Colour codes ({@code §Y…§!}) are stripped.
	 */
	private static Map<String, String> loadLocNames() throws Exception {
		Map<String, String> names = new LinkedHashMap<>();
		for (String blob : AnbennarFiles.list(LOC_DIR)) {
			if (!LOC_FILE.matcher(blob).find())
				continue;
			// EU4 localisation YAML is UTF-8 (unlike the Latin-1 Clausewitz .txt above) — read it as
			// such or accented names (Rüng, Siádar, Galéinn) arrive as mojibake
			String content = Files.readString(
					AnbennarFiles.get(blob), StandardCharsets.UTF_8);
			Matcher m = LOC_LINE.matcher(content);
			while (m.find()) {
				String value = COLOR_CODE.matcher(m.group(2)).replaceAll("").trim();
				if (!value.isEmpty())
					names.put(m.group(1), value);
			}
		}
		return names;
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
