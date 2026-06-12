package eos.io.printer;

import eos.agent.Agent;
import eos.agent.noble.Noble;
import eos.bank.CurrencyType;
import eos.settlement.Settlement;

/**
 * Writes a CSV time-series of the noble population: how many nobles are alive
 * and their average dividends, income, consumption and wealth. Aggregates over
 * the living nobles read from {@link Settlement#getAgents()} each step (mirroring
 * {@link LaborersPrinter}). Register with {@link Settlement#addPrinter} and finalize
 * with {@link Settlement#cleanUpPrinters}.
 * <p>
 * Columns: Date, Count, AvgDividends, AvgIncome, AvgConsumption, AvgWealth,
 * TotalWealth, AvgAge, AvgNStock, Currency. The four monetary columns are
 * denominated in the nobles' own currency (Currency), converted from the internal
 * copper at the colony's fixed exchange rate; {@code AvgNStock} is the average
 * necessity reserve (in units, not money) the nobles hold.
 */
public class NoblesPrinter extends Printer {

	private final CSVPrintWriter printWriter;

	/**
	 * Create a new {@code NoblesPrinter}.
	 *
	 * @param fileName
	 *            name of the CSV output file
	 */
	public NoblesPrinter(String fileName) {
		super();
		this.printWriter = new CSVPrintWriter(fileName);
	}

	@Override
	public void print(Settlement colony) {
		if (!shouldPrint(colony))
			return;

		// monetary fields are kept in copper internally; display them in the
		// nobles' own currency at the colony's fixed exchange rate
		double totDividends = 0, totIncome = 0, totConsumption = 0, totWealth = 0;
		double totAge = 0, totNStock = 0;
		int count = 0;
		CurrencyType currency = CurrencyType.COPPER;
		for (Agent agent : colony.getAgents())
			if (agent instanceof Noble noble) {
				CurrencyType c = noble.getBank().getCurrency();
				currency = c;
				totDividends += colony.convert(noble.getDividends(),
						CurrencyType.COPPER, c);
				totIncome += colony.convert(noble.getIncome(),
						CurrencyType.COPPER, c);
				totConsumption += colony.convert(noble.getConsumption(),
						CurrencyType.COPPER, c);
				totWealth += colony.convert(noble.getWealth(),
						CurrencyType.COPPER, c);
				totAge += noble.getAgeYears();
				totNStock += noble.getNecessityStock();
				count++;
			}

		double inv = count > 0 ? 1.0 / count : 0;
		printWriter.println(colony.getDate(), count, totDividends * inv,
				totIncome * inv, totConsumption * inv, totWealth * inv, totWealth,
				totAge * inv, totNStock * inv, currency);
	}

	@Override
	public void printTitles() {
		printWriter.println("Date", "Count", "AvgDividends", "AvgIncome",
				"AvgConsumption", "AvgWealth", "TotalWealth", "AvgAge", "AvgNStock",
				"Currency");
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
