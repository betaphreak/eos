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

	/**
	 * The <b>cheapest available</b> housing rung a colony can build today — the B3
	 * self-build target (docs/build-queue-plan.md): among rungs that are
	 * {@link HousingBuilding#buildable() buildable}, <b>unlocked</b> (their
	 * {@code prereqTech} known — read straight off the catalog rather than the
	 * {@code Unlock} token set, because the warm-start baseline does not apply token
	 * effects for pre-known techs; a known follow-up) and <b>not obsolete</b> (their
	 * {@code obsoleteTech} unresearched), the lowest {@link
	 * HousingBuilding#effectiveCost() effective cost}, type id as the deterministic
	 * tie-break. {@code null} when the colony can build no housing yet.
	 *
	 * @param knownTechs the colony's known tech ids, never null
	 * @return the cheapest available rung, or {@code null}
	 */
	public HousingBuilding cheapestAvailable(java.util.Set<String> knownTechs) {
		HousingBuilding best = null;
		for (HousingBuilding h : all) {
			if (!h.buildable() || h.prereqTech() == null
					|| !knownTechs.contains(h.prereqTech()))
				continue;
			if (h.obsoleteTech() != null && knownTechs.contains(h.obsoleteTech()))
				continue;
			if (best == null || h.effectiveCost() < best.effectiveCost()
					|| (h.effectiveCost().equals(best.effectiveCost())
							&& h.type().compareTo(best.type()) < 0))
				best = h;
		}
		return best;
	}

	/**
	 * The <b>best available</b> housing rung — the highest effective cost among the
	 * buildable, prereq-known, non-obsolete rungs (deterministic id tie-break): the
	 * <b>elite</b> commission target (a manor over a hut — B3b). {@code null} when none.
	 *
	 * @param knownTechs the colony's known tech ids, never null
	 * @return the most expensive available rung, or {@code null}
	 */
	public HousingBuilding bestAvailable(java.util.Set<String> knownTechs) {
		HousingBuilding best = null;
		for (HousingBuilding h : all) {
			if (!h.buildable() || h.prereqTech() == null
					|| !knownTechs.contains(h.prereqTech()))
				continue;
			if (h.obsoleteTech() != null && knownTechs.contains(h.obsoleteTech()))
				continue;
			if (best == null || h.effectiveCost() > best.effectiveCost()
					|| (h.effectiveCost().equals(best.effectiveCost())
							&& h.type().compareTo(best.type()) < 0))
				best = h;
		}
		return best;
	}

	/**
	 * The next housing rung <b>up</b> — the cheapest available (buildable, prereq-known,
	 * non-obsolete) rung whose effective cost is strictly greater than {@code cost}, the
	 * id as the deterministic tie-break. The household <b>upgrade</b> target (B5): a housed
	 * household climbs one rung at a time toward {@link #bestAvailable}. {@code null} when
	 * nothing available is dearer than {@code cost} (the household is at the top of the
	 * ladder, and its hammers donate instead).
	 *
	 * @param cost       the household's current house cost (0 when it has none)
	 * @param knownTechs the colony's known tech ids, never null
	 * @return the cheapest available rung dearer than {@code cost}, or {@code null}
	 */
	public HousingBuilding cheapestAbove(double cost, java.util.Set<String> knownTechs) {
		HousingBuilding best = null;
		for (HousingBuilding h : all) {
			if (!h.buildable() || h.prereqTech() == null
					|| !knownTechs.contains(h.prereqTech()))
				continue;
			if (h.obsoleteTech() != null && knownTechs.contains(h.obsoleteTech()))
				continue;
			if (h.effectiveCost() <= cost)
				continue;
			if (best == null || h.effectiveCost() < best.effectiveCost()
					|| (h.effectiveCost().equals(best.effectiveCost())
							&& h.type().compareTo(best.type()) < 0))
				best = h;
		}
		return best;
	}

	/**
	 * Whether a placed housing building still counts as <b>current</b> — its rung not
	 * obsoleted by a researched tech. An obsolete-housed household stays sheltered but
	 * the wedding/fission gate re-applies until it builds current housing (the
	 * Obsolescence decision). A rung missing from the catalog counts as current
	 * (lenient — an unseeded store must not evict anyone).
	 *
	 * @param buildingId the placed building's id
	 * @param knownTechs the colony's known tech ids
	 * @return whether the housing is still current
	 */
	public boolean isCurrent(String buildingId, java.util.Set<String> knownTechs) {
		HousingBuilding h = byType.get(buildingId);
		if (h == null)
			return true;
		return h.obsoleteTech() == null || !knownTechs.contains(h.obsoleteTech());
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
