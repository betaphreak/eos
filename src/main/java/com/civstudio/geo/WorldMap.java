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
 * caravan and village-founding features build on. It does not itself move
 * anything: founding a colony into a province (Phase 2) and routing over the
 * graph (caravan trade) are the dependent features. See {@code docs/geography.md}.
 */
public final class WorldMap {

	private static final String RESOURCE = "/provinces.json";

	private static final ObjectMapper MAPPER = new ObjectMapper()
			.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

	// provinces keyed by id, in load order (deterministic iteration)
	private final Map<Integer, Province> byId;

	private WorldMap(Map<Integer, Province> byId) {
		this.byId = byId;
	}

	/**
	 * Load the world map from its classpath resource ({@code /provinces.json}).
	 *
	 * @return the loaded map
	 * @throws IllegalStateException
	 *             if the resource is missing, a province id is duplicated, or a
	 *             neighbor refers to an id not present in the map
	 */
	public static WorldMap load() {
		try (InputStream in = WorldMap.class.getResourceAsStream(RESOURCE)) {
			if (in == null)
				throw new IllegalStateException(
						"World map resource not found: " + RESOURCE);
			List<Province> rows = MAPPER.readValue(in,
					new TypeReference<List<Province>>() {
					});
			Map<Integer, Province> byId = new LinkedHashMap<>(rows.size() * 2);
			for (Province p : rows)
				if (byId.put(p.id(), p) != null)
					throw new IllegalStateException(
							"duplicate province id " + p.id());
			// every neighbor must resolve to a province in the map (catches a
			// broken export); symmetry is materialized at export time.
			for (Province p : byId.values())
				for (int n : p.neighbors())
					if (!byId.containsKey(n))
						throw new IllegalStateException("province " + p.id()
								+ " has dangling neighbor " + n);
			return new WorldMap(byId);
		} catch (IOException e) {
			throw new UncheckedIOException(
					"Failed to load world map resource: " + RESOURCE, e);
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
