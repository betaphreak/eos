package com.civstudio.io.printer;

import static com.civstudio.io.sink.ColumnSpec.*;

import com.civstudio.agent.firm.StrategicFirm;
import com.civstudio.bank.Bank;
import com.civstudio.io.sink.ColumnSpec;
import com.civstudio.settlement.Settlement;

/**
 * Writes a time-series of the colony's strategic export sector: what the single
 * {@link StrategicFirm} exported and earned, the noble labor it employed, and the
 * resulting bank {@link Bank#getEquity() equity} the export earnings flow into.
 * Register with {@link Settlement#addPrinter} and finalize with {@link
 * Settlement#cleanUpPrinters}.
 * <p>
 * Columns: Date, Exported, TotalExported, Revenue, Profit, WageBudget, Labor, Wage,
 * Equity. {@code Exported} is the units shipped out of the economy this step;
 * {@code Labor} is the skill-scaled noble labor employed; {@code Equity} is the
 * bank's cumulative retained equity, which the export earnings build up.
 */
public class StrategicPrinter extends Printer {

	private final StrategicFirm firm;
	private final Bank bank;

	/**
	 * Create a new {@code StrategicPrinter}.
	 *
	 * @param fileName name of the CSV output file
	 * @param firm     the colony's export firm
	 * @param bank     the bank whose equity the firm's export earnings flow into
	 */
	public StrategicPrinter(String fileName, StrategicFirm firm, Bank bank) {
		super(fileName);
		this.firm = firm;
		this.bank = bank;
	}

	@Override
	public String tableName() {
		return "strategic";
	}

	@Override
	public ColumnSpec[] columns() {
		return new ColumnSpec[] { date("Date"), real("Exported"),
				real("TotalExported"), real("Revenue"), real("Profit"),
				real("WageBudget"), real("Labor"), real("Wage"), real("Equity") };
	}

	@Override
	public void print(Settlement colony) {
		if (!shouldPrint(colony))
			return;

		sink.writeRow(colony.getDate(), firm.getExported(), firm.getTotalExported(),
				firm.getRevenue(), firm.getProfit(), firm.getLaborCost(),
				firm.getLabor(), firm.getWage(), bank.getEquity());
	}
}
