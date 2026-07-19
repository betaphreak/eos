package com.civstudio.data;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPInputStream;

import tools.jackson.databind.JsonNode;

/**
 * A {@link BundleWorldSource} that loads the world bundle from a saved snapshot (plain {@code .json}
 * or gzipped {@code .json.gz}). This is the offline / {@code mvn test} contract: tests and offline dev
 * boot the engine from a cached bundle with no Strapi reachable. The snapshot is either the
 * <b>committed engine test fixture</b> ({@code src/test/resources/world-bundle.json.gz}, loaded from
 * the classpath by {@link #fromClasspath} — see {@code FixtureWorldSourceInstaller}) or an out-of-tree
 * file (a CI/offline artifact, loaded via the {@link #FixtureWorldSource(Path) Path} constructor).
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
		InputStream raw = FixtureWorldSource.class.getResourceAsStream(resource);
		if (raw == null)
			throw new IllegalStateException("world-bundle snapshot not on classpath: " + resource);
		try {
			return new FixtureWorldSource(decode(raw, resource.endsWith(".gz")));
		} catch (IOException e) {
			throw new IllegalStateException("world-bundle snapshot read failed: " + resource, e);
		}
	}

	private static JsonNode read(Path snapshot) {
		try {
			return decode(Files.newInputStream(snapshot), snapshot.toString().endsWith(".gz"));
		} catch (IOException e) {
			throw new IllegalStateException("world-bundle snapshot read failed: " + snapshot, e);
		}
	}

	/** Parse the bundle from a raw stream, gunzipping first when {@code gzipped}. Closes {@code raw}. */
	private static JsonNode decode(InputStream raw, boolean gzipped) throws IOException {
		try (InputStream in = gzipped ? new GZIPInputStream(raw) : raw) {
			return parse(in);
		}
	}
}
