package com.civstudio.data;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Objects;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * A {@link WorldSource} backed by the consolidated, path-keyed world bundle that studio serves at
 * {@code GET /api/world-bundle} (studio-datamodel-rebuild Phase 4). The bundle is
 * {@code { meta: {mapVersion, contentVersion}, resources: { "/map/provinces.json": […], … } }}; each
 * key mirrors exactly the classpath resource the engine reads, so {@link #open} just re-serializes
 * {@code resources[path]} and the loader's existing Jackson parser reads it unchanged.
 *
 * <p>Paths absent from the bundle (the geonames subset, non-human name caches, {@code *.lock} pins)
 * fall back to the classpath, so a bundle that covers only part of {@code generated/} still boots.
 * {@link StrapiWorldSource} fetches the bundle over HTTP; {@link FixtureWorldSource} loads a snapshot.
 */
public class BundleWorldSource implements WorldSource {

	protected static final ObjectMapper MAPPER = new ObjectMapper();

	private final JsonNode resources;
	private final WorldSource fallback = new ClasspathWorldSource();
	private final int mapVersion;
	private final String contentVersion;

	/** Wrap an already-parsed bundle document ({@code {meta, resources}}). */
	public BundleWorldSource(JsonNode bundle) {
		Objects.requireNonNull(bundle, "bundle");
		this.resources = bundle.path("resources");
		JsonNode meta = bundle.path("meta");
		this.mapVersion = meta.path("mapVersion").asInt(-1);
		this.contentVersion = meta.path("contentVersion").asString(null);
		if (this.resources.isMissingNode() || !this.resources.isObject())
			throw new IllegalStateException("world bundle has no 'resources' object");
	}

	@Override
	public InputStream open(String path) {
		JsonNode node = resources.get(path);
		if (node == null)
			return fallback.open(path); // not in the bundle (geonames / names / locks) → classpath
		return new ByteArrayInputStream(MAPPER.writeValueAsBytes(node));
	}

	@Override
	public boolean exists(String path) {
		return resources.has(path) || fallback.exists(path);
	}

	/** The plot-cache generation the bundle was built at ({@code meta.mapVersion}), or -1 if absent. */
	public int mapVersion() {
		return mapVersion;
	}

	/** The content-version stamp ({@code meta.contentVersion}) — reproducibility is seed + this. */
	public String contentVersion() {
		return contentVersion;
	}

	/** Parse a bundle document from a stream (shared by the Strapi/fixture factories). */
	protected static JsonNode parse(InputStream in) {
		return MAPPER.readTree(in);
	}
}
