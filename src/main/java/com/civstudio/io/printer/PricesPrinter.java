package com.civstudio.io.printer;

import static com.civstudio.io.sink.ColumnSpec.*;

import com.civstudio.io.sink.ColumnSpec;
import com.civstudio.market.ConsumerGoodMarket;
import com.civstudio.settlement.Settlement;

/**
 * Tracks the market price of <b>every consumer-good market in the colony</b>
 * (enjoyment, necessity) in one place. Each print cycle it writes one row per
 * consumer market (in {@link Settlement#getConsumerGoodMarkets()} order), the rows
 * told apart by a <b>Good</b> column (the market's good, e.g. {@code "Necessity"}).
 * <p>
 * One row per market is written on the first day of each in-game month (see {@link
 * Printer#shouldPrint}). Columns: Date, Good, Price.
 */
public class PricesPrinter extends Printer {

	/**
	 * Create a printer writing every consumer market's price, over the whole run.
	 *
	 * @param fileName name of the CSV output file
	 */
	public PricesPrinter(String fileName) {
		super(fileName);
	}

	@Override
	public String tableName() {
		return "prices";
	}

	@Override
	public ColumnSpec[] columns() {
		return new ColumnSpec[] { date("Date"), text("Good"), real("Price") };
	}

	/** Print one row per consumer market, called by {@link Settlement#newDay()}. */
	public void print(Settlement colony) {
		if (!shouldPrint(colony))
			return;
		for (ConsumerGoodMarket mkt : colony.getConsumerGoodMarkets())
			sink.writeRow(colony.getDate(), mkt.getGood(), mkt.getLastMktPrice());
	}
}
