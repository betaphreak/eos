package eos.io.printer;

import eos.agent.firm.Firm;
import eos.economy.*;

/**
 * This printer tracks statistics of a group of firms. To use it:
 * <p>
 * 1. Create a new <tt>FirmsPrinter</tt>. See
 * {@link #FirmsPrinter(String fileName, int period, int start, int end, Firm[] firms)}.
 * <p>
 * 2. Call <tt>printTitles()</tt> to print column titles.
 * <p>
 * 3. Add the printer to the Economy by calling <tt>Economy.addPrinter()</tt>.
 * <p>
 * 4. Call <tt>print()</tt> of this printer in <tt>Economy.step()</tt> to print
 * data.
 * <p>
 * 5. Include <tt>cleanup()</tt> of this printer in
 * <tt>Economy.cleanUpPrinters()</tt>, and call that method to clean up the
 * printers.
 * <p>
 * The output of the printer is a CSV file. If you have closely followed the
 * above steps, the first line of the file should be the column titles, and the
 * first column is the time step. All entries are comma-delimited (without
 * space). The file could be directly used as an input file for <tt>Grapher</tt>
 * and <tt>MultiAxisGrapher</tt>. You could also open the file with most
 * spreadsheet softwares like Microsoft Excel and OpenOffice Spreadsheet, and
 * perform any data processing you wish.
 * <p>
 * If you omit the file name or provide a simple file name in the constructor,
 * the output file will be saved in a folder called "output". If on the other
 * hand, you specify a directory in the file name, the output file will be saved
 * in your specified directory.
 * <p>
 * The default columns to be printed are: <br>
 * Col0: time step<br>
 * Col1: total revenue<br>
 * Col2: total output<br>
 * Col3: total loan<br>
 * Col4: average revenue<br>
 * Col5: average output<br>
 * Col6: average loan<br>
 * Col7: average profit<br>
 * Col8: average labor<br>
 * Col9: average marginal profit<br>
 * Col10: total cost<br>
 * Col11: total labor cost<br>
 * Col12: total capital cost<br>
 * 
 */
public class FirmsPrinter extends Printer {

	// print writer that writes output to a CSV file
	private final CSVPrintWriter printWriter;

	// firms to be tracked
	private final Firm[] firms;

	/**
	 * Create a new <tt>FirmsPrinter</tt>.
	 * <p>
	 * 
	 * @param fileName
	 *            name of the CSV output file. A default name will be used if it
	 *            is omitted
	 *            <p>
	 * 
	 * @param period
	 *            number of steps between two printing. e.g. if <tt>period</tt>
	 *            = 5, data will be printed every 5 time steps.
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
	 * @param firms
	 *            firms to be tracked
	 *            <p>
	 * 
	 */
	public FirmsPrinter(String fileName, int period, int start, int end,
			Firm[] firms) {
		super(period, start, end);
		this.printWriter = new CSVPrintWriter(fileName);
		this.firms = firms;
	}

	/**
	 * Create a new <tt>FirmsPrinter</tt>. See
	 * {@link #FirmsPrinter(String fileName, int period, int start, int end, Firm[] firms)}
	 * . <tt>end</tt> is set to the end of the simulation.
	 * <p>
	 * 
	 * @param fileName
	 *            name of the CSV output file. A default name will be used if it
	 *            is omitted
	 *            <p>
	 * 
	 * @param period
	 *            number of steps between two prints. e.g. if <tt>period</tt> =
	 *            5, data will be printed every 5 time steps.
	 *            <p>
	 * @param start
	 *            starting time step, no data will be printed before this
	 *            <p>
	 * @param firms
	 *            firms to be tracked
	 *            <p>
	 * 
	 */
	public FirmsPrinter(String fileName, int period, int start, Firm[] firms) {
		this(fileName, period, start, Integer.MAX_VALUE, firms);
	}

	/**
	 * Create a new <tt>FirmsPrinter</tt>. See
	 * {@link #FirmsPrinter(String fileName, int period, int start, int end, Firm[] firms)}
	 * . <tt>start</tt> is set to 0. <tt>end</tt> is set to the end of the
	 * simulation.
	 * <p>
	 * 
	 * @param fileName
	 *            name of the CSV output file. A default name will be used if it
	 *            is omitted
	 *            <p>
	 * 
	 * @param period
	 *            number of steps between two prints. e.g. if <tt>period</tt> =
	 *            5, data will be printed every 5 time steps.
	 *            <p>
	 * @param firms
	 *            firms to be tracked
	 *            <p>
	 * 
	 */
	public FirmsPrinter(String fileName, int period, Firm[] firms) {
		this(fileName, period, 0, firms);
	}

