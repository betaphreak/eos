package com.civstudio.io.printer;

import static com.civstudio.io.sink.ColumnSpec.date;
import static com.civstudio.io.sink.ColumnSpec.integer;
import static com.civstudio.io.sink.ColumnSpec.text;

import java.time.LocalDate;

import com.civstudio.io.sink.ColumnSpec;
import com.civstudio.settlement.Plot;
import com.civstudio.settlement.ProvincePlotPool;
import com.civstudio.settlement.Settlement;

/**
 * A <b>one-time</b> dump of a province's whole plot field — every plot of the
 * shared {@link ProvincePlotPool}, one row each — for visualizing the province's
 * generated terrain, relief, features, resources and how its settlements divide
 * it. Unlike the periodic {@link ProvinceInventoryPrinter}, this writes once (on
 * its first print) and then nothing: the field's terrain/feature/bonus is fixed at
 * generation, so a single snapshot captures the full inventory, with the
 * {@code Owner} column showing which settlement (if any) holds each plot at that
 * moment. A {@linkplain Settlement#getProvince() province-less} colony has no pool,
 * so this prints nothing for one. See {@code docs/province-plots.md}.
 * <p>
 * The field is the whole province's — a <b>barony-level</b> view, not any one
 * settlement's — so it is registered <b>once per province</b> (by the first
 * settlement founded into it, via {@link
 * com.civstudio.settlement.GameSession#firstPlotMapFor}); settlements sharing the
 * province do not each dump it. When a province gains a governing Baron (see {@code
 * docs/rank-ladder.md}), that barony is the natural owner of this printer.
 * <p>
 * Columns: Date, X, Y, Terrain, Relief, Feature, Bonus, Owner. {@code Feature} and
 * {@code Bonus} are {@code -} when absent; {@code Owner} is {@code free} for an
 * unclaimed plot.
 */
public class PlotMapPrinter extends Printer {

	private boolean dumped;

	/**
	 * Create a printer that dumps the province's plot field once.
	 *
	 * @param fileName name of the CSV output file
	 */
	public PlotMapPrinter(String fileName) {
		super(fileName);
	}

	@Override
	public String tableName() {
		return "plotmap";
	}

	@Override
	public ColumnSpec[] columns() {
		return new ColumnSpec[] { date("Date"), integer("X"), integer("Y"), text("Terrain"),
				text("Relief"), text("Feature"), text("Bonus"), text("Owner") };
	}

	/** Dump every plot of the province pool once, on the first call. */
	public void print(Settlement colony) {
		if (dumped || colony.getProvince() == null)
			return;
		ProvincePlotPool pool = colony.getSession().provincePlotPool(colony.getProvince());
		LocalDate date = colony.getDate();
		for (Plot p : pool.plots())
			sink.writeRow(date, p.x(), p.y(), p.terrain().type(), p.plotType().name(),
					p.feature() == null ? "-" : p.feature().type(),
					p.bonus() == null ? "-" : p.bonus().type(),
					p.owner() == null ? "free" : p.owner().getName());
		dumped = true;
	}
}
