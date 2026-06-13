package eos.io.printer;

import eos.agent.Household;
import eos.bank.CurrencyType;
import eos.settlement.Settlement;

/**
 * Writes a CSV roster of the colony's <b>persons of interest</b> — its living
 * nobles and notable households (skill above the threshold) — emitting one row
 * per person <b>once a year</b> (on the first day of each year), so the file is a
 * yearly snapshot of who the notable people are and how they are doing. Reads the
 * roster from {@link Settlement#getPersonsOfInterest()} each year (so it tracks
 * successors and new arrivals, not a fixed initial set). Register with {@link
 * Settlement#addPrinter} and finalize with {@link Settlement#cleanUpPrinters}.
 * <p>
 * Columns: Date, Name, Type, Skill, Age, Income, Wealth, Currency. {@code Type}
 * is "Noble" or "Notable laborer"; {@code Income} and {@code Wealth} are the
 * person's latest values, displayed in their own bank's {@code Currency}
 * (converted from the internal copper at the colony's fixed exchange rate). Years
 * with no persons of interest contribute no rows.
 */
public class PersonsOfInterestPrinter extends Printer {

	private final CSVPrintWriter printWriter;

	/**
	 * Create a new {@code PersonsOfInterestPrinter}.
	 *
	 * @param fileName
	 *            name of the CSV output file
	 */
	public PersonsOfInterestPrinter(String fileName) {
		super();
		this.printWriter = new CSVPrintWriter(fileName);
	}

	@Override
	public void print(Settlement colony) {
		// once a year: the base cadence is monthly (day-of-month 1), so also gate
		// on January to emit a single yearly block of rows
		if (!shouldPrint(colony) || colony.getDate().getMonthValue() != 1)
			return;

		for (Household h : colony.getPersonsOfInterest()) {
			// every household reports its own role, income, wealth and currency, so
			// a new population type needs no case added here
			CurrencyType currency = h.getBank().getCurrency();
			// income/wealth are copper internally; display each person's in their
			// own bank's currency at the colony's fixed exchange rate
			printWriter.println(colony.getDate(), h.getHead().fullName(), h.role(),
					h.getSkill(), h.getAgeYears(),
					colony.convert(h.getIncome(), CurrencyType.COPPER, currency),
					colony.convert(h.getWealth(), CurrencyType.COPPER, currency),
					currency);
		}
	}

	@Override
	public void printTitles() {
		printWriter.println("Date", "Name", "Type", "Skill", "Age", "Income",
				"Wealth", "Currency");
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
