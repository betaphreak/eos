package com.civstudio.io.printer;

import static com.civstudio.io.sink.ColumnSpec.*;

import com.civstudio.io.sink.ColumnSpec;
import com.civstudio.settlement.BuildEconomy;
import com.civstudio.settlement.Settlement;

/**
 * Writes the <b>build economy's</b> monthly time-series (docs/build-queue-plan.md B1)
 * — the calibration instrument for the occupation choice: how many household-days went
 * to the plot vs the market (and how many market days fell back unhired), the hammers
 * produced/donated and the commerce coin minted that month, plus the cumulative totals.
 * Register with {@link Settlement#addPrinter} on a build-economy colony only.
 * <p>
 * Columns: Date, PlotDays, MarketDays, FallbackDays, Hammers, Commerce, TotalHammers,
 * TotalCommerce.
 */
public class HammerPrinter extends Printer {

	private final BuildEconomy buildEconomy;

	/**
	 * Create a new {@code HammerPrinter}.
	 *
	 * @param fileName     name of the CSV output file
	 * @param buildEconomy the colony's build economy (non-null — register only on a
	 *                     build-economy colony)
	 */
	public HammerPrinter(String fileName, BuildEconomy buildEconomy) {
		super(fileName);
		this.buildEconomy = buildEconomy;
	}

	@Override
	public String tableName() {
		return "hammers";
	}

	@Override
	public ColumnSpec[] columns() {
		return new ColumnSpec[] { date("Date"), integer("PlotDays"), integer("MarketDays"),
				integer("FallbackDays"), real("Hammers"), real("Commerce"),
				real("TotalHammers"), real("TotalCommerce") };
	}

	@Override
	public void print(Settlement colony) {
		if (!shouldPrint(colony))
			return;
		BuildEconomy.Period p = buildEconomy.samplePeriod();
		sink.writeRow(colony.getDate(), p.plotDays(), p.marketDays(), p.fallbackDays(),
				p.hammers(), p.commerce(), buildEconomy.getTotalHammersDonated(),
				buildEconomy.getTotalCommerceMinted());
	}
}
