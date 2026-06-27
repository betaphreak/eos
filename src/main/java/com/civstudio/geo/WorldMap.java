package com.civstudio.geo;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The world map: the {@link Province} graph imported from the Strapi world
 * content and loaded once from {@code /provinces.json}. It is pure reference
 * data — independent of seed and run — so a single instance is shared by every
 * colony in a {@link com.civstudio.settlement.GameSession}, which loads it
 * lazily on first request (the ~1MB parse is only paid by a session that uses
 * geography) and exposes it via {@code getWorldMap()}.
 * <p>
 * The map provides province lookup, the (undirected) neighbor adjacency, and a
 * shortest-path query over that adjacency — the travel-network primitive the
 * caravan and village-founding features build on. It also carries the coarser
 * geographic tiers loaded from {@code /areas.json} and {@code /regions.json} —
 * the province&nbsp;&rarr;&nbsp;{@link Area area}&nbsp;&rarr;&nbsp;{@link Region
 * region} hierarchy — and the membership queries over it ({@link
 * #provincesInRegion(String)}, {@link #areaOf(int)}, …), with areas the source of
 * truth for a region's provinces. It does not itself move anything: founding a
 * colony into a province (Phase 2) and routing over the graph (caravan trade) are
 * the dependent features. See {@code docs/geography.md}.
 */
public final class WorldMap {

	private static final String PROVINCES_RESOURCE = "/provinces.json";
	private static final String AREAS_RESOURCE = "/areas.json";
	private static final String REGIONS_RESOURCE = "/regions.json";
	private static final String CONTINENTS_RESOURCE = "/continents.json";

	private static final ObjectMapper MAPPER = new ObjectMapper()
			.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

	// provinces keyed by id, in load order (deterministic iteration)
	private final Map<Integer, Province> byId;
	// areas, regions and continents keyed by their raw_key, in load order
	private final Map<String, Area> areasByKey;
	private final Map<String, Region> regionsByKey;
	private final Map<String, Continent> continentsByKey;
	// derived membership indices (all values unmodifiable, deterministic order)
	private final Map<String, List<Province>> provincesByArea;
	private final Map<String, List<Province>> provincesByRegion;
	private final Map<String, List<Province>> provincesByContinent;
	private final Map<String, List<Area>> areasByRegion;
	private final Map<String, String> regionKeyByArea;

	private WorldMap(List<Province> provinces, List<Area> areas,
			List<Region> regions, List<Continent> continents) {
		Map<Integer, Province> byId = new LinkedHashMap<>(provinces.size() * 2);
		for (Province p : provinces)
			if (byId.put(p.id(), p) != null)
				throw new IllegalStateException("duplicate province id " + p.id());
		// every neighbor must resolve to a province in the map (catches a
		// broken export); symmetry is materialized at export time.
		for (Province p : byId.values())
			for (int n : p.neighbors())
				if (!byId.containsKey(n))
					throw new IllegalStateException("province " + p.id()
							+ " has dangling neighbor " + n);
		this.byId = byId;

		Map<String, Area> areasByKey = new LinkedHashMap<>(areas.size() * 2);
		for (Area a : areas)
			if (areasByKey.put(a.rawKey(), a) != null)
				throw new IllegalStateException("duplicate area key " + a.rawKey());
		this.areasByKey = areasByKey;

		Map<String, Region> regionsByKey = new LinkedHashMap<>(regions.size() * 2);
		for (Region r : regions)
			if (regionsByKey.put(r.rawKey(), r) != null)
				throw new IllegalStateException(
						"duplicate region key " + r.rawKey());
		this.regionsByKey = regionsByKey;

		// area -> its provinces present in the map (source ids referring to a
		// province outside the map, e.g. Random-New-World, are skipped)
		Map<String, List<Province>> provByArea = new LinkedHashMap<>();
		for (Area a : areas) {
			List<Province> ps = new ArrayList<>();
			for (int pid : a.provinceIds()) {
				Province p = byId.get(pid);
				if (p != null)
					ps.add(p);
			}
			provByArea.put(a.rawKey(), Collections.unmodifiableList(ps));
		}
		this.provincesByArea = provByArea;

		// region -> its areas / provinces, via the area tier (a region's areas
		// that resolve to no loaded area — empty vanilla placeholders — are
		// skipped; provinces are the de-duplicated union over the resolved areas)
		Map<String, List<Area>> areasByRegion = new LinkedHashMap<>();
		Map<String, List<Province>> provByRegion = new LinkedHashMap<>();
		Map<String, String> regionByArea = new HashMap<>();
		for (Region r : regions) {
			List<Area> ra = new ArrayList<>();
			LinkedHashSet<Province> rp = new LinkedHashSet<>();
			for (String ak : r.areaKeys()) {
				Area a = areasByKey.get(ak);
				if (a == null)
					continue;
				ra.add(a);
				regionByArea.put(ak, r.rawKey());
				rp.addAll(provByArea.get(ak));
			}
			areasByRegion.put(r.rawKey(), Collections.unmodifiableList(ra));
			provByRegion.put(r.rawKey(), List.copyOf(rp));
		}
		this.areasByRegion = areasByRegion;
		this.provincesByRegion = provByRegion;
		this.regionKeyByArea = regionByArea;

		Map<String, Continent> continentsByKey = new LinkedHashMap<>(
				continents.size() * 2);
		for (Continent c : continents)
			if (continentsByKey.put(c.rawKey(), c) != null)
				throw new IllegalStateException(
						"duplicate continent key " + c.rawKey());
		this.continentsByKey = continentsByKey;

		// continent -> its provinces present in the map (ids outside the map are
		// skipped, as for areas)
		Map<String, List<Province>> provByContinent = new LinkedHashMap<>();
		for (Continent c : continents) {
			List<Province> ps = new ArrayList<>();
			for (int pid : c.provinceIds()) {
				Province p = byId.get(pid);
				if (p != null)
					ps.add(p);
			}
			provByContinent.put(c.rawKey(), Collections.unmodifiableList(ps));
		}
		this.provincesByContinent = provByContinent;
	}

	/**
	 * Load the world map from its classpath resources ({@code /provinces.json},
	 * {@code /areas.json}, {@code /regions.json}, {@code /continents.json}).
	 *
	 * @return the loaded map
	 * @throws IllegalStateException
	 *             if a resource is missing, a province/area/region/continent key is
	 *             duplicated, or a neighbor refers to an id not present in the map
	 */
	public static WorldMap load() {
		List<Province> provinces = loadList(PROVINCES_RESOURCE,
				new TypeReference<List<Province>>() {
				});
		List<Area> areas = loadList(AREAS_RESOURCE,
				new TypeReference<List<Area>>() {
				});
		List<Region> regions = loadList(REGIONS_RESOURCE,
				new TypeReference<List<Region>>() {
				});
		List<Continent> continents = loadList(CONTINENTS_RESOURCE,
				new TypeReference<List<Continent>>() {
				});
		return new WorldMap(provinces, areas, regions, continents);
	}

	private static <T> List<T> loadList(String resource,
			TypeReference<List<T>> type) {
		try (InputStream in = WorldMap.class.getResourceAsStream(resource)) {
			if (in == null)
				throw new IllegalStateException(
						"World map resource not found: " + resource);
			return MAPPER.readValue(in, type);
		} catch (IOException e) {
			throw new UncheckedIOException(
					"Failed to load world map resource: " + resource, e);
		}
	}

	/** The number of provinces in the map. */
	public int size() {
		return byId.size();
	}

	/**
	 * Whether the map contains a province with this id.
	 *
	 * @param id a province id
	 * @return {@code true} if present
	 */
	public boolean contains(int id) {
		return byId.containsKey(id);
	}

	/**
	 * The province with this id.
	 *
	 * @param id a province id
	 * @return the province
	 * @throws IllegalArgumentException if no province has that id
	 */
	public Province province(int id) {
		Province p = byId.get(id);
		if (p == null)
			throw new IllegalArgumentException("no province with id " + id);
		return p;
	}

	/** All provinces, in load order (unmodifiable). */
	public Collection<Province> provinces() {
		return Collections.unmodifiableCollection(byId.values());
	}

	/**
	 * The ids of the provinces adjacent to this one.
	 *
	 * @param id a province id
	 * @return its neighbors (unmodifiable)
	 * @throws IllegalArgumentException if no province has that id
	 */
	public List<Integer> neighbors(int id) {
		return province(id).neighbors();
	}

	/** The settleable ({@link ProvinceType#LAND}) provinces, in load order. */
	public List<Province> settleableProvinces() {
		List<Province> out = new ArrayList<>();
		for (Province p : byId.values())
			if (p.isSettleable())
				out.add(p);
		return out;
	}

	/**
	 * The first province with this (case-sensitive) name, if any. Province names
	 * are not guaranteed unique across the whole world, but are unique enough to
	 * resolve a known default (e.g. the founding province).
	 *
	 * @param name the province name to find
	 * @return the matching province, or empty if none
	 */
	public Optional<Province> findByName(String name) {
		for (Province p : byId.values())
			if (p.name().equals(name))
				return Optional.of(p);
		return Optional.empty();
	}

	// --- area / region tier ------------------------------------------------

	/** All areas, in load order (unmodifiable). */
	public Collection<Area> areas() {
		return Collections.unmodifiableCollection(areasByKey.values());
	}

	/** All regions, in load order (unmodifiable). */
	public Collection<Region> regions() {
		return Collections.unmodifiableCollection(regionsByKey.values());
	}

	/**
	 * The area with this {@code raw_key}.
	 *
	 * @param key an area {@code raw_key} (e.g. {@code "inner_rahen_area"})
	 * @return the area
	 * @throws IllegalArgumentException if no area has that key
	 */
	public Area area(String key) {
		Area a = areasByKey.get(key);
		if (a == null)
			throw new IllegalArgumentException("no area with key " + key);
		return a;
	}

	/**
	 * The region with this {@code raw_key}.
	 *
	 * @param key a region {@code raw_key} (e.g. {@code "rahen_coast_region"})
	 * @return the region
	 * @throws IllegalArgumentException if no region has that key
	 */
	public Region region(String key) {
		Region r = regionsByKey.get(key);
		if (r == null)
			throw new IllegalArgumentException("no region with key " + key);
		return r;
	}

	/** Whether an area with this key is loaded. */
	public boolean hasArea(String key) {
		return areasByKey.containsKey(key);
	}

	/** Whether a region with this key is loaded. */
	public boolean hasRegion(String key) {
		return regionsByKey.containsKey(key);
	}

	/**
	 * The provinces this area contains (those present in the map, in source
	 * order; ids referring outside the map are omitted).
	 *
	 * @param key an area {@code raw_key}
	 * @return the area's provinces (unmodifiable, possibly empty)
	 * @throws IllegalArgumentException if no area has that key
	 */
	public List<Province> provincesInArea(String key) {
		area(key); // validate
		return provincesByArea.get(key);
	}

	/**
	 * The areas this region is composed of (those that resolve to a loaded area,
	 * in source order; empty placeholder areas are omitted).
	 *
	 * @param key a region {@code raw_key}
	 * @return the region's areas (unmodifiable, possibly empty)
	 * @throws IllegalArgumentException if no region has that key
	 */
	public List<Area> areasInRegion(String key) {
		region(key); // validate
		return areasByRegion.get(key);
	}

	/**
	 * The provinces in this region — the de-duplicated union of its areas'
	 * provinces (areas are the source of truth for region membership).
	 *
	 * @param key a region {@code raw_key}
	 * @return the region's provinces (unmodifiable, possibly empty)
	 * @throws IllegalArgumentException if no region has that key
	 */
	public List<Province> provincesInRegion(String key) {
		region(key); // validate
		return provincesByRegion.get(key);
	}

	/**
	 * The area a province belongs to, via its {@link Province#areaKey()}.
	 *
	 * @param provinceId a province id
	 * @return the province's area, or empty if it has none (or its key resolves
	 *         to no loaded area)
	 * @throws IllegalArgumentException if no province has that id
	 */
	public Optional<Area> areaOf(int provinceId) {
		String key = province(provinceId).areaKey();
		return key == null ? Optional.empty()
				: Optional.ofNullable(areasByKey.get(key));
	}

	/**
	 * The region a province belongs to, resolved through its {@link #areaOf(int)
	 * area} (areas being the source of truth for region membership).
	 *
	 * @param provinceId a province id
	 * @return the province's region, or empty if its area maps to no region
	 * @throws IllegalArgumentException if no province has that id
	 */
	public Optional<Region> regionOf(int provinceId) {
		return areaOf(provinceId)
				.map(a -> regionKeyByArea.get(a.rawKey()))
				.map(regionsByKey::get);
	}

	/** All continents, in load order (unmodifiable). */
	public Collection<Continent> continents() {
		return Collections.unmodifiableCollection(continentsByKey.values());
	}

	/**
	 * The continent with this {@code raw_key}.
	 *
	 * @param key a continent {@code raw_key} (e.g. {@code "asia"})
	 * @return the continent
	 * @throws IllegalArgumentException if no continent has that key
	 */
	public Continent continent(String key) {
		Continent c = continentsByKey.get(key);
		if (c == null)
			throw new IllegalArgumentException("no continent with key " + key);
		return c;
	}

	/** Whether a continent with this key is loaded. */
	public boolean hasContinent(String key) {
		return continentsByKey.containsKey(key);
	}

	/**
	 * The provinces this continent contains (those present in the map, in source
	 * order; ids referring outside the map are omitted).
	 *
	 * @param key a continent {@code raw_key}
	 * @return the continent's provinces (unmodifiable, possibly empty)
	 * @throws IllegalArgumentException if no continent has that key
	 */
	public List<Province> provincesInContinent(String key) {
		continent(key); // validate
		return provincesByContinent.get(key);
	}

	/**
	 * The continent a province belongs to, via its {@link Province#continentKey()}.
	 *
	 * @param provinceId a province id
	 * @return the province's continent, or empty if it has none (or its key
	 *         resolves to no loaded continent)
	 * @throws IllegalArgumentException if no province has that id
	 */
	public Optional<Continent> continentOf(int provinceId) {
		String key = province(provinceId).continentKey();
		return key == null ? Optional.empty()
				: Optional.ofNullable(continentsByKey.get(key));
	}

	/**
	 * A shortest path between two provinces over the neighbor graph (a
	 * breadth-first walk, so each edge counts as one step). The returned list is
	 * inclusive of both endpoints; a single-element list is returned when
	 * {@code from == to}, and an empty list when {@code to} is unreachable.
	 *
	 * @param from the start province id
	 * @param to   the goal province id
	 * @return the path of province ids from {@code from} to {@code to} inclusive,
	 *         or an empty list if there is no route
	 * @throws IllegalArgumentException if either id is not in the map
	 */
	public List<Integer> path(int from, int to) {
		province(from); // validate endpoints exist
		province(to);
		if (from == to)
			return List.of(from);
		Map<Integer, Integer> cameFrom = new HashMap<>();
		cameFrom.put(from, from);
		Deque<Integer> queue = new ArrayDeque<>();
		queue.add(from);
		while (!queue.isEmpty()) {
			int cur = queue.poll();
			for (int next : byId.get(cur).neighbors()) {
				if (cameFrom.containsKey(next))
					continue;
				cameFrom.put(next, cur);
				if (next == to)
					return reconstruct(cameFrom, from, to);
				queue.add(next);
			}
		}
		return List.of();
	}

	private static List<Integer> reconstruct(Map<Integer, Integer> cameFrom,
			int from, int to) {
		List<Integer> path = new ArrayList<>();
		for (int cur = to; cur != from; cur = cameFrom.get(cur))
			path.add(cur);
		path.add(from);
		Collections.reverse(path);
		return path;
	}
}
