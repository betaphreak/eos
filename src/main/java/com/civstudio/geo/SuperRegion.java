package com.civstudio.geo;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One super-region of the world map: the geographic tier above the {@link
 * Region}, loaded once from the committed {@code /superregions.json} resource as
 * part of the per-{@link com.civstudio.settlement.GameSession} {@link WorldMap}.
 * Like the province graph, areas, regions and continents it is pure reference
 * data — independent of seed and run.
 * <p>
 * A super-region groups regions exactly as a region groups areas, so the nesting
 * is <b>province &rarr; area &rarr; region &rarr; super-region</b>. This record
 * carries its constituent region {@code raw_key}s straight from the Anbennar
 * {@code superregion.txt}, flattened by {@link
 * com.civstudio.geo.export.SuperRegionExporter}; the {@link WorldMap} resolves
 * them to regions and (via the region&rarr;area tier) to provinces.
 * <p>
 * The source lists only region keys (plus the {@code restrict_charter} keyword,
 * dropped at export); a few referenced regions may be empty placeholders absent
 * from {@code regions.json}, so the {@link WorldMap} resolves leniently — see
 * {@link WorldMap#regionsInSuperRegion(String)}.
 *
 * @param rawKey     the super-region's stable {@code raw_key} (e.g.
 *                   {@code "rahen_superregion"})
 * @param name       the super-region's display name, title-cased from the key
 *                   with the {@code _superregion} suffix dropped (e.g.
 *                   {@code "Rahen"})
 * @param regionKeys the {@code raw_key}s of the regions it is composed of, in
 *                   source-file order
 */
public record SuperRegion(
		@JsonProperty("key") String rawKey,
		String name,
		@JsonProperty("regions") List<String> regionKeys) {

	/** Defensive copy so a loaded super-region's region list cannot be mutated. */
	public SuperRegion {
		regionKeys = regionKeys == null ? List.of() : List.copyOf(regionKeys);
	}
}
