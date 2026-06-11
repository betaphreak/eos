package eos.io.printer;

import eos.agent.Agent;
import eos.agent.noble.Noble;
import eos.economy.Economy;

/**
 * Writes a CSV time-series of the noble population: how many nobles are alive
 * and their average dividends, income, consumption and wealth. Aggregates over
 * the living nobles read from {@link Economy#getAgents()} each step (mirroring
 * {@link LaborersPrinter}). Register with {@link Economy#addPrinter} and finalize
 * with {@link Economy#cleanUpPrinters}.
 * <p>
 * Columns: Date, Count, AvgDividends, AvgIncome, AvgConsumption, AvgWealth,
 * TotalWealth, AvgAge.
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
	public void print(Economy economy) {
		if (!shouldPrint(economy))
			return;

		double totDividends = 0, totIncome = 0, totConsumption = 0, totWealth = 0;
		double totAge = 0;
		int count = 0;
		for (Agent agent : economy.getAgents())
			if (agent instanceof Noble noble) {
				totDividends += noble.getDividends();
				totIncome += noble.getIncome();
				totConsumption += noble.getConsumption();
				totWealth += noble.getWealth();
				totAge += noble.getAgeYears();
				count++;
			}

		double inv = count > 0 ? 1.0 / count : 0;
		printWriter.println(economy.getDate(), count, totDividends * inv,
				totIncome * inv, totConsumption * inv, totWealth * inv, totWealth,
				totAge * inv);
	}

	@Override
	public void printTitles() {
		printWriter.println("Date", "Count", "AvgDividends", "AvgIncome",
				"AvgConsumption", "AvgWealth", "TotalWealth", "AvgAge");
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
