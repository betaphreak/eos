package com.civstudio.geo;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One culture of the imported Anbennar world, loaded once from the committed
 * {@code /map/cultures.json} resource as part of the per-{@link
 * com.civstudio.settlement.GameSession} {@link WorldMap}. Pure reference data,
 * independent of seed and run.
 * <p>
 * A culture is the territorial cultural layer: an owned {@link Province} carries
 * the {@link Province#culture() culture raw_key}, so the two join on that key (the
 * {@link WorldMap} groups provinces by it — see {@link
 * WorldMap#provincesOfCulture(String)}). The source is the Anbennar
 * {@code common/cultures/anb_cultures.txt} (culture group &rarr; culture),
 * flattened to this record by {@link com.civstudio.geo.export.CultureExporter}.
 * <p>
 * This is distinct from the per-<em>person</em> {@link com.civstudio.race.Race}
 * enum, which repurposes the same source for names and demography; {@code Culture}
 * is the per-province map layer.
 *
 * @param key   the culture's stable {@code raw_key} (e.g. {@code "west_damerian"})
 *              — the value {@link Province#culture()} references
 * @param name  the culture's display name, title-cased from the key
 *              (e.g. {@code "West Damerian"})
 * @param group the {@code raw_key} of the culture group it belongs to
 *              (e.g. {@code "anbennarian"})
 * @param color the culture's map colour as a {@code "#rrggbb"} hex string
 *              (auto-generated — EU4 culture files carry no colour)
 */
public record Culture(
		@JsonProperty("key") String key,
		String name,
		String group,
		String color) {
}
