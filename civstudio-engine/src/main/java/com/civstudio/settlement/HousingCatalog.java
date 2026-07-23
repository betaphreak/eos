package com.civstudio.settlement;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.civstudio.data.WorldSource;
import com.civstudio.data.WorldSourceCache;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * The in-memory index of the C2C <b>housing ladder</b> ({@code /housing.json}) — the
 * rich per-rung catalog ({@link HousingBuilding}: prereq legs, ladder structure, costs)
 * the build economy's housing targeting reads (docs/build-queue-plan.md B3), distinct
 * from the flat {@link BuildingCatalog} the ruler's queue brain reads. Same load
 * pattern as {@link com.civstudio.agent.UnitCatalog}: {@link WorldSourceCache}-backed,
 * <b>lenient</b> — a missing/broken resource yields an empty catalog (warning, not a
 * crash), so an unseeded content store just means nobody can build housing.
 */
public final class HousingCatalog {

	private static final String RESOURCE = "/housing.json";
	private static final ObjectMapper MAPPER = JsonMapper.builder()
			.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
			.configure(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY, true)
			.build();

	private static final WorldSourceCache<HousingCatalog> CACHE =
			new WorldSourceCache<>(HousingCatalog::load);

	// all rungs in document (bundle) order; the by-type index
	private final List<HousingBuilding> all;
	private final Map<String, HousingBuilding> byType;

	private HousingCatalog(List<HousingBuilding> all) {
		this.all = Collections.unmodifiableList(all);
		Map<String, HousingBuilding> index = new HashMap<>();
		for (HousingBuilding h : all)
			index.put(h.type(), h);
		this.byType = Collections.unmodifiableMap(index);
	}

	/** The shared catalog. */
	public static HousingCatalog get() {
		return CACHE.get();
	}

	/** All housing rungs in document order (empty on an unseeded content store). */
	public List<HousingBuilding> all() {
		return all;
	}

	/** The rung with the given {@code BUILDING_HOUSING_*} type, or {@code null}. */
	public HousingBuilding byType(String type) {
		return byType.get(type);
	}

	static HousingCatalog load(WorldSource source) {
		try (InputStream in = source.open(RESOURCE)) {
			if (in == null) {
				System.err.println("HousingCatalog: " + RESOURCE + " absent — empty catalog");
				return new HousingCatalog(new ArrayList<>());
			}
			List<HousingBuilding> rows =
					MAPPER.readValue(in, new TypeReference<List<HousingBuilding>>() {
					});
			return new HousingCatalog(rows);
		} catch (IOException | RuntimeException e) {
			// lenient: an unseeded/broken content store must not break the sim
			System.err.println("HousingCatalog: failed to load " + RESOURCE + " — " + e.getMessage());
			return new HousingCatalog(new ArrayList<>());
		}
	}
}
