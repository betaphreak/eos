package com.civstudio.mission.export;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.civstudio.data.AnbennarFiles;
import com.civstudio.geo.export.ClausewitzBlocks;
import com.civstudio.geo.export.ClausewitzBlocks.Block;
import com.civstudio.geo.export.ClausewitzBlocks.Parsed;
import com.civstudio.mission.Mission;
import com.civstudio.mission.MissionSeries;

import tools.jackson.databind.ObjectMapper;

/**
 * Dev tool: imports every Anbennar mission tree — {@code missions/*.txt} for <b>all</b> countries —
 * into a single {@code generated/missions.json}, with all the fields the campaign selector and any
 * later mission-tree import need (see {@code docs/campaign-selector.md} §What the missions can give).
 * <p>
 * Sources, via the {@link AnbennarFiles} seam (cached; the {@code .anbennar-cache} junction makes it a
 * local read on the dev box): the mission scripts are Clausewitz {@code .txt} (Latin-1), parsed with
 * {@link ClausewitzBlocks}; the localised titles/descriptions come from {@code
 * localisation/*_missions_l_english.yml} (UTF-8), keyed {@code <mission>_title}/{@code _desc}.
 *
 * <pre>
 * mvn -pl civstudio-engine exec:exec -Dsim.main=com.civstudio.mission.export.MissionExporter
 * </pre>
 *
 * The mission DSL itself (triggers/effects) is <b>not</b> modelled — those blocks are captured as raw
 * (whitespace-collapsed) text, so nothing is lost while we stop short of re-implementing EU4 script.
 */
public final class MissionExporter {

	private static final String MISSIONS_DIR = "missions";
	private static final String LOC_DIR = "localisation";
	private static final String OUTPUT = "civstudio-engine/target/generated/missions.json";

	// a localisation line:  arakeprun_ruins_of_greatness_title:0 "Ruins of Greatness"
	private static final Pattern LOC_LINE = Pattern.compile(
			"^\\s*([A-Za-z0-9_.]+):\\d*\\s*\"(.*)\"\\s*$", Pattern.MULTILINE);
	// EVERY English loc file — mission titles/descs live in per-nation/region loc files, not only the
	// handful named "*mission*". We keep only <key>_title / <key>_desc keys, so unrelated entries are
	// harmless; a mission's key is unique enough that a stray non-mission collision is a non-issue.
	private static final Pattern LOC_FILE = Pattern.compile("(?i)l_english\\.yml$");
	private static final Pattern COLOR_CODE = Pattern.compile("§.");   // EU4 in-line colour codes §Y…§!
	private static final Pattern PROVINCE_ID = Pattern.compile("province_id\\s*=\\s*(\\d+)");

	private MissionExporter() {
	}

	public static void main(String[] args) throws Exception {
		Path out = Path.of(args.length > 0 ? args[0] : OUTPUT);

		Map<String, String> titles = new LinkedHashMap<>();
		Map<String, String> descs = new LinkedHashMap<>();
		loadMissionLoc(titles, descs);

		File[] files = AnbennarFiles.getDir(MISSIONS_DIR).toFile().listFiles((d, n) -> n.endsWith(".txt"));
		if (files == null)
			throw new IllegalStateException("missions dir not found: " + MISSIONS_DIR);
		java.util.Arrays.sort(files);   // stable output order

		List<MissionSeries> all = new ArrayList<>();
		for (File f : files)
			all.addAll(parseContent(Files.readString(f.toPath(), StandardCharsets.ISO_8859_1),
					f.getName(), titles, descs));

		int nMissions = 0, withTag = 0, withLoc = 0;
		for (MissionSeries s : all) {
			nMissions += s.missions().size();
			if (s.tag() != null)
				withTag++;
			for (Mission m : s.missions())
				if (m.title() != null)
					withLoc++;
		}

		Files.createDirectories(out.getParent());
		new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(out.toFile(), all);
		System.out.println("wrote " + all.size() + " mission series (" + withTag + " tag-gated), "
				+ nMissions + " missions (" + withLoc + " localised) from " + files.length
				+ " files to " + out.toAbsolutePath());
	}

