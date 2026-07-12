package com.civstudio.name;

import com.civstudio.data.AnbennarFiles;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Generates a race's tiered, weighted name tables from the Anbennar {@code common/cultures/anb_cultures.txt}
 * Paradox-script file (fetched on demand by {@link AnbennarFiles}). This is the <b>only</b> source for
 * non-human names: they are not vendored, but produced on first use and cached by {@link NameStore}
 * (the human names are the sole hand-authored set — {@code /human-names/} — and are never generated).
 * <p>
 * The Anbennar file is structured {@code race = { culture = { dynasty_names, male_names, female_names } }}
 * and is <b>Windows-1252</b> encoded (so accented names like {@code Síl} round-trip). Per the import design:
 * <ul>
 *   <li><b>Per race</b> — every culture under a top-level race key is merged into one set per race; the
 *       {@code culture}/{@code country} structure is dropped (it is not modeled in eos).</li>
 *   <li><b>Front-loaded common&rarr;rare weights</b> — the lists carry no frequency data, so a rarity
 *       curve is synthesized: a name is ranked by how many cultures of its race use it (descending),
 *       ties by first appearance, then given a Zipfian {@code 1/rank} weight normalized to {@link #SCALE}.
 *       Consecutive names rounding to one band share a tier, matching the hand-built human tables.</li>
 * </ul>
 * A race missing one of the three kinds, or with fewer than {@value #MIN_NAMES} names in any kind, is
 * <b>sparse</b>: {@link #generate} returns an empty map and the caller falls back to human names.
 * <p>
 * (This is the runtime successor to the old {@code AnbennarNameExporter} dev tool, whose per-race JSON
 * output is now produced on demand rather than committed.)
 */
public final class RaceNameGenerator {

	private static final String INPUT = "common/cultures/anb_cultures.txt";

	// the three name kinds and their eos file/prefix names
	static final String[][] KINDS = {
			{ "dynasty_names", "dynasty" },
			{ "male_names", "male" },
			{ "female_names", "female" },
	};

	/** A race is sparse (→ human fallback) unless every kind lists at least this many distinct names. */
	private static final int MIN_NAMES = 10;

	/** Cumulative percent the rarity scale spans (matches the hand-built human tables' percentile scale). */
	private static final double SCALE = 99.0;

	// a top-level race definition: an identifier at column 0 followed by '= {'
	private static final Pattern RACE = Pattern.compile(
			"^([A-Za-z_][A-Za-z0-9_]*)[ \\t]*=[ \\t]*\\{", Pattern.MULTILINE);
	// a single name token: a quoted (possibly multi-word) name, or a bare word
	private static final Pattern TOKEN = Pattern.compile("\"([^\"]*)\"|(\\S+)");

	private RaceNameGenerator() {
	}

	private static String kindBlockRegex(String kind) {
		return Pattern.quote(kind) + "[ \\t]*=[ \\t]*\\{([^}]*)\\}";
	}

	/**
	 * Generate the tiered name tables for a race, keyed by eos kind ({@code dynasty}/{@code male}/{@code
	 * female}). Returns an <b>empty</b> map if the race is absent from the Anbennar source or is sparse
	 * (so the caller falls back to human names). Each value is the tier list ready to serialize as the
	 * {@code /<race>/<kind>.json} the {@link NameTable} loads.
	 *
	 * @param raceId the eos race id (a top-level culture-group key in {@code anb_cultures.txt})
	 * @return kind &rarr; tier list, or empty if not generatable
	 */
	public static Map<String, List<Map<String, Object>>> generate(String raceId) {
		String content = read();
		String body = raceBody(content, raceId);
		if (body == null)
			return Map.of();

		Map<String, List<Map<String, Object>>> out = new LinkedHashMap<>();
		for (String[] kind : KINDS) {
			List<RankedName> ranked = collect(body, kind[0]);
			if (ranked.size() < MIN_NAMES)
				return Map.of(); // missing/sparse kind → not generatable, fall back to human
			out.put(kind[1], toTiers(ranked));
		}
		return out;
	}

	private static String read() {
		try {
			return Files.readString(AnbennarFiles.get(INPUT), Charset.forName("windows-1252"));
		} catch (IOException e) {
			throw new UncheckedIOException("failed to read Anbennar cultures for name generation", e);
		}
	}

	/** The body of the top-level {@code raceId = { ... }} block, or null if there is none. */
	private static String raceBody(String content, String raceId) {
		Matcher m = RACE.matcher(content);
		int consumedTo = 0;
		while (m.find()) {
			if (m.start() < consumedTo)
				continue; // a col-0 match inside the block we just consumed
			int bodyStart = m.end();
			int bodyEnd = blockEnd(content, bodyStart);
			consumedTo = bodyEnd;
			if (m.group(1).equals(raceId))
				return content.substring(bodyStart, bodyEnd);
		}
		return null;
	}

	/** Collect a kind's names across every culture in a race, ranked common&rarr;rare. */
	private static List<RankedName> collect(String body, String kind) {
		Map<String, Integer> freq = new LinkedHashMap<>(); // name -> # culture blocks listing it
		Matcher b = Pattern.compile(kindBlockRegex(kind)).matcher(body);
		while (b.find()) {
			String raw = b.group(1).replaceAll("(?m)#.*", ""); // strip inline comments
			LinkedHashSet<String> inBlock = new LinkedHashSet<>();
			Matcher t = TOKEN.matcher(raw);
			while (t.find()) {
				String name = (t.group(1) != null) ? t.group(1) : t.group(2);
				if (name != null) {
					name = name.trim();
					if (!name.isEmpty())
						inBlock.add(name);
				}
			}
			for (String name : inBlock)
				freq.merge(name, 1, Integer::sum);
		}

		List<RankedName> names = new ArrayList<>();
		int order = 0;
		for (Map.Entry<String, Integer> e : freq.entrySet())
			names.add(new RankedName(e.getKey(), e.getValue(), order++));
		names.sort((x, y) -> {
			int c = Integer.compare(y.freq, x.freq);
			return (c != 0) ? c : Integer.compare(x.firstSeen, y.firstSeen);
		});
		return names;
	}

	/**
	 * Build the eos tier list: a leading empty anchor tier, then names walked common&rarr;rare with a
	 * Zipfian {@code 1/rank} weight normalized so the cumulative band reaches {@link #SCALE}. Names that
	 * round to the same cumulative percent share a tier (the rare tail).
	 */
	private static List<Map<String, Object>> toTiers(List<RankedName> names) {
		int n = names.size();
		double harmonic = 0;
		for (int r = 1; r <= n; r++)
			harmonic += 1.0 / r;
		double factor = SCALE / harmonic;

		List<Map<String, Object>> tiers = new ArrayList<>();
		tiers.add(tier(0, new ArrayList<>(), null)); // anchor: prev=null, no names

		double cum = 0;
		int prevPercent = 0;
		List<String> bucket = new ArrayList<>();
		int bucketPercent = -1;
		for (int i = 0; i < n; i++) {
			cum += factor / (i + 1);
			int percent = (int) Math.round(cum);
			if (percent <= prevPercent)
				percent = prevPercent + (bucket.isEmpty() ? 1 : 0);
			if (bucket.isEmpty()) {
				bucketPercent = percent;
			} else if (percent != bucketPercent) {
				tiers.add(tier(bucketPercent, bucket, prevPercent));
				prevPercent = bucketPercent;
				bucket = new ArrayList<>();
				bucketPercent = percent;
			}
			bucket.add(names.get(i).name);
		}
		if (!bucket.isEmpty())
			tiers.add(tier(bucketPercent, bucket, prevPercent));
		return tiers;
	}

	private static Map<String, Object> tier(int percent, List<String> names, Integer prev) {
		Map<String, Object> t = new LinkedHashMap<>();
		t.put("percent", percent);
		t.put("names", names);
		t.put("size", names.size());
		t.put("prev", prev);
		return t;
	}

	/** Index just past the matching '}' for the '{' that opened at {@code afterBrace}. */
	private static int blockEnd(String s, int afterBrace) {
		int depth = 1, i = afterBrace;
		while (i < s.length() && depth > 0) {
			char c = s.charAt(i++);
			if (c == '{')
				depth++;
			else if (c == '}')
				depth--;
		}
		return i - 1;
	}

	private record RankedName(String name, int freq, int firstSeen) {
	}
}
