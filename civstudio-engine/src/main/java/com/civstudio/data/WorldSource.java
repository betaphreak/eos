package com.civstudio.data;

import java.io.IOException;
import java.io.InputStream;

/**
 * Source of the engine's world-data resources — the committed {@code generated/*.json} today. A loader
 * asks for a classpath-style resource path (e.g. {@code "/map/provinces.json"}, {@code "/units.json"})
 * and gets a stream of that resource's bytes, which its existing Jackson parser reads unchanged.
 *
 * <p>The point of the seam (studio-datamodel-rebuild Phase 4): the byte source can move from the
 * classpath to the live Strapi {@code /api/world-bundle} without touching any parser. Implementations:
 * {@link ClasspathWorldSource} (default, reads the classpath — behavior-neutral), {@code
 * StrapiWorldSource} (serves the same bytes from the fetched bundle), {@code FixtureWorldSource}
 * (from a saved snapshot, for {@code mvn test} / offline). The active source is chosen at the
 * composition root via {@link WorldSources}; the sim core never knows which one it is.
 *
 * <p>{@link #open} returns {@code null} when the resource is absent — callers that treat a resource as
 * optional (WorldMap edge/portal tables, per-race overlays) rely on that, so preserve it.
 */
public interface WorldSource {

	/** Open the resource at {@code path}, or {@code null} if it does not exist. */
	InputStream open(String path);

	/** Whether the resource at {@code path} exists (default: probe via {@link #open}). */
	default boolean exists(String path) {
		try (InputStream in = open(path)) {
			return in != null;
		} catch (IOException e) {
			return false;
		}
	}
}
