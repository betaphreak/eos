package com.civstudio.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import com.civstudio.geo.TerrainRegistry;
import com.civstudio.geo.WorldMap;
import com.civstudio.settlement.ProvincePlotStore;
import com.civstudio.tech.TechTree;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * End-to-end Phase-4 check: boot the real on-demand loaders through a bundle-backed {@link WorldSource}
 * and confirm they load the same counts they do off the classpath — i.e. the world bundle → engine
 * chain is faithful and BundleWorldSource re-serializes each dataset in a shape the parsers still read.
 *
 * <p>Fixture source, in order: (1) {@code -Dworldbundle.fixture=<bundle.json[.gz]>} — a real snapshot
 * (produce one with {@code tools/make-world-bundle.mjs}); else (2) a bundle assembled on the fly from
 * the committed {@code generated/} resources, so the test RUNS on every {@code mvn test} (no external
 * dependency) while {@code generated/} still exists; else (3) skip (post-cutover, when {@code generated/}
 * is gone — CI then supplies a snapshot via the property). Only covers the on-demand loaders —
 * {@code UnitCatalog} is an eager static singleton that captures the source at class-load, so it can't
 * be re-sourced mid-JVM in the shared test fork.
 */
class WorldSourceIntegrationTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	@Test
	void bootsOnDemandLoadersThroughBundle() {
		WorldSource bundle = resolveFixture();
		assumeTrue(bundle != null,
				"no world-bundle fixture: set -Dworldbundle.fixture=<bundle.json[.gz]> (generated/ is gone)");
		try {
			// baseline off the classpath, then re-load through the bundle and assert identical counts.
			WorldSources.reset();
			int cpProvinces = WorldMap.load().size();
			int cpCountries = WorldMap.load().countries().size();
			int cpTerrains = TerrainRegistry.load().terrains().size();
			int cpTechs = TechTree.load().size();

			WorldSources.set(bundle);
			assertEquals(cpProvinces, WorldMap.load().size(), "provinces via bundle");
			assertEquals(cpCountries, WorldMap.load().countries().size(), "countries via bundle");
			assertEquals(cpTerrains, TerrainRegistry.load().terrains().size(), "terrains via bundle");
			assertEquals(cpTechs, TechTree.load().size(), "kept techs via bundle");
		} finally {
			WorldSources.reset();
		}
	}

	/** A real snapshot if -D provided; else a bundle built from the classpath resources; else null. */
	private static WorldSource resolveFixture() {
		String fx = System.getProperty("worldbundle.fixture");
		if (fx != null && Files.exists(Path.of(fx)))
			return new FixtureWorldSource(Path.of(fx));
		Path root = classpathRoot();
		return (root != null) ? new BundleWorldSource(classpathBundle(root)) : null;
	}

	/** The classpath root dir (…/target/classes) via a known committed resource, or null if absent/jar'd. */
	private static Path classpathRoot() {
		try {
			URL u = WorldSourceIntegrationTest.class.getResource("/units.json"); // committed at classpath root
			if (u == null || !"file".equals(u.getProtocol()))
				return null; // post-cutover (deleted) or running from a jar — caller falls back to skip
			return Path.of(u.toURI()).getParent();
		} catch (java.net.URISyntaxException e) {
			return null;
		}
	}

	/**
	 * Assemble a world bundle from the committed resources on the classpath — {@code resources["/<rel>"]}
	 * = the parsed file (generated/ mounts at classpath root, so {@code map/provinces.json} ⇒
	 * {@code /map/provinces.json}). Covers every committed {@code *.json} (data + root feasts/tech-effects/
	 * region-earth-map/human-names); geonames ({@code .json.gz}) and {@code *.lock} are skipped and fall
	 * back to the classpath in BundleWorldSource.
	 */
	private static ObjectNode classpathBundle(Path root) {
		ObjectNode bundle = MAPPER.createObjectNode();
		ObjectNode meta = bundle.putObject("meta");
		meta.put("mapVersion", ProvincePlotStore.MAP_VERSION);
		meta.put("contentVersion", "classpath-fixture");
		ObjectNode resources = bundle.putObject("resources");
		try (Stream<Path> tree = Files.walk(root)) {
			tree.filter(p -> p.toString().endsWith(".json")).forEach(p -> {
				String key = "/" + root.relativize(p).toString().replace('\\', '/');
				try (InputStream in = Files.newInputStream(p)) {
					resources.set(key, MAPPER.readTree(in));
				} catch (java.io.IOException e) {
					throw new java.io.UncheckedIOException("reading " + p, e);
				}
			});
		} catch (java.io.IOException e) {
			throw new java.io.UncheckedIOException("walking " + root, e);
		}
		return bundle;
	}
}
