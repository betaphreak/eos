package com.civstudio.agent.march;

import java.time.LocalDate;

/**
 * A print-ready snapshot of one band's march on one day — the band composes it (it has
 * the province graph to resolve names), and {@link
 * com.civstudio.io.printer.CaravanMarchPrinter} writes it, so the writer stays
 * independent of the graph. It bundles the day's {@link MarchDay physics + schedule} with
 * the human-readable labels the report shows: where the band starts, the provinces it
 * will traverse today, and where it camps tonight.
 *
 * @param date               the in-game date
 * @param journey            the band's journey label, which names its journal file —
 *                           "&lt;Origin&gt;-&lt;Destination&gt;" (province names) for a
 *                           directed band, "&lt;Origin&gt;-&lt;Leader&gt;" for a wanderer
 * @param province           the band's starting province ("id name")
 * @param day                the computed day's physics and order-of-march schedule
 * @param provincesTraversed the provinces to move through today, as a label
 * @param bonuses            the notable {@link com.civstudio.geo.Bonus resource bonuses}
 *                           encountered on the current province's corridor, as a label
 *                           ("-" when none, or before Level-2 corridors are computed)
 * @param plotsEstimate      the plots crossed today (the corridor plot count when a
 *                           corridor is known, else {@code D / kmPerPlot})
 * @param ate                the necessity the band consumed today (its wandering ration
 *                           × head-count) — eaten every day, halts and fords included
 * @param foraged            the food gathered from the land today — non-zero only when the
 *                           day left surplus daylight and the corridor crossed a food
 *                           resource; added to the larder (offsets, but is capped below,
 *                           the ration, so the band still declines)
 * @param larder             the necessity remaining in the carried larder after eating and
 *                           foraging — the countdown to starvation while the band cannot
 *                           restock on a market
 * @param gathered           the whole units of non-food goods (ore, gems, luxuries…)
 *                           gathered into the band's cargo today — non-zero only when surplus
 *                           daylight remained after food foraging and the corridor crossed
 *                           such a resource (cargo goods are discrete; part-unit work accrues
 *                           as progress on the band and deposits only full units)
 * @param cargo              the total whole units carried after gathering (capped by the
 *                           band's carrying capacity, head-count × capacity per head)
 * @param carrying           the cargo manifest, as a label — the most-carried goods in
 *                           descending quantity ("-" when the cargo is empty)
 * @param camp               the nightly camp, as a label ("-" if the band did not camp)
 */
public record MarchReport(
		LocalDate date,
		String journey,
		String province,
		MarchDay day,
		String provincesTraversed,
		String bonuses,
		int plotsEstimate,
		double ate,
		double foraged,
		double larder,
		int gathered,
		int cargo,
		String carrying,
		String camp) {
}
