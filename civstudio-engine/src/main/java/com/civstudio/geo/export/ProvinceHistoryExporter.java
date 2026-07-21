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

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Dev tool: reads the Anbennar {@code data/anbennar/history/provinces/*.txt}
 * (Clausewitz province-history files) and stamps each province's political
 * attributes onto {@code map/provinces.json} — the {@code owner}/{@code
 * controller}/{@code culture}/{@code religion} keys ({@link
 * com.civstudio.geo.Province#ownerTag()} / {@code controllerTag()} / {@code
 * culture()} / {@code religion()}) and its game-start development ({@code
 * base_tax}/{@code base_production}/{@code base_manpower} — EU4 ADM/DIP/MIL, see
 * {@link com.civstudio.geo.Province#development()}). Like the sibling geo stampers this is
 * a build-time/manual step whose output is committed.
 * <p>
 * Each source file is named {@code <id> - <Name>.txt} and carries the values at
 * the top level (e.g. {@code owner = A04}, {@code culture = west_damerian},
 * {@code religion = regent_court}). A file may also carry dated {@code
 * YYYY.M.D = { ... }} blocks that change ownership over time; the effective value
 * at the game-start bookmark is the base value with every dated block up to
 * {@link #START_DATE} applied in chronological order. Provinces with no owner
 * (uncolonized/wasteland, sea) are left unstamped.
 * <p>
 * Runs at the <em>end</em> of the stamp chain, after {@link ClimateExporter}
 * (it re-stamps the committed {@code provinces.json}):
 *
 * <pre>
 * mvn compile exec:exec -Dsim.main=com.civstudio.geo.export.ProvinceHistoryExporter
 * </pre>
 *
 * See {@code docs/geography.md} and {@code CountryExporter}/{@code
 * CultureExporter}/{@code ReligionExporter} for the metadata resources these keys
 * join to.
 */
public final class ProvinceHistoryExporter {

	private static final String INPUT_DIR = "history/provinces";
	private static final String PROVINCES = "civstudio-engine/target/generated/map/provinces.json";

	/** The game-start bookmark; dated blocks after this are ignored (encoded YYYYMMDD). */
	private static final int START_DATE = 1444_11_11;

	/** {@code <id> - <Name>.txt} — the leading number is the province id. */
	private static final Pattern FILE_ID = Pattern.compile("^(\\d+)\\s*-");
	/** A {@code YYYY.M.D} block key, encoded to a sortable {@code YYYYMMDD} int. */
	private static final Pattern DATE = Pattern.compile("^(\\d+)\\.(\\d+)\\.(\\d+)$");

	/** The EU4 placeholder good for undiscovered provinces — normalized to a null trade good. */
	private static final String UNKNOWN_GOOD = "unknown";

	private final ObjectMapper mapper = new ObjectMapper();
	// per-province overlay, parsed from the history files:
	// id -> {owner, controller, culture, religion, tradeGood, baseTax, baseProduction, baseManpower}
	private final Map<Integer, String[]> political = new LinkedHashMap<>();

	private ProvinceHistoryExporter() {
	}

	public static void main(String[] args) throws Exception {
		ProvinceHistoryExporter exporter = new ProvinceHistoryExporter();
		exporter.parseAll();
		exporter.stampProvinces();
	}

	private void parseAll() throws Exception {
		File dir = AnbennarFiles.getDir(INPUT_DIR).toFile();
		File[] files = dir.listFiles((d, n) -> n.endsWith(".txt"));
		if (files == null)
			throw new IllegalStateException("history dir not found: " + dir.getAbsolutePath());
		for (File f : files) {
			Matcher m = FILE_ID.matcher(f.getName());
			if (!m.find())
				continue;
			int id = Integer.parseInt(m.group(1));
			String content = stripComments(
					Files.readString(f.toPath(), StandardCharsets.ISO_8859_1));
			String[] eff = effective(content);
			boolean any = false;
			for (String v : eff)
				any |= v != null;
			if (any)
				political.put(id, eff);
		}
	}

	/**
	 * The effective {@code {owner, controller, culture, religion, tradeGood}} at
	 * {@link #START_DATE}: base top-level values, then every dated block up to the
	 * start date applied in chronological order.
	 */
	private static String[] effective(String content) {
		Scan base = scan(content);
		String[] eff = new String[8];
		merge(eff, base.keys);
		base.dated.sort((a, b) -> Integer.compare(a.date, b.date));
		for (DateBlock db : base.dated) {
			if (db.date > START_DATE)
				continue;
			merge(eff, scan(db.body).keys);
		}
		return eff;
	}

	private static void merge(String[] eff, Map<String, String> keys) {
		if (keys.containsKey("owner"))
			eff[0] = keys.get("owner");
		if (keys.containsKey("controller"))
			eff[1] = keys.get("controller");
		if (keys.containsKey("culture"))
			eff[2] = keys.get("culture");
		if (keys.containsKey("religion"))
			eff[3] = keys.get("religion");
		if (keys.containsKey("trade_goods")) {
			String good = keys.get("trade_goods");
			// the 'unknown' placeholder (undiscovered province) is normalized to null,
			// so an undiscovered province is left unstamped like an unowned one
			eff[4] = UNKNOWN_GOOD.equals(good) ? null : good;
		}
		// development — EU4 base_tax/base_production/base_manpower (ADM/DIP/MIL). Base
		// values set at the top level (dated `base_* = N` overrides win by last-write);
		// the additive `add_base_*` modifiers are rare in start-date history and not summed.
		if (keys.containsKey("base_tax"))
			eff[5] = keys.get("base_tax");
		if (keys.containsKey("base_production"))
			eff[6] = keys.get("base_production");
		if (keys.containsKey("base_manpower"))
			eff[7] = keys.get("base_manpower");
	}

	// --- a minimal brace-aware Clausewitz scan ---------------------------------

	private record Scan(Map<String, String> keys, List<DateBlock> dated) {
	}

	private record DateBlock(int date, String body) {
	}

	/**
	 * Walk {@code s} once, collecting the depth-0 {@code key = value} scalar
	 * assignments (last write wins) and the depth-0 {@code DATE = { ... }} blocks.
	 * Nested blocks (modifiers, revolt, etc.) are skipped by brace matching, so a
	 * stray {@code culture}/{@code religion} inside one cannot leak up.
	 */
	private static Scan scan(String s) {
		Map<String, String> keys = new LinkedHashMap<>();
		List<DateBlock> dated = new ArrayList<>();
		int i = 0, n = s.length();
		while (i < n) {
			char c = s.charAt(i);
			if (Character.isWhitespace(c) || c == '{' || c == '}') {
				i++;
				continue;
			}
			int start = i;
			while (i < n && !Character.isWhitespace(s.charAt(i))
					&& s.charAt(i) != '=' && s.charAt(i) != '{' && s.charAt(i) != '}')
				i++;
			String token = s.substring(start, i);
			while (i < n && Character.isWhitespace(s.charAt(i)))
				i++;
			if (i >= n || s.charAt(i) != '=')
				continue; // a bare token (list element) — not an assignment
			i++; // consume '='
			while (i < n && Character.isWhitespace(s.charAt(i)))
				i++;
			if (i < n && s.charAt(i) == '{') {
				int depth = 0, bodyStart = i + 1;
				while (i < n) {
					char d = s.charAt(i);
					if (d == '{')
						depth++;
					else if (d == '}' && --depth == 0) {
						i++;
						break;
					}
					i++;
				}
				Integer date = parseDate(token);
				if (date != null)
					dated.add(new DateBlock(date, s.substring(bodyStart, i - 1)));
			} else {
				int vStart = i;
				while (i < n && !Character.isWhitespace(s.charAt(i))
						&& s.charAt(i) != '{' && s.charAt(i) != '}')
					i++;
				keys.put(token, s.substring(vStart, i));
			}
		}
		return new Scan(keys, dated);
	}

	private static Integer parseDate(String token) {
		Matcher m = DATE.matcher(token);
		if (!m.matches())
			return null;
		return Integer.parseInt(m.group(1)) * 1_00_00
				+ Integer.parseInt(m.group(2)) * 1_00
				+ Integer.parseInt(m.group(3));
	}

	private static String stripComments(String content) {
		return content.replaceAll("#.*", "");
	}

	private void stampProvinces() throws Exception {
		File file = Exports.outFile(PROVINCES);
		List<Map<String, Object>> rows = mapper.readValue(file,
				new TypeReference<List<Map<String, Object>>>() {
				});

		int owners = 0, cultures = 0, religions = 0, tradeGoods = 0, developed = 0;
		List<Map<String, Object>> out = new ArrayList<>(rows.size());
		for (Map<String, Object> row : rows) {
			int id = ((Number) row.get("id")).intValue();
			String[] pol = political.get(id);
			String owner = pol == null ? null : pol[0];
			String controller = pol == null ? null : pol[1];
			String culture = pol == null ? null : pol[2];
			String religion = pol == null ? null : pol[3];
			String tradeGood = pol == null ? null : pol[4];
			Integer baseTax = pol == null ? null : dev(pol[5]);
			Integer baseProduction = pol == null ? null : dev(pol[6]);
			Integer baseManpower = pol == null ? null : dev(pol[7]);
			if (owner != null)
				owners++;
			if (culture != null)
				cultures++;
			if (religion != null)
				religions++;
			if (tradeGood != null)
				tradeGoods++;
			if (baseTax != null || baseProduction != null || baseManpower != null)
				developed++;

			// anchor the political fields after the last environmental/geo key present
			String anchor = row.containsKey("monsoon") ? "monsoon"
					: row.containsKey("winter") ? "winter"
							: row.containsKey("climate") ? "climate"
									: row.containsKey("continent") ? "continent"
											: row.containsKey("area") ? "area"
													: row.containsKey("region") ? "region" : null;
			Map<String, Object> rebuilt = new LinkedHashMap<>();
			for (Map.Entry<String, Object> e : row.entrySet()) {
				switch (e.getKey()) {
					case "owner", "controller", "culture", "religion", "trade_goods",
							"base_tax", "base_production", "base_manpower" -> {
						// drop any stale value; re-added in the canonical slot
					}
					default -> rebuilt.put(e.getKey(), e.getValue());
				}
				if (e.getKey().equals(anchor))
					putPolitical(rebuilt, owner, controller, culture, religion, tradeGood,
							baseTax, baseProduction, baseManpower);
			}
			if (anchor == null)
				putPolitical(rebuilt, owner, controller, culture, religion, tradeGood,
						baseTax, baseProduction, baseManpower);
			out.add(rebuilt);
		}

		mapper.writerWithDefaultPrettyPrinter().writeValue(file, out);
		System.out.println("stamped owner onto " + owners + ", culture onto "
				+ cultures + ", religion onto " + religions + ", trade good onto "
				+ tradeGoods + ", development onto " + developed + " of " + rows.size()
				+ " provinces in " + file.getAbsolutePath());
	}

	// parse an integer development value (base_tax/base_production/base_manpower); EU4
	// history values are integers, but tolerate a decimal by rounding. null stays null.
	private static Integer dev(String v) {
		if (v == null)
			return null;
		try {
			return (int) Math.round(Double.parseDouble(v));
		} catch (NumberFormatException e) {
			return null;
		}
	}

	// add only the present political + development fields, anchored after the geo keys,
	// canonical order
	private static void putPolitical(Map<String, Object> row, String owner,
			String controller, String culture, String religion, String tradeGood,
			Integer baseTax, Integer baseProduction, Integer baseManpower) {
		if (owner != null)
			row.put("owner", owner);
		if (controller != null)
			row.put("controller", controller);
		if (culture != null)
			row.put("culture", culture);
		if (religion != null)
			row.put("religion", religion);
		if (tradeGood != null)
			row.put("trade_goods", tradeGood);
		if (baseTax != null)
			row.put("base_tax", baseTax);
		if (baseProduction != null)
			row.put("base_production", baseProduction);
		if (baseManpower != null)
			row.put("base_manpower", baseManpower);
	}
}
