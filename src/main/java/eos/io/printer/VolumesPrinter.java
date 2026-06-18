package eos.io.printer;

import eos.market.ConsumerGoodMarket;
import eos.settlement.Settlement;

/**
 * Tracks the trading volume and supply of <b>every consumer-good market in the
 * colony</b> (enjoyment, necessity) in a single CSV — the way {@link BanksPrinter}
 * reports all banks in one file. Each print cycle it writes one row per consumer
 * market (in {@link Settlement#getConsumerGoodMarkets()} order), the rows told apart
 * by a <b>Good</b> column (the market's good, e.g. {@code "Necessity"}).
 * <p>
 * One row per market is written on the first day of each in-game month (see {@link
 * Printer#shouldPrint}). Columns: Date, Good, Volume, Supply.
 */
public class VolumesPrinter extends Printer {

	private final CSVPrintWriter printWriter;

	/**
	 * Create a printer writing every consumer market's volume to <tt>fileName</tt>,
	 * over the whole run.
	 *
	 * @param fileName
	 *            name of the CSV output file
	 */
	public VolumesPrinter(String fileName) {
		super(0, Integer.MAX_VALUE);
		this.printWriter = new CSVPrintWriter(fileName);
	}

	/**
	 * Print one row per consumer market, called by {@link Settlement#newDay()} each
	 * step.
	 */
	public void print(Settlement colony) {
		if (!shouldPrint(colony))
			return;
		for (ConsumerGoodMarket mkt : colony.getConsumerGoodMarkets())
			printWriter.println(colony.getDate(), mkt.getGood(),
					mkt.getLastMktGoodVol(), mkt.getLastMktSupply());
	}

	/** Print column titles. */
	public void printTitles() {
		printWriter.println("Date", "Good", "Volume", "Supply");
	}

	/** Clean up the printer. */
	public void cleanup() {
		printWriter.cleanup();
	}

	/**
	 * Return the name of the output file.
	 *
	 * @return the name of the output file
	 */
	public String getFileName() {
		return printWriter.getFileName();
	}
}
