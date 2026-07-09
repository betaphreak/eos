package com.civstudio.geo;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The world map: the {@link Province} graph imported from the Strapi world
 * content and loaded once from {@code /map/provinces.json}. It is pure reference
 * data — independent of seed and run — so a single instance is shared by every
 * colony in a {@link com.civstudio.settlement.GameSession}, which loads it
 * lazily on first request (the ~1MB parse is only paid by a session that uses
 * geography) and exposes it via {@code getWorldMap()}.
 * <p>
 * The map provides province lookup, the (undirected) neighbor adjacency, and a
 * shortest-path query over that adjacency — the travel-network primitive the
 * caravan and village-founding features build on. It also carries the coarser
 * geographic tiers — the province&nbsp;&rarr;&nbsp;{@link Area area}&nbsp;&rarr;&nbsp;{@link
 * Region region}&nbsp;&rarr;&nbsp;{@link SuperRegion super-region} nesting (loaded
 * from {@code /map/areas.json}, {@code /map/regions.json}, {@code
 * /map/superregions.json}) plus
 * the parallel {@link Continent} partition (a fixed enum; per-province membership
 * lives on {@link Province#continent()}) — and the membership queries over them
 * ({@link #provincesInRegion(String)}, {@link #areaOf(int)}, {@link
 * #provincesInContinent(Continent)}, …), with areas the source of truth for a
 * region's provinces. It does not itself move anything: founding a colony into a
 * province (Phase 2) and routing over the graph (caravan trade) are the dependent
 * features. See {@code docs/geography.md}.
 */
public final class WorldMap {

	private static final String PROVINCES_RESOURCE = "/map/provinces.json";
	private static final String AREAS_RESOURCE = "/map/areas.json";
	private static final String REGIONS_RESOURCE = "/map/regions.json";
	private static final String SUPERREGIONS_RESOURCE = "/map/superregions.json";
	private static final String EDGES_RESOURCE = "/map/edges.json";
	private static final String ADJACENCIES_RESOURCE = "/map/adjacencies.json";
	private static final String PORTALS_RESOURCE = "/map/portals.json";
	private static final String COUNTRIES_RESOURCE = "/map/countries.json";
	private static final String CULTURES_RESOURCE = "/map/cultures.json";
	private static final String RELIGIONS_RESOURCE = "/map/religions.json";

	/** Mean Earth radius in km — the great-circle scale for {@link #distanceKm}. */
	private static final double EARTH_RADIUS_KM = 6371.0;

	private static final ObjectMapper MAPPER = new ObjectMapper()
			.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

	// provinces keyed by id, in load order (deterministic iteration)
	private final Map<Integer, Province> byId;
	// areas, regions and super-regions keyed by their raw_key, in load order
	// (continents are a fixed enum, not loaded)
	private final Map<String, Area> areasByKey;
	private final Map<String, Region> regionsByKey;
	private final Map<String, SuperRegion> superRegionsByKey;
	// derived membership indices (all values unmodifiable, deterministic order)
	private final Map<String, List<Province>> provincesByArea;
	private final Map<String, List<Province>> provincesByRegion;
	private final Map<String, List<Province>> provincesBySuperRegion;
	private final Map<Continent, List<Province>> provincesByContinent;
	private final Map<Climate, List<Province>> provincesByClimate;
	private final Map<WinterSeverity, List<Province>> provincesByWinter;
	private final Map<Monsoon, List<Province>> provincesByMonsoon;
	private final Map<String, List<Area>> areasByRegion;
	private final Map<String, List<Region>> regionsBySuperRegion;
	private final Map<String, String> regionKeyByArea;
	private final Map<String, String> superRegionKeyByRegion;
	// political reference data (committed /map/{countries,cultures,religions}.json),
	// keyed by tag/raw_key, plus the derived province membership indices. Empty when a
	// resource is absent (e.g. while an exporter bootstraps the map). See the political
	// stampers (ProvinceHistoryExporter) and metadata exporters.
	private final Map<String, Country> countriesByTag;
	private final Map<String, Culture> culturesByKey;
	private final Map<String, Religion> religionsByKey;
	private final Map<String, List<Province>> provincesByOwner;
	private final Map<String, List<Province>> provincesByCulture;
	private final Map<String, List<Province>> provincesByReligion;
	// committed per-edge great-circle km, keyed by province id and aligned to that
	// province's neighbors() order (from the LandRouteExporter's /map/edges.json). Empty
	// when the resource is absent (e.g. while the exporter itself bootstraps the map),
	// in which case edgeKm falls back to the runtime centroid distance. See docs/land-routing.md.
	private final Map<Integer, double[]> edgeKmById;

	// the special EU4 adjacencies (straits/canals/tunnels/passes) as extra graph edges: per
	// province, its pixel neighbours() PLUS the adjacency endpoints (deduped). Only provinces that
	// have an adjacency edge appear; every other province routes on its neighbours() directly.
	// Empty when /map/adjacencies.json is absent. See Adjacency / docs/land-routing.md.
	private final Map<Integer, List<Integer>> combinedNeighbors;
	// the raw adjacency records, for callers that need the connection metadata (type/comment)
	private final List<Adjacency> adjacencies;
	// committed border-portal anchors, keyed by the directed edge (from<<32 | to) ->
	// {x, y} raster pixel on `from`'s side of the shared border (from /map/portals.json,
	// the PortalExporter). Empty when the resource is absent (edgeless routing / the
	// exporter bootstrapping). See docs/land-routing.md.
	private final Map<Long, int[]> portalByEdge;

	private WorldMap(List<Province> provinces, List<Area> areas,
			List<Region> regions, List<SuperRegion> superRegions,
			List<Country> countries, List<Culture> cultures, List<Religion> religions,
			List<ProvinceEdges> edges, List<ProvincePortals> portals,
			List<Adjacency> adjacencies) {
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

		// merge the special EU4 adjacencies (straits/canals/tunnels/passes) into the graph as extra
		// bidirectional edges, so routing (LandRouter, path) traverses a tunnel/strait the raster
		// pixel adjacency never captured. Endpoints not present in the map are skipped (defensive).
		this.adjacencies = List.copyOf(adjacencies);
		Map<Integer, Set<Integer>> extra = new HashMap<>();
		for (Adjacency a : adjacencies) {
			if (!byId.containsKey(a.from()) || !byId.containsKey(a.to()) || a.from() == a.to())
				continue;
			extra.computeIfAbsent(a.from(), k -> new LinkedHashSet<>()).add(a.to());
			extra.computeIfAbsent(a.to(), k -> new LinkedHashSet<>()).add(a.from());
		}
		Map<Integer, List<Integer>> combined = new HashMap<>(extra.size() * 2);
		for (Map.Entry<Integer, Set<Integer>> e : extra.entrySet()) {
			List<Integer> base = byId.get(e.getKey()).neighbors();
			List<Integer> all = new ArrayList<>(base);
			for (int n : e.getValue())
				if (!base.contains(n))
					all.add(n);
			combined.put(e.getKey(), List.copyOf(all));
		}
		this.combinedNeighbors = combined;

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

		Map<String, SuperRegion> superByKey = new LinkedHashMap<>(
				superRegions.size() * 2);
		for (SuperRegion sr : superRegions)
			if (superByKey.put(sr.rawKey(), sr) != null)
				throw new IllegalStateException(
						"duplicate super-region key " + sr.rawKey());
		this.superRegionsByKey = superByKey;

		// super-region -> its regions / provinces, via the region tier (regions
		// that resolve to no loaded region — empty placeholders — are skipped;
		// provinces are the de-duplicated union over the resolved regions)
		Map<String, List<Region>> regionsBySuper = new LinkedHashMap<>();
		Map<String, List<Province>> provBySuper = new LinkedHashMap<>();
		Map<String, String> superByRegion = new HashMap<>();
		for (SuperRegion sr : superRegions) {
			List<Region> rr = new ArrayList<>();
			LinkedHashSet<Province> sp = new LinkedHashSet<>();
			for (String rk : sr.regionKeys()) {
				Region r = regionsByKey.get(rk);
				if (r == null)
					continue;
				rr.add(r);
				superByRegion.put(rk, sr.rawKey());
				sp.addAll(provByRegion.get(rk));
			}
			regionsBySuper.put(sr.rawKey(), Collections.unmodifiableList(rr));
			provBySuper.put(sr.rawKey(), List.copyOf(sp));
		}
		this.regionsBySuperRegion = regionsBySuper;
		this.provincesBySuperRegion = provBySuper;
		this.superRegionKeyByRegion = superByRegion;

		// environmental-attribute partitions, grouped from each province's own
		// enum value (continent may be null; climate/winter/monsoon never are)
		Map<Continent, List<Province>> provByContinent = new EnumMap<>(Continent.class);
		Map<Climate, List<Province>> provByClimate = new EnumMap<>(Climate.class);
		Map<WinterSeverity, List<Province>> provByWinter = new EnumMap<>(WinterSeverity.class);
		Map<Monsoon, List<Province>> provByMonsoon = new EnumMap<>(Monsoon.class);
		for (Province p : byId.values()) {
			if (p.continent() != null)
				provByContinent.computeIfAbsent(p.continent(), k -> new ArrayList<>()).add(p);
			provByClimate.computeIfAbsent(p.climate(), k -> new ArrayList<>()).add(p);
			provByWinter.computeIfAbsent(p.winter(), k -> new ArrayList<>()).add(p);
			provByMonsoon.computeIfAbsent(p.monsoon(), k -> new ArrayList<>()).add(p);
		}
		provByContinent.replaceAll((c, ps) -> Collections.unmodifiableList(ps));
		provByClimate.replaceAll((c, ps) -> Collections.unmodifiableList(ps));
		provByWinter.replaceAll((w, ps) -> Collections.unmodifiableList(ps));
		provByMonsoon.replaceAll((mo, ps) -> Collections.unmodifiableList(ps));
		this.provincesByContinent = provByContinent;
		this.provincesByClimate = provByClimate;
		this.provincesByWinter = provByWinter;
		this.provincesByMonsoon = provByMonsoon;

		// political reference tables, keyed by tag/raw_key (absent resource -> empty map)
		Map<String, Country> byTag = new LinkedHashMap<>(countries.size() * 2);
		for (Country c : countries)
			byTag.put(c.tag(), c);
		this.countriesByTag = Collections.unmodifiableMap(byTag);
		Map<String, Culture> byCultureKey = new LinkedHashMap<>(cultures.size() * 2);
		for (Culture c : cultures)
			byCultureKey.put(c.key(), c);
		this.culturesByKey = Collections.unmodifiableMap(byCultureKey);
		Map<String, Religion> byReligionKey = new LinkedHashMap<>(religions.size() * 2);
		for (Religion r : religions)
			byReligionKey.put(r.key(), r);
		this.religionsByKey = Collections.unmodifiableMap(byReligionKey);

		// derived political partitions, grouped from each province's own tag/key
		// (owner/culture/religion may be null — unowned/uncolonized provinces are skipped)
		Map<String, List<Province>> provByOwner = new LinkedHashMap<>();
		Map<String, List<Province>> provByCulture = new LinkedHashMap<>();
		Map<String, List<Province>> provByReligion = new LinkedHashMap<>();
		for (Province p : byId.values()) {
			if (p.ownerTag() != null)
				provByOwner.computeIfAbsent(p.ownerTag(), k -> new ArrayList<>()).add(p);
			if (p.culture() != null)
				provByCulture.computeIfAbsent(p.culture(), k -> new ArrayList<>()).add(p);
			if (p.religion() != null)
				provByReligion.computeIfAbsent(p.religion(), k -> new ArrayList<>()).add(p);
		}
		provByOwner.replaceAll((k, ps) -> Collections.unmodifiableList(ps));
		provByCulture.replaceAll((k, ps) -> Collections.unmodifiableList(ps));
		provByReligion.replaceAll((k, ps) -> Collections.unmodifiableList(ps));
		this.provincesByOwner = Collections.unmodifiableMap(provByOwner);
		this.provincesByCulture = Collections.unmodifiableMap(provByCulture);
		this.provincesByReligion = Collections.unmodifiableMap(provByReligion);

		// committed edge weights, if the /map/edges.json resource is present: each
		// entry's km[] aligns to the province's neighbors() order (validated), so an
		// edge weight is a plain index lookup. Absent -> empty, and edgeKm falls back
		// to the runtime centroid distance.
		Map<Integer, double[]> edgeKm = new HashMap<>(edges.size() * 2);
		for (ProvinceEdges e : edges) {
			Province p = byId.get(e.id());
			if (p == null)
				continue; // an edge record for a province not in the map — skip
			double[] km = e.km();
			if (km.length != p.neighbors().size())
				throw new IllegalStateException("province " + e.id() + " has "
						+ p.neighbors().size() + " neighbors but " + km.length
						+ " edge weights");
			edgeKm.put(e.id(), km);
		}
		this.edgeKmById = edgeKm;

		// committed border portals, keyed by directed edge (from<<32 | to)
		Map<Long, int[]> portalMap = new HashMap<>(portals.size() * 4);
		for (ProvincePortals pp : portals)
			for (ProvincePortals.Portal portal : pp.portals())
				portalMap.put(edgeKey(pp.id(), portal.to()),
						new int[] { portal.x(), portal.y() });
		this.portalByEdge = portalMap;
	}

	// pack a directed edge (from, to) into a single long key
	private static long edgeKey(int from, int to) {
		return ((long) from << 32) | (to & 0xFFFFFFFFL);
	}

	/**
	 * Load the world map from its classpath resources ({@code /map/provinces.json},
	 * {@code /map/areas.json}, {@code /map/regions.json}, {@code
	 * /map/superregions.json}). Continents are a fixed {@link Continent} enum, not a
	 * loaded resource.
	 *
	 * @return the loaded map
	 * @throws IllegalStateException
	 *             if a resource is missing, a province/area/region/super-region key
	 *             is duplicated, or a neighbor refers to an id not present in the map
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
		List<SuperRegion> superRegions = loadList(SUPERREGIONS_RESOURCE,
				new TypeReference<List<SuperRegion>>() {
				});
		// the committed edge-weight table is optional: absent while the exporter that
		// writes it is bootstrapping the map, in which case edge weights fall back to
		// the runtime centroid distance (see edgeKm / docs/land-routing.md)
		List<ProvinceEdges> edges = loadListOptional(EDGES_RESOURCE,
				new TypeReference<List<ProvinceEdges>>() {
				});
		// the committed border-portal table is optional too (absent while the exporter
		// bootstraps, or on a map with no committed portals yet)
		List<ProvincePortals> portals = loadListOptional(PORTALS_RESOURCE,
				new TypeReference<List<ProvincePortals>>() {
				});
		// the special EU4 adjacencies (straits/canals/tunnels/passes) are optional too — absent
		// while the exporter bootstraps, in which case the graph is the raster pixel adjacency alone
		List<Adjacency> adjacencies = loadListOptional(ADJACENCIES_RESOURCE,
				new TypeReference<List<Adjacency>>() {
				});
		// the political reference tables are optional: absent while their exporters
		// bootstrap, in which case the country/culture/religion lookups are empty and
		// the derived owner/culture/religion indices carry no entries
		List<Country> countries = loadListOptional(COUNTRIES_RESOURCE,
				new TypeReference<List<Country>>() {
				});
		List<Culture> cultures = loadListOptional(CULTURES_RESOURCE,
				new TypeReference<List<Culture>>() {
				});
		List<Religion> religions = loadListOptional(RELIGIONS_RESOURCE,
				new TypeReference<List<Religion>>() {
				});
		return new WorldMap(provinces, areas, regions, superRegions,
				countries, cultures, religions, edges, portals, adjacencies);
	}

	/**
	 * The special EU4 {@link Adjacency adjacencies} (straits/canals/tunnels/passes) merged into
	 * this map's routing graph — the connections between provinces that are not visually adjacent.
	 * Empty if {@code /map/adjacencies.json} was absent.
	 *
	 * @return an unmodifiable view of the adjacency records
	 */
	public List<Adjacency> adjacencies() {
		return adjacencies;
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

	// like loadList, but a missing resource yields an empty list rather than throwing —
	// for the optional committed edge-weight table (absent while the exporter bootstraps)
	private static <T> List<T> loadListOptional(String resource,
			TypeReference<List<T>> type) {
		try (InputStream in = WorldMap.class.getResourceAsStream(resource)) {
			if (in == null)
				return List.of();
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
		List<Integer> combined = combinedNeighbors.get(id);
		return combined != null ? combined : province(id).neighbors();
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
		return require(areasByKey, key, "area");
	}

	/** Look up a tier by key, or throw with a {@code "no <tier> with key …"} message. */
	private static <T extends GeoTier> T require(Map<String, T> byKey, String key,
			String tier) {
		T value = byKey.get(key);
		if (value == null)
			throw new IllegalArgumentException("no " + tier + " with key " + key);
		return value;
	}

	/**
	 * The region with this {@code raw_key}.
	 *
	 * @param key a region {@code raw_key} (e.g. {@code "rahen_coast_region"})
	 * @return the region
	 * @throws IllegalArgumentException if no region has that key
	 */
	public Region region(String key) {
		return require(regionsByKey, key, "region");
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

	// ---- political layer (owner / culture / religion) -------------------------

	/**
	 * The {@link Country} with this tag, if the {@code countries.json} resource is
	 * loaded and carries it.
	 *
	 * @param tag a country tag (e.g. {@code "A04"}) — see {@link Province#ownerTag()}
	 * @return the country, or empty
	 */
	public Optional<Country> country(String tag) {
		return Optional.ofNullable(countriesByTag.get(tag));
	}

	/**
	 * The {@link Culture} with this key, if the {@code cultures.json} resource is
	 * loaded and carries it.
	 *
	 * @param key a culture {@code raw_key} (e.g. {@code "west_damerian"})
	 * @return the culture, or empty
	 */
	public Optional<Culture> culture(String key) {
		return Optional.ofNullable(culturesByKey.get(key));
	}

	/**
	 * The {@link Religion} with this key, if the {@code religions.json} resource is
	 * loaded and carries it.
	 *
	 * @param key a religion {@code raw_key} (e.g. {@code "regent_court"})
	 * @return the religion, or empty
	 */
	public Optional<Religion> religion(String key) {
		return Optional.ofNullable(religionsByKey.get(key));
	}

	/** All loaded countries (unmodifiable, in tag order; empty if the resource is absent). */
	public Collection<Country> countries() {
		return countriesByTag.values();
	}

	/** All loaded cultures (unmodifiable, in key order; empty if the resource is absent). */
	public Collection<Culture> cultures() {
		return culturesByKey.values();
	}

	/** All loaded religions (unmodifiable, in key order; empty if the resource is absent). */
	public Collection<Religion> religions() {
		return religionsByKey.values();
	}

	/** The tags that own at least one province (the keys of the owner index). */
	public Collection<String> owners() {
		return provincesByOwner.keySet();
	}

	/**
	 * The provinces owned by a country at the game-start bookmark.
	 *
	 * @param tag a country tag
	 * @return that country's provinces (unmodifiable, possibly empty)
	 */
	public List<Province> provincesOwnedBy(String tag) {
		return provincesByOwner.getOrDefault(tag, List.of());
	}

	/**
	 * The provinces of a culture.
	 *
	 * @param key a culture {@code raw_key}
	 * @return that culture's provinces (unmodifiable, possibly empty)
	 */
	public List<Province> provincesOfCulture(String key) {
		return provincesByCulture.getOrDefault(key, List.of());
	}

	/**
	 * The provinces of a religion.
	 *
	 * @param key a religion {@code raw_key}
	 * @return that religion's provinces (unmodifiable, possibly empty)
	 */
	public List<Province> provincesOfReligion(String key) {
		return provincesByReligion.getOrDefault(key, List.of());
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

	/** All super-regions, in load order (unmodifiable). */
	public Collection<SuperRegion> superRegions() {
		return Collections.unmodifiableCollection(superRegionsByKey.values());
	}

	/**
	 * The super-region with this {@code raw_key}.
	 *
	 * @param key a super-region {@code raw_key} (e.g. {@code "rahen_superregion"})
	 * @return the super-region
	 * @throws IllegalArgumentException if no super-region has that key
	 */
	public SuperRegion superRegion(String key) {
		return require(superRegionsByKey, key, "super-region");
	}

	/** Whether a super-region with this key is loaded. */
	public boolean hasSuperRegion(String key) {
		return superRegionsByKey.containsKey(key);
	}

	/**
	 * The regions this super-region is composed of (those that resolve to a loaded
	 * region, in source order; empty placeholder regions are omitted).
	 *
	 * @param key a super-region {@code raw_key}
	 * @return the super-region's regions (unmodifiable, possibly empty)
	 * @throws IllegalArgumentException if no super-region has that key
	 */
	public List<Region> regionsInSuperRegion(String key) {
		superRegion(key); // validate
		return regionsBySuperRegion.get(key);
	}

	/**
	 * The provinces in this super-region — the de-duplicated union of its regions'
	 * provinces (resolved on through the region&rarr;area tier).
	 *
	 * @param key a super-region {@code raw_key}
	 * @return the super-region's provinces (unmodifiable, possibly empty)
	 * @throws IllegalArgumentException if no super-region has that key
	 */
	public List<Province> provincesInSuperRegion(String key) {
		superRegion(key); // validate
		return provincesBySuperRegion.get(key);
	}

	/**
	 * The super-region a province belongs to, resolved through its {@link
	 * #regionOf(int) region}.
	 *
	 * @param provinceId a province id
	 * @return the province's super-region, or empty if its region maps to none
	 * @throws IllegalArgumentException if no province has that id
	 */
	public Optional<SuperRegion> superRegionOf(int provinceId) {
		return regionOf(provinceId)
				.map(r -> superRegionKeyByRegion.get(r.rawKey()))
				.map(superRegionsByKey::get);
	}

	/**
	 * The continents that have at least one province in the map. The full fixed
	 * taxonomy is {@link Continent#values()}; this is the subset actually present.
	 *
	 * @return the populated continents (unmodifiable)
	 */
	public Collection<Continent> continents() {
		return Collections.unmodifiableSet(provincesByContinent.keySet());
	}

	/**
	 * The provinces on this continent (in province-id load order).
	 *
	 * @param continent a continent
	 * @return the continent's provinces (unmodifiable, possibly empty)
	 */
	public List<Province> provincesInContinent(Continent continent) {
		return provincesByContinent.getOrDefault(continent, List.of());
	}

	/**
	 * The continent a province belongs to (its {@link Province#continent()}).
	 *
	 * @param provinceId a province id
	 * @return the province's continent, or empty if it has none
	 * @throws IllegalArgumentException if no province has that id
	 */
	public Optional<Continent> continentOf(int provinceId) {
		return Optional.ofNullable(province(provinceId).continent());
	}

	/** The provinces in this {@link Climate} band (in province-id load order). */
	public List<Province> provincesInClimate(Climate climate) {
		return provincesByClimate.getOrDefault(climate, List.of());
	}

	/** The provinces at this {@link WinterSeverity} (in province-id load order). */
	public List<Province> provincesInWinter(WinterSeverity winter) {
		return provincesByWinter.getOrDefault(winter, List.of());
	}

	/** The provinces at this {@link Monsoon} intensity (in province-id load order). */
	public List<Province> provincesInMonsoon(Monsoon monsoon) {
		return provincesByMonsoon.getOrDefault(monsoon, List.of());
	}

	/**
	 * The climate band of a province (its {@link Province#climate()} — never null,
	 * {@link Climate#TEMPERATE} by default).
	 *
	 * @param provinceId a province id
	 * @return the province's climate
	 * @throws IllegalArgumentException if no province has that id
	 */
	public Climate climateOf(int provinceId) {
		return province(provinceId).climate();
	}

	/**
	 * The winter severity of a province (its {@link Province#winter()} — never null,
	 * {@link WinterSeverity#NONE} by default).
	 *
	 * @param provinceId a province id
	 * @return the province's winter severity
	 * @throws IllegalArgumentException if no province has that id
	 */
	public WinterSeverity winterOf(int provinceId) {
		return province(provinceId).winter();
	}

	/**
	 * The monsoon intensity of a province (its {@link Province#monsoon()} — never
	 * null, {@link Monsoon#NONE} by default).
	 *
	 * @param provinceId a province id
	 * @return the province's monsoon intensity
	 * @throws IllegalArgumentException if no province has that id
	 */
	public Monsoon monsoonOf(int provinceId) {
		return province(provinceId).monsoon();
	}

	/**
	 * A shortest path between two provinces over the neighbor graph (a
	 * breadth-first walk, so each edge counts as one step). The returned list is
	 * inclusive of both endpoints; a single-element list is returned when
	 * {@code from == to}, and an empty list when {@code to} is unreachable.
	 * <p>
	 * The path routes only over <b>passable</b> provinces (see {@link
	 * Province#isPassable()}): {@link ProvinceType#IMPASSABLE} wasteland is never
	 * traversed, and an impassable {@code from}/{@code to} endpoint is unreachable
	 * (empty result) — caravans cannot cross or enter wasteland.
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
		if (!byId.get(from).isPassable() || !byId.get(to).isPassable())
			return List.of(); // cannot route into or out of impassable wasteland
		if (from == to)
			return List.of(from);
		Map<Integer, Integer> cameFrom = new HashMap<>();
		cameFrom.put(from, from);
		Deque<Integer> queue = new ArrayDeque<>();
		queue.add(from);
		while (!queue.isEmpty()) {
			int cur = queue.poll();
			for (int next : byId.get(cur).neighbors()) {
				if (cameFrom.containsKey(next) || !byId.get(next).isPassable())
					continue; // skip visited and impassable provinces
				cameFrom.put(next, cur);
				if (next == to)
					return reconstruct(cameFrom, from, to);
				queue.add(next);
			}
		}
		return List.of();
	}

	/**
	 * The great-circle (haversine) distance in km between two provinces' centroids
	 * (their {@link Province#latitude()}/{@link Province#longitude()}). Defined for any
	 * two provinces, adjacent or not — it is the metric the caravan march charges for a
	 * boundary hop and the admissible A* heuristic the {@link
	 * com.civstudio.geo.LandRouter} routes with (see {@code docs/caravan-march.md} §6,
	 * {@code docs/land-routing.md}).
	 *
	 * @param a a province id
	 * @param b a province id
	 * @return the great-circle distance between their centroids, in km (0 when
	 *         {@code a == b})
	 * @throws IllegalArgumentException if either id is not in the map
	 */
	public double distanceKm(int a, int b) {
		Province pa = province(a), pb = province(b);
		return haversineKm(pa.latitude(), pa.longitude(), pb.latitude(), pb.longitude());
	}

	// great-circle distance (km) between two lat/long points, in decimal degrees
	private static double haversineKm(double lat1, double lon1, double lat2, double lon2) {
		double dLat = Math.toRadians(lat2 - lat1);
		double dLon = Math.toRadians(lon2 - lon1);
		double s = Math.sin(dLat / 2) * Math.sin(dLat / 2)
				+ Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
						* Math.sin(dLon / 2) * Math.sin(dLon / 2);
		return 2 * EARTH_RADIUS_KM * Math.asin(Math.min(1.0, Math.sqrt(s)));
	}

	/**
	 * The travel weight in km of the edge from province {@code from} to its neighbour
	 * {@code to} — the committed {@code /map/edges.json} weight when present, else the
	 * runtime {@link #distanceKm(int, int) centroid distance} as a fallback. This is the
	 * seam by which a later exporter can ship border-aware weights without touching the
	 * router (see {@code docs/land-routing.md}).
	 *
	 * @param from a province id
	 * @param to   a neighbour of {@code from}
	 * @return the edge weight in km
	 * @throws IllegalArgumentException if {@code to} is not a neighbour of {@code from}
	 */
	public double edgeKm(int from, int to) {
		List<Integer> nb = province(from).neighbors();
		int i = nb.indexOf(to);
		if (i >= 0) {
			double[] km = edgeKmById.get(from);
			return km != null ? km[i] : distanceKm(from, to);
		}
		// a special adjacency edge (strait/canal/tunnel/pass), not in the pixel neighbours: its
		// weight isn't in the /map/edges.json table, so cost it by the great-circle distance
		List<Integer> combined = combinedNeighbors.get(from);
		if (combined != null && combined.contains(to))
			return distanceKm(from, to);
		throw new IllegalArgumentException(
				"province " + to + " is not a neighbour of " + from);
	}

	/**
	 * The <b>border portal</b> anchor on province {@code from}'s side of its shared border
	 * with neighbour {@code to} — the raster {@code {x, y}} pixel a caravan's plot corridor
	 * enters or leaves {@code from} at (Level 2 of {@code docs/land-routing.md}). Read from
	 * the committed {@code /map/portals.json}; {@code null} when no portal is recorded for
	 * that edge (the resource is absent, or the pair does not share a pixel border).
	 *
	 * @param from a province id
	 * @param to   a neighbour of {@code from}
	 * @return the {@code {x, y}} border anchor, or {@code null} if none is recorded
	 */
	public int[] portal(int from, int to) {
		int[] xy = portalByEdge.get(edgeKey(from, to));
		return xy == null ? null : xy.clone();
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
