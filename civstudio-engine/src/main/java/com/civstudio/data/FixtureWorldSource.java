package com.civstudio.data;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPInputStream;

import tools.jackson.databind.JsonNode;

/**
 * A {@link BundleWorldSource} that loads the world bundle from a saved snapshot file (plain
 * {@code .json} or gzipped {@code .json.gz}). This is the offline / {@code mvn test} contract: tests
 * and offline dev boot the engine from a cached bundle with no Strapi reachable. The snapshot is a
 * gitignored/CI-produced artifact, not committed source-of-truth.
 */
public final class FixtureWorldSource extends BundleWorldSource {

	public FixtureWorldSource(Path snapshot) {
		super(read(snapshot));
	}

	private FixtureWorldSource(JsonNode bundle) {
		super(bundle);
	}

	/**
	 * Load a snapshot committed on the classpath (e.g. {@code /world-bundle.json.gz} under test
	 * resources). Reads through the plain class loader rather than {@link WorldSources} — this is the
	 * bootstrap that installs the source, so it cannot depend on the source already being set.
	 */
	public static FixtureWorldSource fromClasspath(String resource) {
		try (InputStream raw = FixtureWorldSource.class.getResourceAsStream(resource)) {
			if (raw == null)
				throw new IllegalStateException("world-bundle snapshot not on classpath: " + resource);
			try (InputStream in = resource.endsWith(".gz") ? new GZIPInputStream(raw) : raw) {
				return new FixtureWorldSource(parse(in));
			}
		} catch (java.io.IOException e) {
			throw new IllegalStateException("world-bundle snapshot read failed: " + resource, e);
		}
	}

	private static JsonNode read(Path snapshot) {
		try (InputStream raw = Files.newInputStream(snapshot);
				InputStream in = snapshot.toString().endsWith(".gz") ? new GZIPInputStream(raw) : raw) {
			return parse(in);
		} catch (java.io.IOException e) {
			throw new IllegalStateException("world-bundle snapshot read failed: " + snapshot, e);
		}
	}
}
