package com.civstudio.io.printer;

import java.time.LocalTime;

import com.civstudio.agent.march.MarchDay;
import com.civstudio.agent.march.MarchReport;
import com.civstudio.io.sink.ColumnSpec;
import com.civstudio.io.sink.CsvRowSink;

/**
 * Writes the daily march journal of the realm's {@link com.civstudio.agent.Caravan
 * wandering bands} — a self-contained CSV writer rather than a {@link Printer}, because a
 * band belongs to no {@link com.civstudio.settlement.Settlement} (it is session-level) and
 * so has no colony to register a printer with. It emits two tables, both scoped to the
 * session's {@code output/<seed>/} folder and told apart by a {@code Band} column (the
 * "one file, many bands" convention the other consolidated printers use):
 * <ul>
 * <li><b>{@code CaravanMarch.csv}</b> — one row per band per day: the daylight budget,
 * speed, column length and net march distance, plus the <b>provinces to be traversed</b>
 * that day and the <b>nightly camp</b> plot.</li>
 * <li><b>{@code CaravanTimetable.csv}</b> — the day's <b>HH:mm order-of-march</b>: one row
 * per fielded {@link com.civstudio.agent.march.MarchElement} with its depart / clears-camp
 * / head-arrives times (see {@code docs/caravan-march.md} §5).</li>
 * </ul>
 * Both files are appended as bands tick (single-threaded, from the session day-barrier),
 * and {@link #close()} flushes them at the end of the run.
 */
public final class CaravanMarchPrinter {

	private static final ColumnSpec[] MARCH_COLUMNS = {
			ColumnSpec.date("Date"),
			ColumnSpec.text("Band"),
			ColumnSpec.text("Province"),
			ColumnSpec.integer("BandSize"),
			ColumnSpec.real("DaylightH"),
			ColumnSpec.real("SpeedKmh"),
			ColumnSpec.real("ColumnKm"),
			ColumnSpec.real("NetMarchKm"),
			ColumnSpec.text("FirstDepart"),
			ColumnSpec.text("CampMade"),
			ColumnSpec.text("ProvincesTraversed"),
			ColumnSpec.integer("PlotsEst"),
			ColumnSpec.text("Camp"),
	};

	private static final ColumnSpec[] TIMETABLE_COLUMNS = {
			ColumnSpec.date("Date"),
			ColumnSpec.text("Band"),
			ColumnSpec.text("Element"),
			ColumnSpec.integer("Size"),
			ColumnSpec.text("Depart"),
			ColumnSpec.text("ClearsCamp"),
			ColumnSpec.text("HeadArrives"),
	};

	private final CsvRowSink march;
	private final CsvRowSink timetable;

	/**
	 * Open the journal under the session's output directory.
	 *
	 * @param baseDir the session output directory (e.g. {@code "output/7654321"})
	 */
	public CaravanMarchPrinter(String baseDir) {
		this.march = new CsvRowSink(baseDir + "/CaravanMarch.csv", MARCH_COLUMNS);
		this.timetable = new CsvRowSink(baseDir + "/CaravanTimetable.csv", TIMETABLE_COLUMNS);
	}

	/**
	 * Record one band's march for one day: the summary row and the order-of-march
	 * timetable rows. The band composes the {@link MarchReport} (labels resolved against
	 * the province graph) so the writer stays independent of it.
	 *
	 * @param r the day's print-ready march report
	 */
	public void record(MarchReport r) {
		MarchDay day = r.day();
		march.writeRow(r.date(), r.band(), r.province(), day.bandSize(), day.daylightHours(),
				day.speedKmh(), day.columnKm(), day.netMarchKm(), hm(day.firstDepart()),
				hm(day.campMade()), r.provincesTraversed(), r.plotsEstimate(), r.camp());
		for (MarchDay.Stage s : day.stages())
			timetable.writeRow(r.date(), r.band(), s.element().name(), s.size(),
					hm(s.depart()), hm(s.clearsCamp()), hm(s.headArrives()));
	}

	/** Flush and close both files. */
	public void close() {
		march.close();
		timetable.close();
	}

	// HH:mm, or "-" when the time is undefined (the band did not march)
	private static String hm(LocalTime t) {
		return t == null ? "-" : t.toString();
	}
}
