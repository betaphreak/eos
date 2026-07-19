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

	private static JsonNode read(Path snapshot) {
		try (InputStream raw = Files.newInputStream(snapshot);
				InputStream in = snapshot.toString().endsWith(".gz") ? new GZIPInputStream(raw) : raw) {
			return parse(in);
		} catch (java.io.IOException e) {
			throw new IllegalStateException("world-bundle snapshot read failed: " + snapshot, e);
		}
	}
}
