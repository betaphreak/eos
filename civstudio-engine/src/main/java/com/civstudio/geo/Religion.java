package com.civstudio.geo;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One religion of the imported Anbennar world, loaded once from the committed
 * {@code /map/religions.json} resource as part of the per-{@link
 * com.civstudio.settlement.GameSession} {@link WorldMap}. Pure reference data,
 * independent of seed and run.
 * <p>
 * A religion is the territorial faith layer: an owned {@link Province} carries the
 * {@link Province#religion() religion raw_key}, so the two join on that key (the
 * {@link WorldMap} groups provinces by it — see {@link
 * WorldMap#provincesOfReligion(String)}). The source is the Anbennar
 * {@code common/religions} files (religion group &rarr; religion &rarr;
 * {@code color}), flattened to this record by {@link
 * com.civstudio.geo.export.ReligionExporter}.
 *
 * @param key   the religion's stable {@code raw_key} (e.g. {@code "regent_court"})
 *              — the value {@link Province#religion()} references
 * @param name  the religion's display name, title-cased from the key
 *              (e.g. {@code "Regent Court"})
 * @param group the {@code raw_key} of the religion group it belongs to
 *              (e.g. {@code "cannorian"})
 * @param color the religion's map colour as a {@code "#rrggbb"} hex string (its
 *              source {@code color = { r g b }})
 */
public record Religion(
		@JsonProperty("key") String key,
		String name,
		String group,
		String color) {
}
