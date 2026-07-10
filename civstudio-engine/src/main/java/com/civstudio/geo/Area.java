package com.civstudio.geo;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One area of the world map: the finer geographic tier between {@link Province}
 * and {@link Region}, loaded once from the committed {@code /areas.json} resource
 * as part of the per-{@link com.civstudio.settlement.GameSession} {@link
 * WorldMap}. Like the province graph and the regions it is pure reference data —
 * independent of seed and run.
 * <p>
 * An area groups a handful of provinces; a {@link Region} in turn groups areas
 * (see {@link Region#areaKeys()}), so the hierarchy is
 * province&nbsp;&rarr;&nbsp;area&nbsp;&rarr;&nbsp;region. Every province names its
 * area via {@link Province#areaKey()}; this record carries the inverse — the
 * {@code province_id}s it contains, straight from the Anbennar {@code area.txt}
 * (a Clausewitz file mapping each area to its province ids), flattened by {@link
 * com.civstudio.geo.export.AreaExporter}.
 * <p>
 * The {@link #provinceIds()} are kept verbatim from the source: a few may refer
 * to ids not present in the loaded map (e.g. Random-New-World provinces), so the
 * {@link WorldMap} resolves them leniently — see {@link
 * WorldMap#provincesInArea(String)}.
 *
 * @param rawKey      the area's stable {@code raw_key} (e.g.
 *                    {@code "inner_rahen_area"}) — the value {@link
 *                    Province#areaKey()} references
 * @param name        the area's display name, title-cased from the key with the
 *                    {@code _area} suffix dropped (e.g. {@code "Inner Rahen"})
 * @param provinceIds the {@code province_id}s the area contains, in source-file
 *                    order
 */
public record Area(
		@JsonProperty("key") String rawKey,
		String name,
		@JsonProperty("provinces") List<Integer> provinceIds) implements GeoTier {

	/** Defensive copy so a loaded area's province list cannot be mutated. */
	public Area {
		provinceIds = provinceIds == null ? List.of() : List.copyOf(provinceIds);
	}

	/** {@inheritDoc} The area's {@link #name()}. */
	@Override
	public String displayName() {
		return name;
	}
}
