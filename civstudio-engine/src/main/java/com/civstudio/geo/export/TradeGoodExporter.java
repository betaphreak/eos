package com.civstudio.geo.export;

import com.civstudio.data.AnbennarFiles;
import com.civstudio.data.Exports;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.civstudio.geo.TradeGood;
import com.civstudio.geo.TradeGoodClass;
import tools.jackson.databind.ObjectMapper;

/**
 * Dev tool: reads the Anbennar {@code common/tradegoods/00_tradegoods.txt} and
 * writes the committed {@code generated/map/tradegoods.json} — the per-key display
 * name, map colour and (hand-authored) {@link TradeGoodClass} category the
 * trade-good layer joins each {@link com.civstudio.geo.Province#tradeGood()} to
 * (loaded by {@link com.civstudio.geo.WorldMap} into {@link
 * com.civstudio.geo.TradeGood} records). The sibling of {@link CountryExporter} /
 * {@link CultureExporter} / {@link ReligionExporter} for the per-province resource.
 * <p>
 * The source is top-level {@code goodname = { color = { r g b } … }} blocks. The
 * colour channels are floats in {@code 0..1} (e.g. {@code 0.96 0.93 0.58}), unlike
 * the {@code 0..255} ints in the culture/religion sources, so this exporter parses
 * them with a float-aware reader. The {@code unknown} placeholder good
 * (undiscovered/uncolonized provinces) is skipped — those provinces are left with a
 * {@code null} trade good, exactly as unowned provinces are left null.
 * <p>
 * EU4 trade-good files carry no economic category, so {@link #CATEGORY} is a
 * hand-authored classification; a good absent from it is emitted as {@link
 * TradeGoodClass#LUXURY} with a loud warning so a mod update that adds a good is
 * caught here.
 *
 * <pre>
 * mvn compile exec:exec -Dsim.main=com.civstudio.geo.export.TradeGoodExporter
 * </pre>
 */
public final class TradeGoodExporter {

	private static final String INPUT = "common/tradegoods/00_tradegoods.txt";
	private static final String OUTPUT = "civstudio-engine/target/generated/map/tradegoods.json";

	/** The EU4 placeholder good for undiscovered/uncolonized provinces — not a real good. */
	private static final String UNKNOWN = "unknown";

	/**
	 * Hand-authored {@code good raw_key -> category}. The source has no category, so
	 * this is the source of truth for {@link TradeGoodClass}. Covers every good in the
	 * pinned Anbennar {@code 00_tradegoods.txt}; an unmapped good defaults to {@link
	 * TradeGoodClass#LUXURY} with a warning (see {@link #categoryOf}).
	 */
	private static final Map<String, TradeGoodClass> CATEGORY = Map.ofEntries(
			// staples
			Map.entry("grain", TradeGoodClass.FOOD),
			Map.entry("fish", TradeGoodClass.FOOD),
			Map.entry("salt", TradeGoodClass.FOOD),
			Map.entry("livestock", TradeGoodClass.FOOD),
			// metals, minerals, war materials
			Map.entry("copper", TradeGoodClass.STRATEGIC),
			Map.entry("iron", TradeGoodClass.STRATEGIC),
			Map.entry("gold", TradeGoodClass.STRATEGIC),
			Map.entry("coal", TradeGoodClass.STRATEGIC),
			Map.entry("naval_supplies", TradeGoodClass.STRATEGIC),
			Map.entry("tropical_wood", TradeGoodClass.STRATEGIC),
			Map.entry("slaves", TradeGoodClass.STRATEGIC),
			// crafted / processed
			Map.entry("cloth", TradeGoodClass.MANUFACTURED),
			Map.entry("chinaware", TradeGoodClass.MANUFACTURED),
			Map.entry("glass", TradeGoodClass.MANUFACTURED),
			Map.entry("paper", TradeGoodClass.MANUFACTURED),
			// trade luxuries and raw commodities
			Map.entry("wine", TradeGoodClass.LUXURY),
			Map.entry("wool", TradeGoodClass.LUXURY),
			Map.entry("fur", TradeGoodClass.LUXURY),
			Map.entry("ivory", TradeGoodClass.LUXURY),
			Map.entry("tea", TradeGoodClass.LUXURY),
			Map.entry("spices", TradeGoodClass.LUXURY),
			Map.entry("coffee", TradeGoodClass.LUXURY),
			Map.entry("cotton", TradeGoodClass.LUXURY),
			Map.entry("sugar", TradeGoodClass.LUXURY),
			Map.entry("tobacco", TradeGoodClass.LUXURY),
			Map.entry("cocoa", TradeGoodClass.LUXURY),
			Map.entry("silk", TradeGoodClass.LUXURY),
			Map.entry("dyes", TradeGoodClass.LUXURY),
			Map.entry("incense", TradeGoodClass.LUXURY),
			Map.entry("gems", TradeGoodClass.LUXURY),
			Map.entry("cloves", TradeGoodClass.LUXURY),
			// Anbennar magical resources
			Map.entry("damestear", TradeGoodClass.MAGICAL),
			Map.entry("precursor_relics", TradeGoodClass.MAGICAL),
			Map.entry("mithril", TradeGoodClass.MAGICAL),
			Map.entry("fungi", TradeGoodClass.MAGICAL),
			Map.entry("serpentbloom", TradeGoodClass.MAGICAL));

