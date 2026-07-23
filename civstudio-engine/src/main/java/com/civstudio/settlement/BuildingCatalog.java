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
 * The in-memory index of the imported C2C building catalog ({@code /buildings.json}) —
 * the engine-side reader of what {@code BuildingInfoExporter} baked and the studio
 * content chain serves; the {@link com.civstudio.agent.UnitCatalog} pattern applied to
 * buildings (see {@code docs/build-queue-plan.md} B2). Until this, the engine consumed
 * only the {@code /building-unlocks.json} overlay (tech tokens via {@code TechTree});
 * this catalog gives the build economy the rows themselves — cost, kind, obsolescence,
 * flavors.
 * <p>
 * The load is <b>lenient</b>: a missing/broken resource yields an empty catalog (a
 * warning, not a crash) — a server on an unseeded content store then simply builds
 * nothing (no houses, no queue), the safe-inert contract of the plan's deploy note.
 */
public final class BuildingCatalog {

	private static final String RESOURCE = "/buildings.json";
	private static final ObjectMapper MAPPER = JsonMapper.builder()
			.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
			.configure(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY, true)
			.build();

	// built on first use and rebuilt if the active WorldSource changes (the UnitCatalog pattern)
	private static final WorldSourceCache<BuildingCatalog> CACHE =
			new WorldSourceCache<>(BuildingCatalog::load);

	// all rows in document (bundle) order; the by-id index; the housing-kind sub-list, same order
	private final List<BuildingInfo> all;
	private final Map<String, BuildingInfo> byId;
	private final List<BuildingInfo> housing;

	private BuildingCatalog(List<BuildingInfo> all) {
		this.all = Collections.unmodifiableList(all);
		Map<String, BuildingInfo> index = new HashMap<>();
		List<BuildingInfo> homes = new ArrayList<>();
		for (BuildingInfo b : all) {
			index.put(b.id(), b);
			if (b.housing())
				homes.add(b);
		}
		this.byId = Collections.unmodifiableMap(index);
		this.housing = Collections.unmodifiableList(homes);
	}

	/** The shared catalog. */
	public static BuildingCatalog get() {
		return CACHE.get();
	}

	/** All buildings in document order (empty on an unseeded content store). */
	public List<BuildingInfo> all() {
		return all;
	}

	/** The building with the given {@code BUILDING_*} id, or {@code null}. */
	public BuildingInfo byId(String id) {
		return byId.get(id);
	}

	/** The housing-kind line ({@code BUILDING_HOUSING_*}), in document order. */
	public List<BuildingInfo> housing() {
		return housing;
	}

	/**
	 * A building's <b>display name</b> for prose — the event log, a printer, anything a person
	 * reads. Looks the id up in this catalog, then the richer {@link HousingCatalog} (the housing
	 * line lives in both, and only the latter is guaranteed to carry a name), and falls back to a
	 * prettified id so an unseeded content store degrades to "Bark Huts" rather than a bare token.
	 *
	 * @param id the {@code BUILDING_*} catalog id
	 * @return the name to show
	 */
	public static String displayName(String id) {
		if (id == null || id.isBlank())
			return "";
		BuildingInfo b = get().byId(id);
		if (b != null && b.name() != null && !b.name().isBlank())
			return b.name();
		HousingBuilding h = HousingCatalog.get().byType(id);
		if (h != null && h.name() != null && !h.name().isBlank())
			return h.name();
		String[] words = id.replaceFirst("^BUILDING_", "").toLowerCase(java.util.Locale.ROOT)
				.split("_");
		StringBuilder out = new StringBuilder();
		for (String w : words) {
			if (w.isEmpty())
				continue;
			if (!out.isEmpty())
				out.append(' ');
			out.append(Character.toUpperCase(w.charAt(0))).append(w, 1, w.length());
		}
		return out.toString();
	}

	static BuildingCatalog load(WorldSource source) {
		try (InputStream in = source.open(RESOURCE)) {
			if (in == null) {
				System.err.println("BuildingCatalog: " + RESOURCE + " absent — empty catalog");
				return new BuildingCatalog(new ArrayList<>());
			}
			List<BuildingInfo> rows = MAPPER.readValue(in, new TypeReference<List<BuildingInfo>>() {
			});
			return new BuildingCatalog(rows);
		} catch (IOException | RuntimeException e) {
			// lenient: an unseeded/broken content store must not break the sim — it just builds nothing
			System.err.println("BuildingCatalog: failed to load " + RESOURCE + " — " + e.getMessage());
			return new BuildingCatalog(new ArrayList<>());
		}
	}
}
