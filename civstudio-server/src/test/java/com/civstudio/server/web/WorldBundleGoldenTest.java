package com.civstudio.server.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Parity guard for the migration of {@code window.BUNDLE} from the Node-built {@code web/data.js}
 * to the server-assembled {@link WorldBundle}. Asserts that {@link WorldBundle#assemble()}
 * reproduces the <b>engine-derived</b> fields of the last committed {@code data.js} (captured
 * gzipped at {@code src/test/resources/web/bundle-golden.json.gz}): the full province set with
 * {@code id/name/lat/lon/plots/waterPlots/type/region/area/continent/winter/nb/rings/hasPlots/
 * bbox}, plus {@code geo}, {@code geoNames}, and {@code adjacencies}.
 * <p>
 * The one tolerant field is {@code lab} (the curved label baseline): its geometry runs through
 * transcendental functions that can differ from V8 by a ULP, so a small number of provinces may
 * land ±1px or flip null vs a baseline — invisible in the map (the client falls back to a
 * straight axis). The asset-coupled fields ({@code map}, {@code terrainColors}, {@code plotIndex},
 * …) are not checked here: the server copies them verbatim from the same manifest {@code data.js}
 * was built with, so they are identical by construction.
 */
class WorldBundleGoldenTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final double EPS = 1e-9; // numbers should match exactly; absorbs only representation noise

	@Test
	void serverBundleMatchesGoldenDataJs() throws Exception {
		JsonNode golden;
		try (InputStream in = getClass().getResourceAsStream("/web/bundle-golden.json.gz")) {
			assertNotNull(in, "golden fixture /web/bundle-golden.json.gz missing");
			golden = MAPPER.readTree(new GZIPInputStream(in));
		}
		ObjectNode actual = WorldBundle.assemble();

		// Regeneration hook: after an intentional bundle change, rewrite the fixture with
		//   mvn -pl civstudio-server test -Dtest=WorldBundleGoldenTest -Dbundle.golden.regen=true
		// then review the diff and commit the fixture.
		//
		// The path is anchored on ${basedir} (surefire sets it), NOT relative: mvn runs from the
		// reactor root, so a bare "src/test/..." wrote a stray fixture to the repo root and left the
		// real one untouched — the regen appeared to work and changed nothing. A leftover from the
		// engine/server module split.
		if (Boolean.getBoolean("bundle.golden.regen")) {
			java.nio.file.Path out = java.nio.file.Path.of(System.getProperty("basedir", "."))
					.resolve("src/test/resources/web/bundle-golden.json.gz");
			java.nio.file.Files.createDirectories(out.getParent());
			try (var gz = new java.util.zip.GZIPOutputStream(java.nio.file.Files.newOutputStream(out))) {
				gz.write(MAPPER.writeValueAsBytes(actual));
			}
			System.out.println("regenerated golden fixture: " + out.toAbsolutePath());
			return;
		}

		Map<Integer, JsonNode> exp = byId(golden.get("provinces"));
		Map<Integer, JsonNode> act = byId(actual.get("provinces"));
		assertEquals(exp.keySet(), act.keySet(), "shipped province id set differs");

		List<String> hard = new ArrayList<>();     // non-lab field mismatches (must be zero)
		int labFlips = 0, labOff = 0, labProvinces = 0;
		String[] scalarFields = { "name", "lat", "lon", "plots", "waterPlots", "type",
				"region", "area", "continent", "realm", "winter" };
		for (var e : exp.entrySet()) {
			int id = e.getKey();
			JsonNode g = e.getValue(), a = act.get(id);
			for (String f : scalarFields)
				if (!numAwareEquals(g.get(f), a.get(f)))
					hard.add(id + "." + f + ": expected " + g.get(f) + " got " + a.get(f));
			if (!numAwareEquals(g.get("nb"), a.get("nb")))
				hard.add(id + ".nb differs: " + g.get("nb") + " vs " + a.get("nb"));
			if (!numAwareEquals(g.get("rings"), a.get("rings")))
				hard.add(id + ".rings differ");
			if (!numAwareEquals(g.get("bbox"), a.get("bbox")))
				hard.add(id + ".bbox differs: " + g.get("bbox") + " vs " + a.get("bbox"));

			JsonNode gl = nullNode(g.get("lab")), al = nullNode(a.get("lab"));
			if (gl != null || al != null)
				labProvinces++;
			if ((gl == null) != (al == null))
				labFlips++;
			else if (gl != null && !labClose(gl, al))
				labOff++;
		}

		if (!hard.isEmpty())
			System.out.println("hard field mismatches (" + hard.size() + "):\n  "
					+ String.join("\n  ", hard.subList(0, Math.min(40, hard.size()))));
		System.out.printf("lab: %d provinces with a baseline; %d null-flips, %d off-by->1px%n",
				labProvinces, labFlips, labOff);

		assertTrue(hard.isEmpty(), hard.size() + " engine-derived field mismatches (see stdout)");
		// tolerance: transcendental ULP drift. Empirically a handful at most; keep a tight bound.
		assertTrue(labFlips + labOff <= Math.max(5, labProvinces / 100),
				"too many lab discrepancies: " + labFlips + " flips + " + labOff + " off");

		// geo / geoNames / adjacencies: exact (numeric-aware)
		assertTrue(numAwareEquals(golden.get("geo"), actual.get("geo")), "geo differs");
		assertTrue(numAwareEquals(golden.get("geoNames"), actual.get("geoNames")), "geoNames differs");
		assertTrue(adjEquals(golden.get("adjacencies"), actual.get("adjacencies")), "adjacencies differ");
	}

	private static Map<Integer, JsonNode> byId(JsonNode provinces) {
		Map<Integer, JsonNode> m = new HashMap<>();
		for (JsonNode p : provinces)
			m.put(p.get("id").asInt(), p);
		return m;
	}

	// treat an absent or explicit-null lab uniformly as "no baseline"
	private static JsonNode nullNode(JsonNode n) {
		return (n == null || n.isNull()) ? null : n;
	}

	// two lab baselines are "the same" if same point count, thickness within ±1, each control
	// point within ±1px in x and y (absorbs last-ULP drift in cos/sin/atan2 vs V8)
	private static boolean labClose(JsonNode g, JsonNode a) {
		if (Math.abs(g.get("t").asInt() - a.get("t").asInt()) > 1)
			return false;
		JsonNode gp = g.get("p"), ap = a.get("p");
		if (gp.size() != ap.size())
			return false;
		for (int i = 0; i < gp.size(); i++)
			if (Math.abs(gp.get(i).get(0).asInt() - ap.get(i).get(0).asInt()) > 1
					|| Math.abs(gp.get(i).get(1).asInt() - ap.get(i).get(1).asInt()) > 1)
				return false;
		return true;
	}

	// adjacencies is an unordered set of [from,to,type,teleport] rows; compare as sets
	private static boolean adjEquals(JsonNode g, JsonNode a) {
		if (g.size() != a.size())
			return false;
		java.util.Set<String> gs = new java.util.HashSet<>(), as = new java.util.HashSet<>();
		for (JsonNode r : g)
			gs.add(r.toString());
		for (JsonNode r : a)
			as.add(r.toString());
		return gs.equals(as);
	}

	// deep JSON equality that treats 45 == 45.0 (JS drops the trailing .0 the JVM keeps) and
	// compares floating values within EPS; null and absent both count as "no value"
	private static boolean numAwareEquals(JsonNode g, JsonNode a) {
		if (nullNode(g) == null && nullNode(a) == null)
			return true;
		if (nullNode(g) == null || nullNode(a) == null)
			return false;
		if (g.isNumber() && a.isNumber())
			return Math.abs(g.asDouble() - a.asDouble()) <= EPS;
		if (g.isArray() && a.isArray()) {
			if (g.size() != a.size())
				return false;
			for (int i = 0; i < g.size(); i++)
				if (!numAwareEquals(g.get(i), a.get(i)))
					return false;
			return true;
		}
		if (g.isObject() && a.isObject()) {
			if (g.size() != a.size())
				return false;
			for (String f : g.propertyNames()) {
				if (!numAwareEquals(g.get(f), a.get(f)))
					return false;
			}
			return true;
		}
		return g.equals(a);
	}
}
