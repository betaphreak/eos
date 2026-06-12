package eos.io.printer;

import eos.agent.Household;
import eos.agent.laborer.Laborer;
import eos.agent.noble.Noble;
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
 * Columns: Date, Name, Type, Skill, Age, Income, Wealth. {@code Type} is
 * "Noble" or "Notable laborer"; {@code Income} and {@code Wealth} are the
 * person's latest values. Years with no persons of interest contribute no rows.
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
			String type;
			double income, wealth;
			if (h instanceof Noble noble) {
				type = "Noble";
				income = noble.getIncome();
				wealth = noble.getWealth();
			} else {
				Laborer laborer = (Laborer) h;
				type = "Notable laborer";
				income = laborer.getIncome();
				wealth = laborer.getWealth();
			}
			printWriter.println(colony.getDate(), h.getHead().fullName(), type,
					h.getSkill(), h.getAgeYears(), income, wealth);
		}
	}

	@Override
	public void printTitles() {
		printWriter.println("Date", "Name", "Type", "Skill", "Age", "Income",
				"Wealth");
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
