package com.civstudio.io.printer;

import static com.civstudio.io.sink.ColumnSpec.*;

import com.civstudio.agent.Household;
import com.civstudio.bank.CurrencyType;
import com.civstudio.io.sink.ColumnSpec;
import com.civstudio.settlement.Settlement;

/**
 * Writes a roster of the colony's <b>persons of interest</b> — its living nobles
 * and notable households (skill above the threshold) — emitting one row per person
 * <b>once a year</b> (on the first day of each year), a yearly snapshot of who the
 * notable people are and how they are doing. Reads the roster from {@link
 * Settlement#getPersonsOfInterest()} each year (so it tracks successors and new
 * arrivals). Register with {@link Settlement#addPrinter} and finalize with {@link
 * Settlement#cleanUpPrinters}.
 * <p>
 * Columns: Date, Name, Type, Skill, Age, Income, Wealth, Currency. {@code Type} is
 * each household's role; {@code Income} and {@code Wealth} are the person's latest
 * values, displayed in their own bank's {@code Currency} (converted from the
 * internal copper at the colony's fixed exchange rate). Years with no persons of
 * interest contribute no rows.
 */
public class PersonsOfInterestPrinter extends Printer {

	/**
	 * Create a new {@code PersonsOfInterestPrinter}.
	 *
	 * @param fileName name of the CSV output file
	 */
	public PersonsOfInterestPrinter(String fileName) {
		super(fileName);
	}

	@Override
	public String tableName() {
		return "persons_of_interest";
	}

	@Override
	public ColumnSpec[] columns() {
		return new ColumnSpec[] { date("Date"), text("Name"), text("Type"),
				integer("Skill"), integer("Age"), real("Income"), real("Wealth"),
				text("Currency") };
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
			sink.writeRow(colony.getDate(), h.getHead().fullName(), h.role(),
					h.getSkill(), h.getAgeYears(),
					colony.convert(h.getIncome(), CurrencyType.COPPER, currency),
					colony.convert(h.getWealth(), CurrencyType.COPPER, currency),
					currency);
		}
	}
}