	private TradeGoodExporter() {
	}

	public static void main(String[] args) throws Exception {
		String content = ClausewitzBlocks.stripComments(
				Files.readString(AnbennarFiles.get(INPUT), StandardCharsets.ISO_8859_1));

		List<TradeGood> goods = new ArrayList<>();
		for (ClausewitzBlocks.Block good : ClausewitzBlocks.parse(content).blocks()) {
			if (good.name().equals(UNKNOWN))
				continue; // the placeholder good — provinces stay null, not stamped
			String color = goodColor(good.body());
			goods.add(new TradeGood(good.name(),
					ClausewitzBlocks.titleCase(good.name()), color,
					categoryOf(good.name())));
		}
		goods.sort((a, b) -> a.key().compareTo(b.key()));

		File out = Exports.outFile(OUTPUT);
		new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(out, goods);
		System.out.println("wrote " + goods.size() + " trade goods to "
				+ out.getAbsolutePath());
	}

	private static TradeGoodClass categoryOf(String key) {
		TradeGoodClass c = CATEGORY.get(key);
		if (c == null) {
			System.out.println("  WARNING: no category for trade good '" + key
					+ "' — defaulting to LUXURY (add it to TradeGoodExporter.CATEGORY)");
			return TradeGoodClass.LUXURY;
		}
		return c;
	}

	/** The {@code color = { r g b }} of a good body as {@code "#rrggbb"}, or null if absent. */
	private static String goodColor(String goodBody) {
		for (ClausewitzBlocks.Block b : ClausewitzBlocks.parse(goodBody).blocks())
			if (b.name().equals("color"))
				return colorHexFloatOrInt(b.body());
		return null;
	}

	/**
	 * Parse a {@code color} block body to {@code "#rrggbb"}, accepting either float
	 * {@code 0..1} channels (the trade-good source, e.g. {@code 0.96 0.93 0.58}) or
	 * {@code 0..255} int channels. A channel containing {@code '.'} is treated as a
	 * float; otherwise as an int. Returns null if fewer than three parseable channels.
	 */
	private static String colorHexFloatOrInt(String body) {
		String[] t = body.trim().split("\\s+");
		if (t.length < 3)
			return null;
		try {
			int r = channel(t[0]), g = channel(t[1]), b = channel(t[2]);
			return String.format("#%02x%02x%02x", r, g, b);
		} catch (NumberFormatException e) {
			return null;
		}
	}

	// one 0..255 colour channel from either a float (0..1) or int (0..255) token
	private static int channel(String token) {
		int v = token.contains(".")
				? (int) Math.round(Double.parseDouble(token) * 255.0)
				: Integer.parseInt(token);
		return Math.max(0, Math.min(255, v));
	}
}
