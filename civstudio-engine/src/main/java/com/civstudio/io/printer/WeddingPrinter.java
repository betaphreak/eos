package com.civstudio.io.printer;

import static com.civstudio.io.sink.ColumnSpec.*;

import com.civstudio.io.sink.ColumnSpec;
import com.civstudio.market.WeddingMarket;
import com.civstudio.market.WeddingMarket.Wedding;
import com.civstudio.settlement.Settlement;

/**
 * Writes every <b>wedding</b> as it happens — one row per couple, with the
 * statistics of both the household head and the spouse it took out of the peasant
 * pool. Unlike the time-series printers it is <b>event-driven</b>, not monthly: the
 * {@link WeddingMarket} records each step's weddings when it clears (just before
 * printers run), and this writes them all out, so no wedding is missed and quiet
 * steps contribute no rows. Register with {@link Settlement#addPrinter} and finalize
 * with {@link Settlement#cleanUpPrinters}.
 * <p>
 * Columns: Date, Head, HeadType, HeadGender, HeadSkill, HeadAge, Spouse,
 * SpouseGender, SpouseSkill, SpouseAge, Cost (the bride-price in copper).
 */
public class WeddingPrinter extends Printer {

	private final WeddingMarket market;

	/**
	 * Create a new {@code WeddingPrinter}.
	 *
	 * @param fileName name of the CSV output file
	 * @param market   the wedding market whose weddings to report
	 */
	public WeddingPrinter(String fileName, WeddingMarket market) {
		super(fileName);
		this.market = market;
	}

	@Override
	public String tableName() {
		return "weddings";
	}

	@Override
	public ColumnSpec[] columns() {
		return new ColumnSpec[] { date("Date"), text("Head"), text("HeadType"),
				text("HeadGender"), integer("HeadSkill"), integer("HeadAge"),
				text("Spouse"), text("SpouseGender"), integer("SpouseSkill"),
				integer("SpouseAge"), real("Cost") };
	}

	@Override
	public void print(Settlement colony) {
		// event-driven: emit every wedding solemnized this step (the market just
		// recorded them in clear()); no monthly gating
		for (Wedding w : market.getLastWeddings())
			sink.writeRow(w.date(), w.headName(), w.headRole(), w.headGender(),
					w.headSkill(), w.headAge(), w.spouseName(), w.spouseGender(),
					w.spouseSkill(), w.spouseAge(), w.cost());
	}
}
