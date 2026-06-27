package com.civstudio.geo;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One continent of the world map: the coarsest geographic tier, loaded once from
 * the committed {@code /continents.json} resource as part of the per-{@link
 * com.civstudio.settlement.GameSession} {@link WorldMap}. Like the province graph,
 * areas and regions it is pure reference data — independent of seed and run.
 * <p>
 * Unlike the {@link Region}&rarr;{@link Area} nesting, a continent groups
 * provinces <em>directly</em> (the source {@code continent.txt} maps each
 * continent to a flat list of province ids, with no area/region link), so it is a
 * parallel, top-level partition of the map rather than a container of regions.
 * Every province names its continent via {@link Province#continentKey()}; this
 * record carries the inverse — the {@code province_id}s it contains, straight from
 * the Anbennar {@code continent.txt}, flattened by {@link
 * com.civstudio.geo.export.ContinentExporter}.
 * <p>
 * Only the geographic continents are loaded; the file's non-geographic utility
 * pseudo-continents ({@code debug_continent}, {@code island_check_provinces},
 * {@code new_world}) are skipped at export. The {@link #provinceIds()} are kept
 * verbatim from the source: a few may refer to ids not present in the loaded map,
 * so the {@link WorldMap} resolves them leniently — see {@link
 * WorldMap#provincesInContinent(String)}.
 *
 * @param rawKey      the continent's stable {@code raw_key} (e.g. {@code "asia"})
 *                    — the value {@link Province#continentKey()} references
 * @param name        the continent's display name, title-cased from the key (e.g.
 *                    {@code "North America"})
 * @param provinceIds the {@code province_id}s the continent contains, in
 *                    source-file order
 */
public record Continent(
		@JsonProperty("key") String rawKey,
		String name,
		@JsonProperty("provinces") List<Integer> provinceIds) {

	/** Defensive copy so a loaded continent's province list cannot be mutated. */
	public Continent {
		provinceIds = provinceIds == null ? List.of() : List.copyOf(provinceIds);
	}
}
