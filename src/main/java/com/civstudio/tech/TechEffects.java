package com.civstudio.tech;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Loader for the tech-effect overlay: a JSON object keyed by tech id, each value a
 * list of {@link TechEffect}s that tech grants when researched. The overlay is kept
 * <b>separate</b> from {@code techs.json} (an untouched Civ4 import) so eos-native
 * effects can be authored independently and only for techs that have one.
 * <p>
 * The shipped {@code /tech-effects.json} is empty ({@code {}}) for now — the schema
 * and plumbing are in place but coverage (which techs get which effects) is a later
 * authoring pass (see {@code docs/tech-tree.md}). An empty or absent overlay yields
 * no effects, leaving runs unchanged.
 */
final class TechEffects {

	private static final ObjectMapper MAPPER = new ObjectMapper()
			.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
			.configure(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY, true);

	private TechEffects() {
	}

	/**
	 * Load the effect overlay from a classpath resource, mapping each tech id to its
	 * list of effects. A missing resource yields an empty map (the overlay is
	 * optional); an empty object yields an empty map.
	 *
	 * @param resource
	 *            the classpath resource path (e.g. {@code "/tech-effects.json"})
	 * @return tech id &rarr; effects (an immutable map; empty if the resource is
	 *         absent)
	 * @throws IllegalStateException
	 *             if the resource is present but malformed
	 */
	static Map<String, List<TechEffect>> load(String resource) {
		try (InputStream in = TechEffects.class.getResourceAsStream(resource)) {
			if (in == null)
				return Map.of(); // overlay is optional
			Map<String, List<TechEffect>> overlay = MAPPER.readValue(in,
					new TypeReference<Map<String, List<TechEffect>>>() {
					});
			return Map.copyOf(overlay);
		} catch (IOException e) {
			throw new UncheckedIOException(
					"Failed to load tech-effect overlay: " + resource, e);
		}
	}
}
