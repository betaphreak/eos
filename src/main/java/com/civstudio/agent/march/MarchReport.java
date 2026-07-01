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
 * @param band               the band's label (its leader's full name)
 * @param province           the band's starting province ("id name")
 * @param day                the computed day's physics and order-of-march schedule
 * @param provincesTraversed the provinces to move through today, as a label
 * @param plotsEstimate      the estimated plots crossed today ({@code D / kmPerPlot})
 * @param camp               the nightly camp, as a label ("-" if the band did not camp)
 */
public record MarchReport(
		LocalDate date,
		String band,
		String province,
		MarchDay day,
		String provincesTraversed,
		int plotsEstimate,
		String camp) {
}
