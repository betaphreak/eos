package com.civstudio.name.export;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Dev tool: converts the Anbennar {@code anb_cultures.txt} Paradox-script file
 * into the eos tiered, weighted name-table JSON resources that
 * {@link com.civstudio.name.NameTable} loads, grouped per race under
 * {@code /names/<race>/} (e.g. {@code /names/elven/dynasty.json},
 * {@code /names/elven/male.json}, {@code /names/elven/female.json}).
 * <p>
 * The Anbennar file is structured {@code race = { culture = { dynasty_names,
 * male_names, female_names } }} and is <b>Windows-1252</b> encoded (so accented
 * Latin names like {@code Síl} round-trip). Per the design decisions for this
 * import:
 * <ul>
 *   <li><b>Per race</b> — every culture under a top-level race key is merged
 *       into one name set per race; the {@code culture}/{@code country} structure
 *       is dropped (it is deliberately not modeled in eos).</li>
 *   <li><b>Front-loaded common&rarr;rare weights</b> — the source lists carry no
 *       frequency data, so a rarity curve is synthesized: a name is ranked by how
 *       many cultures of its race use it (descending), ties broken by first
 *       appearance, then assigned a Zipfian weight {@code 1/rank}. This mirrors
 *       the common&rarr;rare shape of the hand-built {@code *-human.json} tables.
 *       Names sharing a rounded rarity band are bucketed into one tier, exactly
 *       as the rare tail of the existing tables is.</li>
 * </ul>
 * Races that are sparse or degenerate — missing one of the three name kinds, or
 * with fewer than {@value #MIN_NAMES} names in any kind — are dropped and
 * reported. It <b>never overwrites an existing resource</b>: a race whose folder
 * files already exist (e.g. the curated {@code /names/harimari/} tables) is
 * skipped, so curated data is preserved.
 * <p>
 * Run via:
 *
 * <pre>
 * mvn exec:exec -Dsim.main=com.civstudio.name.export.AnbennarNameExporter
 * </pre>
 *
 * (the forked JVM's CWD is the project root, so the relative resource paths
 * resolve, matching {@link com.civstudio.geo.export.ProvinceExporter}).
 */
public final class AnbennarNameExporter {

	// build-time input (not a runtime resource, so it is not bundled into the jar)
	private static final Path INPUT = Path.of("data/anb_cultures.txt");
	// name tables are grouped per race: src/main/resources/names/<race>/<kind>.json
	private static final String NAMES_DIR = "src/main/resources/names";

	// the three name kinds and their eos file names (within a race's folder)
	private static final String[][] KINDS = {
			{ "dynasty_names", "dynasty" },
			{ "male_names", "male" },
			{ "female_names", "female" },
	};

	// a race is dropped as sparse/degenerate unless it has all three name kinds
	// and each lists at least this many distinct names (a weighted rarity table
	// needs more than a handful of names to be meaningful)
	private static final int MIN_NAMES = 10;

	// total cumulative percent the rarity scale spans (matches the ~99 the
	// hand-built human/harimari tables use as a percentile scale)
	private static final double SCALE = 99.0;

	// a top-level race definition: an identifier at column 0 followed by '= {'
	private static final Pattern RACE = Pattern.compile(
			"^([A-Za-z_][A-Za-z0-9_]*)[ \\t]*=[ \\t]*\\{", Pattern.MULTILINE);

	// a name list of a given kind anywhere within a race block; name lists never
	// contain nested braces, so [^}]* to the first '}' is exact
	private static String kindBlockRegex(String kind) {
		return Pattern.quote(kind) + "[ \\t]*=[ \\t]*\\{([^}]*)\\}";
	}

	// a single name token: a quoted (possibly multi-word) name, or a bare word
	private static final Pattern TOKEN = Pattern.compile("\"([^\"]*)\"|(\\S+)");

	private AnbennarNameExporter() {
	}

	public static void main(String[] args) throws IOException {
		String content = Files.readString(INPUT, Charset.forName("windows-1252"));
		ObjectMapper mapper = new ObjectMapper();

		int racesWritten = 0, filesWritten = 0, racesSkippedSparse = 0, racesSkippedExisting = 0;

		Matcher m = RACE.matcher(content);
		int consumedTo = 0; // ignore col-0 matches that fall inside the race we just consumed
		while (m.find()) {
			if (m.start() < consumedTo)
				continue;
			String race = m.group(1);
			int bodyStart = m.end();
			int bodyEnd = blockEnd(content, bodyStart);
			consumedTo = bodyEnd;
			String body = content.substring(bodyStart, bodyEnd);

			// gather, per kind, the merged name set and a cross-culture frequency
			List<KindResult> results = new ArrayList<>();
			for (String[] kind : KINDS) {
				List<RankedName> ranked = collect(body, kind[0]);
				if (!ranked.isEmpty())
					results.add(new KindResult(kind[1], ranked));
			}

			// drop sparse/degenerate races: must have all three kinds, each with
			// at least MIN_NAMES distinct names
			boolean complete = results.size() == KINDS.length
					&& results.stream().allMatch(kr -> kr.names.size() >= MIN_NAMES);
			if (!complete) {
				racesSkippedSparse++;
				System.out.printf("%-28s DROP sparse (%s)%n", race, summary(results, List.of()));
				continue;
			}

			// never overwrite an existing race folder's files (e.g. curated harimari)
			File dir = new File(NAMES_DIR, race);
			boolean wroteAny = false;
			List<String> skippedKinds = new ArrayList<>();
			for (KindResult kr : results) {
				File out = new File(dir, kr.prefix + ".json");
				if (out.exists()) {
					skippedKinds.add(kr.prefix);
					continue;
				}
				dir.mkdirs();
				mapper.writerWithDefaultPrettyPrinter().writeValue(out, toTiers(kr.names));
				filesWritten++;
				wroteAny = true;
			}
			if (wroteAny) {
				racesWritten++;
				System.out.printf("%-28s %s%n", race, summary(results, skippedKinds));
			} else {
				racesSkippedExisting++;
				System.out.printf("%-28s SKIP (all target files exist)%n", race);
			}
		}

		System.out.printf(
				"%ndone: %d races written (%d files); %d dropped sparse; %d skipped (existing)%n",
				racesWritten, filesWritten, racesSkippedSparse, racesSkippedExisting);
	}

	/** Collect a kind's names across every culture in a race, ranked common&rarr;rare. */
	private static List<RankedName> collect(String body, String kind) {
		// frequency = number of culture blocks that list the name; order = first seen
		Map<String, Integer> freq = new LinkedHashMap<>();
		Matcher b = Pattern.compile(kindBlockRegex(kind)).matcher(body);
		while (b.find()) {
			String raw = b.group(1).replaceAll("(?m)#.*", ""); // strip inline comments
			// dedupe within one culture block before counting it once toward frequency
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
		// common -> rare: by frequency desc, then first-seen asc (stable)
		names.sort((x, y) -> {
			int c = Integer.compare(y.freq, x.freq);
			return (c != 0) ? c : Integer.compare(x.firstSeen, y.firstSeen);
		});
		return names;
	}

	/**
	 * Build the eos tier list: a leading empty anchor tier, then names walked in
	 * common&rarr;rare order with a Zipfian {@code 1/rank} weight, normalized so
	 * the cumulative band reaches {@link #SCALE}. Consecutive names that round to
	 * the same cumulative percent are bucketed into one tier (the rare tail).
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
				percent = prevPercent + (bucket.isEmpty() ? 1 : 0); // keep strictly increasing across tiers
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
		Map<String, Object> t = new LinkedHashMap<>(); // preserve field order
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
		return i - 1; // position of the closing '}'
	}

	private static String summary(List<KindResult> results, List<String> skipped) {
		StringBuilder sb = new StringBuilder();
		for (KindResult kr : results)
			sb.append(kr.prefix).append('=').append(kr.names.size()).append(' ');
		if (!skipped.isEmpty())
			sb.append("(kept existing: ").append(String.join(",", skipped)).append(')');
		return sb.toString().trim();
	}

	private record RankedName(String name, int freq, int firstSeen) {
	}

	private record KindResult(String prefix, List<RankedName> names) {
	}
}
