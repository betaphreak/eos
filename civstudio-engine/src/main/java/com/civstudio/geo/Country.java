package com.civstudio.geo;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One country (an EU4 tag) of the imported Anbennar world, loaded once from the
 * committed {@code /map/countries.json} resource as part of the per-{@link
 * com.civstudio.settlement.GameSession} {@link WorldMap}. Like the province graph
 * it is pure reference data — independent of seed and run.
 * <p>
 * A country is the political owner of provinces at the game-start bookmark: every
 * owned {@link Province} carries the {@link Province#ownerTag() owner tag} of the
 * country it belongs to, so the two join on that tag (the {@link WorldMap} groups
 * provinces by it — see {@link WorldMap#provincesOwnedBy(String)}). The source is
 * the Anbennar {@code common/country_tags} + {@code common/countries} files,
 * flattened to this record by {@link com.civstudio.geo.export.CountryExporter}.
 *
 * @param tag   the country's stable EU4 tag (e.g. {@code "A04"}) — the value
 *              {@link Province#ownerTag()} references
 * @param name  the country's display name, derived from its definition-file stem
 *              (e.g. {@code "Wesdam"})
 * @param color the country's map colour as a {@code "#rrggbb"} hex string (its
 *              {@code common/countries} {@code color = { r g b }}, or a
 *              deterministic fallback when the source has none)
 */
public record Country(
		String tag,
		String name,
		String color) {
}
