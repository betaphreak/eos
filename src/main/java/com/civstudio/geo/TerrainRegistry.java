package com.civstudio.geo;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The curated Civ4 terrain/feature/improvement definitions, loaded once from the
 * committed {@code /terrains.json}, {@code /features.json} and {@code
 * /improvements.json} resources (emitted by the exporters in {@link
 * com.civstudio.geo.export}). Like {@link WorldMap}, it is pure reference data —
 * independent of seed and run — so a single instance is shared by every colony in
 * a {@link com.civstudio.settlement.GameSession} (which will load it lazily, the
 * same way it does the world map and name tables).
 * <p>
 * This is the Phase-0 data layer of the plot model: it just loads and indexes the
 * definitions by type. The {@code Plot}/{@code TerrainGenerator} machinery that
 * consumes them lands in later phases. See {@code docs/plots.md}.
 */
public final class TerrainRegistry {

	private static final String TERRAINS_RESOURCE = "/terrains.json";
	private static final String FEATURES_RESOURCE = "/features.json";
	private static final String IMPROVEMENTS_RESOURCE = "/improvements.json";

	private static final ObjectMapper MAPPER = new ObjectMapper()
			.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

	// each keyed by Civ4 type, in load (XML) order for deterministic iteration
	private final Map<String, Terrain> terrains;
	private final Map<String, Feature> features;
	private final Map<String, Improvement> improvements;

	private TerrainRegistry(List<Terrain> terrains, List<Feature> features,
			List<Improvement> improvements) {
		this.terrains = index(terrains, Terrain::type);
		this.features = index(features, Feature::type);
		this.improvements = index(improvements, Improvement::type);
	}

	/** Load the registry from the committed JSON resources. */
	public static TerrainRegistry load() {
		return new TerrainRegistry(
				loadList(TERRAINS_RESOURCE, new TypeReference<List<Terrain>>() {
				}),
				loadList(FEATURES_RESOURCE, new TypeReference<List<Feature>>() {
				}),
				loadList(IMPROVEMENTS_RESOURCE, new TypeReference<List<Improvement>>() {
				}));
	}

	private static <T> List<T> loadList(String resource, TypeReference<List<T>> type) {
		try (InputStream in = TerrainRegistry.class.getResourceAsStream(resource)) {
			if (in == null)
				throw new IllegalStateException("Terrain resource not found: " + resource);
			return MAPPER.readValue(in, type);
		} catch (IOException e) {
			throw new UncheckedIOException(
					"Failed to load terrain resource: " + resource, e);
		}
	}

	private static <T> Map<String, T> index(List<T> items,
			java.util.function.Function<T, String> key) {
		Map<String, T> m = new LinkedHashMap<>(items.size() * 2);
		for (T item : items)
			if (m.put(key.apply(item), item) != null)
				throw new IllegalStateException("duplicate type " + key.apply(item));
		return m;
	}

	/** The terrain with this Civ4 type, or {@code null} if not in the curated set. */
	public Terrain terrain(String type) {
		return terrains.get(type);
	}

	/** The feature with this Civ4 type, or {@code null} if not in the curated set. */
	public Feature feature(String type) {
		return features.get(type);
	}

	/** The improvement with this Civ4 type, or {@code null} if not in the set. */
	public Improvement improvement(String type) {
		return improvements.get(type);
	}

	/** All curated terrains, in load (XML) order. */
	public List<Terrain> terrains() {
		return List.copyOf(terrains.values());
	}

	/** All curated features, in load (XML) order. */
	public List<Feature> features() {
		return List.copyOf(features.values());
	}

	/** All curated improvements, in load (XML) order. */
	public List<Improvement> improvements() {
		return List.copyOf(improvements.values());
	}
}
