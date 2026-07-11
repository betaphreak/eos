package com.civstudio.geo;

/**
 * The economic category of a {@link TradeGood} — a coarse classification of the
 * ~36 Anbennar/EU4 trade goods into five buckets, mirroring the spirit of {@link
 * BonusClass} (the per-plot bonus placement category) but for the per-province
 * trade good. Unlike {@code BonusClass}, this is <em>not</em> present in the source
 * data: EU4 {@code common/tradegoods} files carry no category, so the mapping is
 * hand-authored in {@link com.civstudio.geo.export.TradeGoodExporter} and baked
 * into {@code /map/tradegoods.json}.
 * <p>
 * The classification is deliberately a standalone enum (it does not yet map to the
 * consumer-good {@code ResourceType} the way {@code BonusClass} does) — the trade
 * good is reference data today; wiring it into the export/market economy is a later
 * step. See {@code docs/trade-goods.md}.
 */
public enum TradeGoodClass {

	/** Edible staples: grain, fish, salt, livestock. */
	FOOD,

	/** Trade luxuries and raw commodities: wine, spices, silk, furs, gems, dyes, … */
	LUXURY,

	/** Metals, minerals and war materials: iron, copper, coal, gold, naval supplies, … */
	STRATEGIC,

	/** Crafted/processed goods: cloth, chinaware, glass, paper. */
	MANUFACTURED,

	/** The Anbennar-specific magical resources: damestear, mithril, precursor relics, … */
	MAGICAL
}
