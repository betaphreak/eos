package com.civstudio.geo;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One region of the world map: a named grouping of provinces, loaded once from
 * the committed {@code /regions.json} resource as part of the per-{@link
 * com.civstudio.settlement.GameSession} {@link WorldMap}. Like the province
 * graph it is pure reference data — independent of seed and run.
 * <p>
 * A region is the coarse geographic tier above the province: every settleable
 * {@link Province} carries the {@link Province#regionKey() raw_key} of the
 * region it belongs to, so the two join on that key (the {@link WorldMap} groups
 * provinces by it — see {@link WorldMap#provincesInRegion(String)}). The source
 * is the Anbennar {@code region.txt} (a Clausewitz file mapping each region to a
 * list of <em>areas</em>), flattened to this record by {@link
 * com.civstudio.geo.export.RegionExporter}.
 * <p>
 * The {@link #areaKeys()} are the region's constituent area {@code raw_key}s
 * straight from the source file. eos has no area tier of its own — provinces are
 * grouped directly by their region — so these are retained as reference metadata
 * (the area an export query flattened away), not a graph eos routes over.
 *
 * @param rawKey   the region's stable {@code raw_key} (e.g.
 *                 {@code "rahen_coast_region"}) — the value {@link
 *                 Province#regionKey()} references
 * @param name     the region's display name, title-cased from the key with the
 *                 {@code _region} suffix dropped (e.g. {@code "Rahen Coast"})
 * @param areaKeys the {@code raw_key}s of the areas the region is composed of,
 *                 in source-file order
 */
public record Region(
		@JsonProperty("key") String rawKey,
		String name,
		@JsonProperty("areas") List<String> areaKeys) {

	/** Defensive copy so a loaded region's area list cannot be mutated. */
	public Region {
		areaKeys = areaKeys == null ? List.of() : List.copyOf(areaKeys);
	}
}
