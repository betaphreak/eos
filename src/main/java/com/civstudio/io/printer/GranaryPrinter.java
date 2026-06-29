package com.civstudio.io.printer;

import static com.civstudio.io.sink.ColumnSpec.*;

import com.civstudio.agent.Granary;
import com.civstudio.io.sink.ColumnSpec;
import com.civstudio.settlement.Settlement;

/**
 * Writes a time-series of the {@link Granary}: the strategic food reserve it holds and
 * the target it aims for, the necessity it bought and sold last cycle, the cumulative
 * traded totals, its cash, and the cumulative cost the ruler covered. Register with
 * {@link Settlement#addPrinter} and finalize with {@link Settlement#cleanUpPrinters}.
 * <p>
 * Columns: Date, Stock, Target, Bought, Sold, TotalBought, TotalSold, Cash, BilledTotal.
 */
public class GranaryPrinter extends Printer {

	private final Granary granary;

	/**
	 * Create a new {@code GranaryPrinter}.
	 *
	 * @param fileName name of the CSV output file
	 * @param granary  the granary to report on
	 */
	public GranaryPrinter(String fileName, Granary granary) {
		super(fileName);
		this.granary = granary;
	}

	@Override
	public String tableName() {
		return "granary";
	}

	@Override
	public ColumnSpec[] columns() {
		return new ColumnSpec[] { date("Date"), real("Stock"), real("Target"),
				real("Bought"), real("Sold"), real("TotalBought"), real("TotalSold"),
				real("Cash"), real("BilledTotal") };
	}

	@Override
	public void print(Settlement colony) {
		if (!shouldPrint(colony))
			return;
		sink.writeRow(colony.getDate(), granary.getStock(), granary.getTargetStock(),
				granary.getLastBought(), granary.getLastSold(), granary.getTotalBought(),
				granary.getTotalSold(), granary.getCash(),
				granary.getTotalBilledToRuler());
	}
}
