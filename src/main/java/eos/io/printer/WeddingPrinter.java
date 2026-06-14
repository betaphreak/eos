package eos.io.printer;

import eos.market.WeddingMarket;
import eos.market.WeddingMarket.Wedding;
import eos.settlement.Settlement;

/**
 * Writes a CSV of every <b>wedding</b> as it happens — one row per couple, with
 * the statistics of both the household head and the spouse it took out of the
 * peasant pool. Unlike the time-series printers it is <b>event-driven</b>, not
 * monthly: the {@link WeddingMarket} records each step's weddings when it clears
 * (just before printers run), and this writes them all out, so no wedding is
 * missed and quiet steps contribute no rows. Register with {@link
 * Settlement#addPrinter} and finalize with {@link Settlement#cleanUpPrinters}.
 * <p>
 * Columns: Date, Head, HeadType, HeadGender, HeadSkill, HeadAge, Spouse,
 * SpouseGender, SpouseSkill, SpouseAge, Cost (the bride-price in copper).
 */
public class WeddingPrinter extends Printer {

	private final CSVPrintWriter printWriter;
	private final WeddingMarket market;

	/**
	 * Create a new {@code WeddingPrinter}.
	 *
	 * @param fileName
	 *            name of the CSV output file
	 * @param market
	 *            the wedding market whose weddings to report
	 */
	public WeddingPrinter(String fileName, WeddingMarket market) {
		super();
		this.printWriter = new CSVPrintWriter(fileName);
		this.market = market;
	}

	@Override
	public void print(Settlement colony) {
		// event-driven: emit every wedding solemnized this step (the market just
		// recorded them in clear()); no monthly gating
		for (Wedding w : market.getLastWeddings())
			printWriter.println(w.date(), w.headName(), w.headRole(),
					w.headGender(), w.headSkill(), w.headAge(), w.spouseName(),
					w.spouseGender(), w.spouseSkill(), w.spouseAge(), w.cost());
	}

	@Override
	public void printTitles() {
		printWriter.println("Date", "Head", "HeadType", "HeadGender", "HeadSkill",
				"HeadAge", "Spouse", "SpouseGender", "SpouseSkill", "SpouseAge",
				"Cost");
	}

	@Override
	public void cleanup() {
		printWriter.cleanup();
	}

	@Override
	public String getFileName() {
		return printWriter.getFileName();
	}
}
