package com.civstudio.io.printer;

import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.Map;

import com.civstudio.agent.march.MarchDay;
import com.civstudio.agent.march.MarchReport;
import com.civstudio.io.sink.ColumnSpec;
import com.civstudio.io.sink.CsvRowSink;

/**
 * Writes the daily march journal of the realm's {@link com.civstudio.agent.Caravan
 * wandering bands} — a self-contained CSV writer rather than a {@link Printer}, because a
 * band belongs to no {@link com.civstudio.settlement.Settlement} (it is session-level) and
 * so has no colony to register a printer with.
 * <p>
 * Each band gets its <b>own</b> file under a <b>{@code by-caravan/}</b> subfolder of the
 * session's {@code output/<seed>/} directory, named by the band's <b>journey</b> —
 * {@code by-caravan/<Origin>-<Destination>-CaravanMarch.csv} for a directed band
 * ({@code <Origin>-<Leader>} for a wanderer) — so the journals of several caravans are
 * read separately (mirroring the colonies' {@code by-settlement/} grouping). One row per marched day: the daylight budget, speed,
 * column length and net march distance; the provinces traversed and the notable resource
 * bonuses encountered on the day's plot corridor; the food the band <b>ate</b> and the
 * <b>larder</b> remaining (its countdown to starvation while it cannot restock); the
 * non-food goods it <b>gathered</b> and the <b>cargo</b> manifest it carries; and the
 * nightly camp plot. Files are opened lazily on a band's first recorded day and appended as
 * it ticks (single-threaded, from the session day-barrier); {@link #close()} flushes them.
 */
public final class CaravanMarchPrinter {

	private static final ColumnSpec[] MARCH_COLUMNS = {
			ColumnSpec.date("Date"),
			ColumnSpec.text("Province"),
			ColumnSpec.integer("BandSize"),
			ColumnSpec.real("DaylightH"),
			ColumnSpec.real("SpeedKmh"),
			ColumnSpec.real("ColumnKm"),
			ColumnSpec.real("NetMarchKm"),
			ColumnSpec.text("FirstDepart"),
			ColumnSpec.text("CampMade"),
			ColumnSpec.text("ProvincesTraversed"),
			ColumnSpec.text("Bonuses"),
			ColumnSpec.integer("PlotsEst"),
			ColumnSpec.real("Ate"),
			ColumnSpec.real("Foraged"),
			ColumnSpec.real("Larder"),
			ColumnSpec.integer("Gathered"),
			ColumnSpec.integer("Cargo"),
			ColumnSpec.text("Carrying"),
			ColumnSpec.text("Camp"),
	};

	private final String bandDir; // output/<seed>/by-caravan
	// one file per band, opened on its first recorded day (keyed by band label)
	private final Map<String, CsvRowSink> marchByBand = new LinkedHashMap<>();

	/**
	 * Open the journal under {@code <baseDir>/by-caravan/}.
	 *
	 * @param baseDir the session output directory (e.g. {@code "output/7654321"})
	 */
	public CaravanMarchPrinter(String baseDir) {
		this.bandDir = baseDir + "/by-caravan";
	}

	/**
	 * Record one band's march for one day into that band's own file. The band composes the
	 * {@link MarchReport} (labels resolved against the province graph) so the writer stays
	 * independent of it.
	 *
	 * @param r the day's print-ready march report
	 */
	public void record(MarchReport r) {
		MarchDay day = r.day();
		marchSink(r.journey()).writeRow(r.date(), r.province(), day.bandSize(),
				day.daylightHours(), day.speedKmh(), day.columnKm(), day.netMarchKm(),
				hm(day.firstDepart()), hm(day.campMade()), r.provincesTraversed(),
				r.bonuses(), r.plotsEstimate(), r.ate(), r.foraged(), r.larder(),
				r.gathered(), r.cargo(), r.carrying(), r.camp());
	}

	/** Flush and close every band's file. */
	public void close() {
		marchByBand.values().forEach(CsvRowSink::close);
	}

	private CsvRowSink marchSink(String band) {
		return marchByBand.computeIfAbsent(band, b -> new CsvRowSink(
				bandDir + "/" + fileSafe(b) + "-CaravanMarch.csv", MARCH_COLUMNS));
	}

	// HH:mm, or "-" when the time is undefined (the band did not march)
	private static String hm(LocalTime t) {
		return t == null ? "-" : t.toString();
	}

	// a band's label as a safe file-name stem (drop characters illegal in a path, and
	// trim stray whitespace — e.g. a surname-less leader's "Denis " label)
	private static String fileSafe(String band) {
		String s = band.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
		return s.isEmpty() ? "band" : s;
	}
}
