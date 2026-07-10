package com.civstudio.io.printer;

import static com.civstudio.io.sink.ColumnSpec.*;

import com.civstudio.bank.Bank;
import com.civstudio.bank.CurrencyType;
import com.civstudio.io.sink.ColumnSpec;
import com.civstudio.settlement.Settlement;

/**
 * Tracks <b>every bank in the colony</b> in one place. Each print cycle it writes
 * one row per registered bank (in {@link Settlement#getBanks()} order), so a colony
 * with several banks needs only one printer. The rows are told apart by a <b>Bank</b>
 * column (each bank's name, e.g. {@code "Bank 1"}) and a <b>Currency</b> column —
 * together they identify a bank even when two share a currency.
 * <p>
 * Columns:
 * <ul>
 * <li>Date — the in-game date</li>
 * <li>Bank — the bank's name ({@link Bank#getName()})</li>
 * <li>LoanIR / LTLoanIR / DepositIR / LTDepositIR — the interest rates, as
 * <b>percent values</b> (a fraction of 0.0045 is reported as {@code 0.45})</li>
 * <li>TotalLoan / TotalDeposit / Equity — in the bank's own currency (see
 * Currency)</li>
 * <li>CPI — the colony's consumer price index, in copper</li>
 * <li>Inflation — the colony-wide smoothed inflation, as a percent value</li>
 * <li>Currency — the {@link CurrencyType} the money columns are denominated in</li>
 * </ul>
 * The rate and inflation columns are raw percent-scaled numbers (not the old
 * {@code "0.45%"} strings), so they store cleanly as typed numeric columns.
 */
public class BanksPrinter extends Printer {

	/**
	 * Create a printer writing all of the colony's banks, over the whole run.
	 *
	 * @param fileName name of the CSV output file
	 */
	public BanksPrinter(String fileName) {
		super(fileName, 0, Integer.MAX_VALUE);
	}

	/**
	 * Create a printer writing all of the colony's banks, bounded to the step
	 * range {@code [start, end]}.
	 *
	 * @param fileName name of the CSV output file
	 * @param start    starting step (no data printed before this)
	 * @param end      ending step (no data printed after this)
	 */
	public BanksPrinter(String fileName, int start, int end) {
		super(fileName, start, end);
	}

	@Override
	public String tableName() {
		return "banks";
	}

	@Override
	public ColumnSpec[] columns() {
		return new ColumnSpec[] { date("Date"), text("Bank"), real("LoanIR"),
				real("LTLoanIR"), real("DepositIR"), real("LTDepositIR"),
				real("TotalLoan"), real("TotalDeposit"), real("Equity"), real("CPI"),
				real("Inflation"), text("Currency") };
	}

	/** Print one row per bank, called by {@link Settlement#newDay()} each step. */
	public void print(Settlement colony) {
		if (!shouldPrint(colony))
			return;
		// money columns display in each bank's own currency at the colony's fixed
		// exchange rate (accounting is kept in copper internally); CPI stays in
		// copper (it is an economy-wide price index, not any one bank's money);
		// rates/inflation are reported as percent values (fraction * 100)
		for (Bank bank : colony.getBanks()) {
			CurrencyType c = bank.getCurrency();
			sink.writeRow(colony.getDate(), bank.getName(), pct(bank.getLoanIR()),
					pct(bank.getLTLoanIR()), pct(bank.getDepositIR()),
					pct(bank.getLTDepositIR()),
					colony.convert(bank.getTotalLoan(), CurrencyType.COPPER, c),
					colony.convert(bank.getTotalDeposit(), CurrencyType.COPPER, c),
					colony.convert(bank.getEquity(), CurrencyType.COPPER, c),
					colony.getCPI(), pct(colony.getInflation()), c);
		}
	}

	/** A rate fraction (e.g. 0.0045) as a percent value (0.45). */
	private static double pct(double fraction) {
		return fraction * 100;
	}
}
