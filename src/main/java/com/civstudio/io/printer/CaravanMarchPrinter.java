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
 * Each band gets its <b>own</b> pair of files under a <b>{@code by-caravan/}</b> subfolder
 * of the session's {@code output/<seed>/} directory, so the journals of several caravans
 * are read separately (mirroring the colonies' {@code by-settlement/} grouping):
 * <ul>
 * <li><b>{@code by-caravan/<band>-CaravanMarch.csv}</b> — one row per day: the daylight
 * budget, speed, column length and net march distance, plus the <b>provinces to be
 * traversed</b> that day and the <b>nightly camp</b> plot.</li>
 * <li><b>{@code by-caravan/<band>-CaravanTimetable.csv}</b> — the day's <b>HH:mm
 * order-of-march</b>: one row per fielded {@link com.civstudio.agent.march.MarchElement}
 * with its depart / clears-camp / head-arrives times (see {@code docs/caravan-march.md}
 * §5).</li>
 * </ul>
 * Files are opened lazily on a band's first recorded day and appended as it ticks
 * (single-threaded, from the session day-barrier); {@link #close()} flushes them all.
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
			ColumnSpec.text("Camp"),
	};

	private static final ColumnSpec[] TIMETABLE_COLUMNS = {
			ColumnSpec.date("Date"),
			ColumnSpec.text("Element"),
			ColumnSpec.integer("Size"),
			ColumnSpec.text("Depart"),
			ColumnSpec.text("ClearsCamp"),
			ColumnSpec.text("HeadArrives"),
	};

	private final String bandDir; // output/<seed>/by-caravan
	// one file pair per band, opened on its first recorded day (keyed by band label)
	private final Map<String, CsvRowSink> marchByBand = new LinkedHashMap<>();
	private final Map<String, CsvRowSink> timetableByBand = new LinkedHashMap<>();

	/**
	 * Open the journal under {@code <baseDir>/by-caravan/}.
	 *
	 * @param baseDir the session output directory (e.g. {@code "output/7654321"})
	 */
	public CaravanMarchPrinter(String baseDir) {
		this.bandDir = baseDir + "/by-caravan";
	}

	/**
	 * Record one band's march for one day into that band's own files: the summary row and
	 * the order-of-march timetable rows. The band composes the {@link MarchReport} (labels
	 * resolved against the province graph) so the writer stays independent of it.
	 *
	 * @param r the day's print-ready march report
	 */
	public void record(MarchReport r) {
		String band = r.band();
		MarchDay day = r.day();
		marchSink(band).writeRow(r.date(), r.province(), day.bandSize(), day.daylightHours(),
				day.speedKmh(), day.columnKm(), day.netMarchKm(), hm(day.firstDepart()),
				hm(day.campMade()), r.provincesTraversed(), r.bonuses(),
				r.plotsEstimate(), r.camp());
		CsvRowSink tt = timetableSink(band);
		for (MarchDay.Stage s : day.stages())
			tt.writeRow(r.date(), s.element().name(), s.size(),
					hm(s.depart()), hm(s.clearsCamp()), hm(s.headArrives()));
	}

	/** Flush and close every band's files. */
	public void close() {
		marchByBand.values().forEach(CsvRowSink::close);
		timetableByBand.values().forEach(CsvRowSink::close);
	}

	private CsvRowSink marchSink(String band) {
		return marchByBand.computeIfAbsent(band, b -> new CsvRowSink(
				bandDir + "/" + fileSafe(b) + "-CaravanMarch.csv", MARCH_COLUMNS));
	}

	private CsvRowSink timetableSink(String band) {
		return timetableByBand.computeIfAbsent(band, b -> new CsvRowSink(
				bandDir + "/" + fileSafe(b) + "-CaravanTimetable.csv", TIMETABLE_COLUMNS));
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
