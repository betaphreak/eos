package com.civstudio.geo;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One trade good of the imported Anbennar world, loaded once from the committed
 * {@code /map/tradegoods.json} resource as part of the per-{@link
 * com.civstudio.settlement.GameSession} {@link WorldMap}. Pure reference data,
 * independent of seed and run — the trade-good analogue of {@link Country} /
 * {@link Culture} / {@link Religion}.
 * <p>
 * A trade good is the per-province resource layer: an owned, discovered {@link
 * Province} carries the {@link Province#tradeGood() trade-good raw_key}, so the two
 * join on that key (the {@link WorldMap} groups provinces by it — see {@link
 * WorldMap#provincesOfTradeGood(String)}). The source is the Anbennar
 * {@code common/tradegoods/00_tradegoods.txt} definitions (each a {@code goodname =
 * { color = { r g b } … }} block), flattened to this record by {@link
 * com.civstudio.geo.export.TradeGoodExporter}; the {@link #category()} is a
 * hand-authored classification added by that exporter (the source has none). This
 * mirrors the per-plot {@link Bonus} but at province granularity — bonuses sit on
 * individual plots, a trade good tags the whole province.
 *
 * @param key      the good's stable {@code raw_key} (e.g. {@code "grain"}) — the
 *                 value {@link Province#tradeGood()} references
 * @param name     the good's display name, title-cased from the key
 *                 (e.g. {@code "Grain"}, {@code "Precursor Relics"})
 * @param color    the good's map colour as a {@code "#rrggbb"} hex string, from its
 *                 source {@code color = { r g b }} (float 0..1 channels)
 * @param category the good's {@link TradeGoodClass} bucket (hand-authored — the
 *                 source carries no category)
 */
public record TradeGood(
		@JsonProperty("key") String key,
		String name,
		String color,
		TradeGoodClass category) {
}
