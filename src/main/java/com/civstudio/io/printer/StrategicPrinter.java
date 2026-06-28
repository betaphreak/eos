package com.civstudio.io.printer;

import static com.civstudio.io.sink.ColumnSpec.*;

import com.civstudio.agent.firm.StrategicFirm;
import com.civstudio.bank.Bank;
import com.civstudio.io.sink.ColumnSpec;
import com.civstudio.settlement.Settlement;

/**
 * Writes a time-series of the <b>export-specific</b> detail of the colony's strategic
 * sector: what the single {@link StrategicFirm} shipped out of the economy and the
 * resulting bank {@link Bank#getEquity() equity} the export earnings flow into.
 * Register with {@link Settlement#addPrinter} and finalize with {@link
 * Settlement#cleanUpPrinters}.
 * <p>
 * Columns: Date, Exported, TotalExported, Equity. {@code Exported} is the units shipped
 * out of the economy this step and {@code TotalExported} the cumulative total; {@code
 * Equity} is the bank's cumulative retained equity, which the export earnings build up.
 * The firm's <b>finance</b> (revenue, profit, labor cost, …) is reported alongside every
 * other firm type in the consolidated {@code Firms.csv} ({@code FirmsPrinter}, the
 * {@code Strategic} row), so it is not duplicated here.
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
				real("TotalExported"), real("Equity") };
	}

	@Override
	public void print(Settlement colony) {
		if (!shouldPrint(colony))
			return;

		sink.writeRow(colony.getDate(), firm.getExported(), firm.getTotalExported(),
				bank.getEquity());
	}
}