	/**
	 * Create a new <tt>FirmsPrinter</tt>. See
	 * {@link #FirmsPrinter(String fileName, int period, int start, int end, Firm[] firms)}
	 * . A default <tt>fileName</tt> is used.
	 * <p>
	 * 
	 * @param period
	 *            number of steps between two prints. e.g. if <tt>period</tt> =
	 *            5, data will be printed every 5 time steps.
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
	 * @param firms
	 *            firms to be tracked
	 *            <p>
	 * 
	 */
	public FirmsPrinter(int period, int start, int end, Firm[] firms) {
		super(period, start, end);
		this.firms = firms;
		String fileName = "firms";
		this.printWriter = new CSVPrintWriter(fileName);
	}

	/**
	 * Create a new <tt>FirmsPrinter</tt>. See
	 * {@link #FirmsPrinter(String fileName, int period, int start, int end, Firm[] firms)}
	 * . A default <tt>fileName</tt> is used. <tt>end</tt> is set to the end of
	 * the simulation.
	 * <p>
	 * 
	 * @param period
	 *            number of steps between two prints. e.g. if <tt>period</tt> =
	 *            5, data will be printed every 5 time steps.
	 *            <p>
	 * @param start
	 *            starting time step, no data will be printed before this
	 *            <p>
	 * @param firms
	 *            firms to be tracked
	 *            <p>
	 * 
	 */
	public FirmsPrinter(int period, int start, Firm[] firms) {
		this(period, start, Integer.MAX_VALUE, firms);
	}

	/**
	 * Create a new <tt>FirmsPrinter</tt>. See
	 * {@link #FirmsPrinter(String fileName, int period, int start, int end, Firm[] firms)}
	 * . A default <tt>fileName</tt> is used. <tt>start</tt> is set to 0.
	 * <tt>end</tt> is set to the end of the simulation.
	 * <p>
	 * 
	 * @param period
	 *            number of steps between two prints. e.g. if <tt>period</tt> =
	 *            5, data will be printed every 5 time steps.
	 *            <p>
	 * @param firms
	 *            firms to be tracked
	 *            <p>
	 * 
	 */
	public FirmsPrinter(int period, Firm[] firms) {
		this(period, 0, firms);
	}

	/**
	 * Print data, called by Economy.step() at each time step
	 */
	public void print() {
		int step = Economy.getTimeStep();
		if (step >= start && step <= end && (step - start) % period == 0) {
			double totRevenue = 0;
			double avgRevenue = 0;
			double totOutput = 0;
			double avgOutput = 0;
			double totLoan = 0;
			double avgLoan = 0;
			double avgLabor = 0;
			double avgProfit = 0;
			double avgMarginalProfit = 0;
			double totCost = 0;
			double totLaborCost = 0;
			double totCapitalCost = 0;

			for (int i = 0; i < firms.length; i++)
				if (firms[i].isAlive()) {
					totRevenue += firms[i].getRevenue();
					totOutput += firms[i].getOutput();
					totLoan += firms[i].getLoan();
					avgProfit += firms[i].getProfit();
					avgLabor += firms[i].getLabor();
					avgMarginalProfit += firms[i].getMarginalProfit();
					totCost += firms[i].getTotalCost();
					totLaborCost += firms[i].getLaborCost();
					totCapitalCost += firms[i].getCapitalCost();
				}
			avgRevenue = totRevenue / firms.length;
			avgOutput = totOutput / firms.length;
			avgLoan = totLoan / firms.length;
			avgProfit /= firms.length;
			avgLabor /= firms.length;
			avgMarginalProfit /= firms.length;

			printWriter.println(step, totRevenue, totOutput, totLoan,
					avgRevenue, avgOutput, avgLoan, avgProfit, avgLabor,
					avgMarginalProfit, totCost, totLaborCost, totCapitalCost);
		}
	}

	/**
	 * Print column titles
	 */
	public void printTitles() {
		printWriter.println("Step", "TotalRevenue", "TotalOutput", "TotalLoan",
				"AvgRevenue", "AvgOutput", "AvgLoan", "AvgProfit", "AvgLabor",
				"AvgMarginalProfit", "TotalCost", "TotalLaborCost",
				"TotalCapitalCost");
	}

	/**
	 * Clean up the printer
	 */
	public void cleanup() {
		printWriter.cleanup();

	}

	/**
	 * Returns the name of the output file.
	 * 
	 * @return the name of the output file
	 */
	public String getFileName() {
		return printWriter.getFileName();
	}
}