	/**
	 * Parse one mission file's raw content into its series — the unit tests drive this directly (no
	 * network). A mission file holds only mission series at the top level; each series is a {@code
	 * slot=…, potential={…}, <mission>={…}…} block.
	 *
	 * @param rawContent the file's text (comments still in — stripped here)
	 * @param file       the source file name, for provenance
	 * @param titles     mission-key → localised title (may be empty)
	 * @param descs      mission-key → localised description (may be empty)
	 * @return the file's mission series
	 */
	static List<MissionSeries> parseContent(String rawContent, String file, Map<String, String> titles,
			Map<String, String> descs) {
		List<MissionSeries> out = new ArrayList<>();
		String content = ClausewitzBlocks.stripComments(rawContent);
		for (Block top : ClausewitzBlocks.parse(content).blocks()) {
			Parsed series = ClausewitzBlocks.parse(top.body());
			if (!series.scalars().containsKey("slot"))
				continue;   // not a mission series (mission files hold only these at the top level)
			String tag = null, potential = null;
			List<Mission> missions = new ArrayList<>();
			for (Block b : series.blocks()) {
				if (b.name().equals("potential")) {
					potential = collapse(b.body());
					tag = ClausewitzBlocks.parse(b.body()).scalars().get("tag");
					continue;
				}
				Parsed mb = ClausewitzBlocks.parse(b.body());
				if (isMission(mb))
					missions.add(mission(b.name(), mb, titles, descs));
			}
			out.add(new MissionSeries(top.name(), tag, intOrNull(series.scalars().get("slot")),
					yes(series, "generic"), yes(series, "ai"), yes(series, "has_country_shield"),
					potential, file, missions));
		}
		return out;
	}

	// a mission node carries an icon and/or a position; everything else in a series body (potential,
	// the odd stray block) is not a mission
	private static boolean isMission(Parsed mb) {
		return mb.scalars().containsKey("icon") || mb.scalars().containsKey("position");
	}

	private static Mission mission(String key, Parsed mb, Map<String, String> titles,
			Map<String, String> descs) {
		List<String> required = tokens(blockBody(mb, "required_missions"));
		List<Integer> highlight = provinceIds(blockBody(mb, "provinces_to_highlight"));
		return new Mission(key, titles.get(key), descs.get(key), mb.scalars().get("icon"),
				intOrNull(mb.scalars().get("position")), required, highlight,
				collapse(blockBody(mb, "trigger")), collapse(blockBody(mb, "effect")));
	}

	// tag → localised title/description, from every *mission*l_english.yml. Later files win.
	private static void loadMissionLoc(Map<String, String> titles, Map<String, String> descs)
			throws Exception {
		for (String blob : AnbennarFiles.list(LOC_DIR)) {
			if (!LOC_FILE.matcher(blob).find())
				continue;
			String content = Files.readString(AnbennarFiles.get(blob), StandardCharsets.UTF_8);
			Matcher m = LOC_LINE.matcher(content);
			while (m.find()) {
				String key = m.group(1);
				String value = COLOR_CODE.matcher(m.group(2)).replaceAll("").trim();
				if (value.isEmpty())
					continue;
				if (key.endsWith("_title"))
					titles.put(key.substring(0, key.length() - "_title".length()), value);
				else if (key.endsWith("_desc"))
					descs.put(key.substring(0, key.length() - "_desc".length()), value);
			}
		}
	}

	// the raw body of a named block within a parsed node, or null
	private static String blockBody(Parsed p, String name) {
		for (Block b : p.blocks())
			if (b.name().equals(name))
				return b.body();
		return null;
	}

	// bare-token list ({ a b c } → [a, b, c]); the parser leaves list elements unassigned, so split
	private static List<String> tokens(String body) {
		List<String> out = new ArrayList<>();
		if (body == null)
			return out;
		for (String t : body.trim().split("\\s+"))
			if (!t.isEmpty() && !t.equals("{") && !t.equals("}") && !t.equals("="))
				out.add(t);
		return out;
	}

	private static List<Integer> provinceIds(String body) {
		List<Integer> out = new ArrayList<>();
		if (body == null)
			return out;
		Matcher m = PROVINCE_ID.matcher(body);
		while (m.find())
			out.add(Integer.parseInt(m.group(1)));
		return out;
	}

	private static boolean yes(Parsed p, String key) {
		return "yes".equals(p.scalars().get(key));
	}

	private static Integer intOrNull(String s) {
		if (s == null)
			return null;
		try {
			return Integer.valueOf(s.trim());
		} catch (NumberFormatException e) {
			return null;
		}
	}

	// collapse a raw Clausewitz body to a single line of tokens (trigger/effect are kept verbatim but
	// compact — the DSL is preserved, not modelled)
	private static String collapse(String s) {
		if (s == null)
			return null;
		String t = s.replaceAll("\\s+", " ").trim();
		return t.isEmpty() ? null : t;
	}
}
