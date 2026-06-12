package eos.io.printer;

import eos.bank.Bank;
import eos.settlement.*;

/**
 * This printer tracks the loan, deposit and interest rates of the bank. To use
 * it:
 * <p>
 * 1. Create a new <tt>BankPrinter</tt>. See constructor
 * {@link #BankPrinter(String fileName, int start, int end)}.
 * <p>
 * 2. Call <tt>printTitles()</tt> to print column titles.
 * <p>
 * 3. Add the printer to the Settlement by calling <tt>Settlement.addPrinter()</tt>.
 * <p>
 * 4. Call <tt>print()</tt> of this printer in <tt>Settlement.newDay()</tt> to print
 * data.
 * <p>
 * 5. Include <tt>cleanup()</tt> of this printer in
 * <tt>Settlement.cleanUpPrinters()</tt>, and call that method to clean up the
 * printers.
 * <p>
 * The output of the printer is a CSV file. If you have closely followed the
 * above steps, the first line of the file should be the column titles, and the
 * first column is the in-game date. All entries are comma-delimited (without
 * space). The file could be directly used as an input file for <tt>Grapher</tt>
 * and <tt>MultiAxisGrapher</tt>. You could also open the file with most
 * spreadsheet softwares like Microsoft Excel and OpenOffice Spreadsheet, and
 * perform any data processing you wish.
 * <p>
 * If you omit the file name or provide a simple file name when calling the
 * constructor, the output file will be saved in a folder called "output". If on
 * the other hand, you specify a directory in the file name, the output file
 * will be saved in your specified directory.
 * <p>
 * The default columns to be printed are: <br>
 * Col0: in-game date <br>
 * Col1: loan interest rate, as a percent truncated to two decimals <br>
 * Col2: smoothed loan interest rate, as a percent truncated to two decimals <br>
 * Col3: deposit interest rate, as a percent truncated to two decimals <br>
 * Col4: smoothed deposit interest rate, as a percent truncated to two decimals <br>
 * Col5: total loan <br>
 * Col6: total deposit<br>
 * Col7: equity (cumulative retained profit)<br>
 * Col8: CPI (consumer price index — mean of consumer-good market prices), to
 * two decimals<br>
 * Col9: inflation (colony-wide smoothed average inflation, formatted as a
 * percent truncated to two decimals; the real rate is the deposit/loan rate
 * less this)<br>
 *
 */
public class BankPrinter extends Printer {

	// print writer that writes output to a CSV file
	private final CSVPrintWriter printWriter;

	// the bank whose rates and totals are tracked
	private final Bank bank;

	/**
	 * Create a new <tt>BankPrinter</tt>.
	 * <p>
	 * 
	 * @param fileName
	 *            name of the CSV output file. A default name will be used if it
	 *            is omitted
	 *            <p>
	 * @param start
	 *            starting time step, no data will be printed before this
	 *            <p>
	 * @param end
	 *            ending step, no data will be printed after this. If
	 *            <tt>end</tt> is omitted, it will be taken to be the last step
	 *            of the simulation. If both <tt>start</tt> and <tt>end</tt> are
	 *            omitted, they will be taken to be the first and last step of
	 *            the simulation respectively.
	 *            <p>
	 */
	public BankPrinter(String fileName, int start, int end,
			Bank bank) {
		super(start, end);
		this.printWriter = new CSVPrintWriter(fileName);
		this.bank = bank;
	}

	/**
	 * Create a new <tt>BankPrinter</tt>. See
	 * {@link #BankPrinter(String fileName, int start, int end)}.
	 * <tt>end</tt> is set to the end of the simulation.
	 * <p>
	 * 
	 * @param fileName
	 *            name of the CSV output file. A default name will be used if it
	 *            is omitted
	 *            <p>
	 * @param start
	 *            starting time step, no data will be printed before this
	 *            <p>
	 */
	public BankPrinter(String fileName, int start, Bank bank) {
		this(fileName, start, Integer.MAX_VALUE, bank);
	}

	/**
	 * Create a new <tt>BankPrinter</tt>. See
	 * {@link #BankPrinter(String fileName, int start, int end)}.
	 * <tt>start</tt> is set to 0. <tt>end</tt> is set to the end of the
	 * simulation.
	 * <p>
	 * 
	 * @param fileName
	 *            name of the CSV output file. A default name will be used if it
	 *            is omitted
	 *            <p>
	 */
	public BankPrinter(String fileName, Bank bank) {
		this(fileName, 0, bank);
	}

	/**
	 * Create a new <tt>BankPrinter</tt>. See
	 * {@link #BankPrinter(String fileName, int start, int end)}. A
	 * default <tt>fileName</tt> is used.
	 * <p>
	 * 
	 * @param start
	 *            starting time step, no data will be printed before this
	 *            <p>
	 * @param end
	 *            ending step, no data will be printed after this. If
	 *            <tt>end</tt> is omitted, it will be taken to be the last step
	 *            of the simulation. If both <tt>start</tt> and <tt>end</tt> are
	 *            omitted, they will be taken to be the first and last step of
	 *            the simulation respectively.
	 *            <p>
	 */
	public BankPrinter(int start, int end, Bank bank) {
		super(start, end);
		this.printWriter = new CSVPrintWriter("bank");
		this.bank = bank;
	}

	/**
	 * Create a new <tt>BankPrinter</tt>. See
	 * {@link #BankPrinter(String fileName, int start, int end)}. A
	 * default <tt>fileName</tt> is used. <tt>end</tt> is set to the end of the
	 * simulation.
	 * <p>
	 * 
	 * @param start
	 *            starting time step, no data will be printed before this
	 *            <p>
	 */
	public BankPrinter(int start, Bank bank) {
		this(start, Integer.MAX_VALUE, bank);
	}

	/**
	 * Create a new <tt>BankPrinter</tt>. See
	 * {@link #BankPrinter(String fileName, int start, int end)}. A
	 * default <tt>fileName</tt> is used. <tt>end</tt> is set to the end of the
	 * simulation. <tt>start</tt> is set to 0.
	 * 
	 */
	public BankPrinter(Bank bank) {
		this(0, bank);
	}

	/**
	 * Print data, called by Settlement.newDay() at each time step
	 */
	public void print(Settlement colony) {
		if (shouldPrint(colony))
			printWriter.println(colony.getDate(),
					formatPercent(bank.getLoanIR()), formatPercent(bank.getLTLoanIR()),
					formatPercent(bank.getDepositIR()), formatPercent(bank.getLTDepositIR()),
					bank.getTotalLoan(), bank.getTotalDeposit(),
					bank.getEquity(), colony.getCPI(),
					formatPercent(colony.getInflation()));
	}

	/**
	 * Format a fraction as a percent string truncated (toward zero) to two
	 * decimal places, e.g. 0.0045 -&gt; "0.45%". Truncating rather than rounding
	 * matches the requested display.
	 *
	 * @param fraction
	 *            the value as a fraction (0.01 == 1%)
	 * @return the truncated percent string
	 */
	private static String formatPercent(double fraction) {
		double pct = fraction * 100;
		double truncated = (long) (pct * 100) / 100.0;
		return String.format("%.2f%%", truncated);
	}

	/**
	 * Print column titles
	 */
	public void printTitles() {
		printWriter.println("Date", "LoanIR", "LTLoanIR", "DepositIR",
				"LTDepositIR", "TotalLoan", "TotalDeposit", "Equity", "CPI",
				"Inflation");
	}

	/**
	 * Clean up the printer
	 */
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
