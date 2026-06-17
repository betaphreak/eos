package eos.io.printer;

import eos.bank.Bank;
import eos.bank.CurrencyType;
import eos.settlement.Settlement;

/**
 * Tracks <b>every bank in the colony</b> in a single CSV. Each print cycle it
 * writes one row per registered bank (in {@link Settlement#getBanks()} order),
 * so a colony with several banks needs only one printer and one file rather than
 * one per bank. The rows are told apart by a <b>Bank</b> column (each bank's name,
 * e.g. {@code "Bank 1"}) and a <b>Currency</b> column — together they identify a
 * bank even when two share a currency (e.g. a colony with two copper banks).
 * <p>
 * Columns:
 * <ul>
 * <li>Date — the in-game date</li>
 * <li>Bank — the bank's name ({@link Bank#getName()})</li>
 * <li>LoanIR / LTLoanIR / DepositIR / LTDepositIR — the interest rates, as
 * percents truncated to two decimals</li>
 * <li>TotalLoan / TotalDeposit / Equity — in the bank's own currency (see
 * Currency)</li>
 * <li>CPI — the colony's consumer price index, in copper</li>
 * <li>Inflation — the colony-wide smoothed inflation, as a truncated percent</li>
 * <li>Currency — the {@link CurrencyType} the money columns are denominated in</li>
 * </ul>
 */
public class BanksPrinter extends Printer {

	private final CSVPrintWriter printWriter;

	/**
	 * Create a printer writing all of the colony's banks to <tt>fileName</tt>,
	 * over the whole run.
	 *
	 * @param fileName
	 *            name of the CSV output file
	 */
	public BanksPrinter(String fileName) {
		this(fileName, 0, Integer.MAX_VALUE);
	}

	/**
	 * Create a printer writing all of the colony's banks to <tt>fileName</tt>,
	 * bounded to the step range {@code [start, end]}.
	 *
	 * @param fileName
	 *            name of the CSV output file
	 * @param start
	 *            starting step (no data printed before this)
	 * @param end
	 *            ending step (no data printed after this)
	 */
	public BanksPrinter(String fileName, int start, int end) {
		super(start, end);
		this.printWriter = new CSVPrintWriter(fileName);
	}

	/**
	 * Print one row per bank, called by {@link Settlement#newDay()} each step.
	 */
	public void print(Settlement colony) {
		if (!shouldPrint(colony))
			return;
		// money columns display in each bank's own currency at the colony's fixed
		// exchange rate (accounting is kept in copper internally); CPI stays in copper
		// (it is an economy-wide price index, not any one bank's money)
		for (Bank bank : colony.getBanks()) {
			CurrencyType c = bank.getCurrency();
			printWriter.println(colony.getDate(), bank.getName(),
					formatPercent(bank.getLoanIR()), formatPercent(bank.getLTLoanIR()),
					formatPercent(bank.getDepositIR()),
					formatPercent(bank.getLTDepositIR()),
					colony.convert(bank.getTotalLoan(), CurrencyType.COPPER, c),
					colony.convert(bank.getTotalDeposit(), CurrencyType.COPPER, c),
					colony.convert(bank.getEquity(), CurrencyType.COPPER, c),
					colony.getCPI(), formatPercent(colony.getInflation()), c);
		}
	}

	/**
	 * Format a fraction as a percent string truncated (toward zero) to two
	 * decimal places, e.g. 0.0045 -&gt; "0.45%".
	 */
	private static String formatPercent(double fraction) {
		double pct = fraction * 100;
		double truncated = (long) (pct * 100) / 100.0;
		return String.format("%.2f%%", truncated);
	}

	/** Print column titles. */
	public void printTitles() {
		printWriter.println("Date", "Bank", "LoanIR", "LTLoanIR", "DepositIR",
				"LTDepositIR", "TotalLoan", "TotalDeposit", "Equity", "CPI",
				"Inflation", "Currency");
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
