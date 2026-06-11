package eos.io.printer;

import eos.agent.Agent;
import eos.agent.laborer.Laborer;
import eos.economy.*;

/**
 * This printer tracks statistics of the economy's laborer population. To use it:
 * <p>
 * 1. Create a new <tt>LaborersPrinter</tt>. See
 * {@link #LaborersPrinter(String fileName, int start, int end)}.
 * <p>
 * 2. Call <tt>printTitles()</tt> to print column titles.
 * <p>
 * 3. Add the printer to the Economy by calling <tt>Economy.addPrinter()</tt>.
 * <p>
 * 4. Call <tt>print()</tt> of this printer in <tt>Economy.newDay()</tt> to print
 * data.
 * <p>
 * 5. Include <tt>cleanup()</tt> of this printer in
 * <tt>Economy.cleanUpPrinters()</tt>, and call that method to clean up the
 * printers.
 * <p>
 * Each row aggregates over <em>all laborers currently alive in the economy</em>
 * (read from {@link Economy#getAgents()} at print time), so it reflects the
 * living population as it changes — the founding cohort plus any replacement and
 * immigrant households — not a fixed initial set. Averages are over the living
 * count.
 * <p>
 * The output of the printer is a CSV file. If you have closely followed the
 * above steps, the first line of the file should be the column titles, and the
 * first column is the in-game date. All entries are comma-delimited (without
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
 * Col0: average wage<br>
 * Col1: average income<br>
 * Col2: average consumption<br>
 * Col3: average savings<br>
 * Col4: total savings<br>
 * Col5: average savings rate<br>
 * Col6: average necessity stock<br>
 * Col7: average enjoyment stock<br>
 * Col8: average necessity consumption<br>
 * Col9: average enjoyment consumption<br>
 * Col10: average age of living laborers, in years<br>
 * Col11: number of living laborers<br>
 *
 */
public class LaborersPrinter extends Printer {

	// print writer that writes output to a CSV file
	private final CSVPrintWriter printWriter;

	/**
	 * Create a new <tt>LaborersPrinter</tt>.
	 * <p>
	 *
	 * @param fileName
	 *            name of the CSV output file. A default name will be used if it
	 *            is omitted
	 *            <p>
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
	 *
	 */
	public LaborersPrinter(String fileName, int start, int end) {
		super(start, end);
		this.printWriter = new CSVPrintWriter(fileName);
	}

	/**
	 * Create a new <tt>LaborersPrinter</tt>. See
	 * {@link #LaborersPrinter(String fileName, int start, int end)}.
	 * <tt>end</tt> is set to the end of the simulation.
	 * <p>
	 *
	 * @param fileName
	 *            name of the CSV output file. A default name will be used if it
	 *            is omitted
	 *            <p>
	 *
	 * @param start
	 *            starting time step, no data will be printed before this
	 *            <p>
	 *
	 */
	public LaborersPrinter(String fileName, int start) {
		this(fileName, start, Integer.MAX_VALUE);
	}

	/**
	 * Create a new <tt>LaborersPrinter</tt>. See
	 * {@link #LaborersPrinter(String fileName, int start, int end)}.
	 * <tt>start</tt> is set to 0. <tt>end</tt> is set to the end of the
	 * simulation.
	 * <p>
	 *
	 * @param fileName
	 *            name of the CSV output file. A default name will be used if it
	 *            is omitted
	 *            <p>
	 *
	 *
	 */
	public LaborersPrinter(String fileName) {
		this(fileName, 0);
	}

	/**
	 * Create a new <tt>LaborersPrinter</tt>. See
	 * {@link #LaborersPrinter(String fileName, int start, int end)}.
	 * A default <tt>fileName</tt> is used.
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
	 *
	 */
	public LaborersPrinter(int start, int end) {
		super(start, end);
		this.printWriter = new CSVPrintWriter("laborers");
	}

	/**
	 * Create a new <tt>LaborersPrinter</tt>. See
	 * {@link #LaborersPrinter(String fileName, int start, int end)}.
	 * A default <tt>fileName</tt> is used. <tt>end</tt> is set to the end of the
	 * simulation.
	 * <p>
	 *
	 * @param start
	 *            starting time step, no data will be printed before this
	 *            <p>
	 *
	 */
	public LaborersPrinter(int start) {
		this(start, Integer.MAX_VALUE);
	}

	/**
	 * Create a new <tt>LaborersPrinter</tt>. See
	 * {@link #LaborersPrinter(String fileName, int start, int end)}.
	 * A default <tt>fileName</tt> is used. <tt>start</tt> is set to 0.
	 * <tt>end</tt> is set to the end of the simulation.
	 * <p>
	 *
	 *
	 */
	public LaborersPrinter() {
		this(0);
	}

	/**
	 * Print data, called by Economy at each time step
	 */
	public void print(Economy economy) {
		if (shouldPrint(economy)) {
			double avgWage = 0;
			double avgIC = 0;
			double avgConsumption = 0;
			double avgSavings = 0;
			double totSavings = 0;
			double avgSavingsRate = 0;
			double avgNStock = 0;
			double avgEStock = 0;
			double avgNConsumption = 0;
			double avgEConsumption = 0;
			double avgAge = 0;
			int count = 0;

			// aggregate over the living laborer population, which grows and is
			// replenished over the run (founders, replacements and immigrants)
			for (Agent agent : economy.getAgents())
				if (agent instanceof Laborer laborer) {
					avgWage += laborer.getWage();
					avgIC += laborer.getIncome();
					avgConsumption += laborer.getConsumption();
					totSavings += laborer.getSavings();
					avgSavingsRate += laborer.getSavingsRate();
					avgNStock += laborer.getGood("Necessity").getQuantity();
					avgEStock += laborer.getGood("Enjoyment").getQuantity();
					avgNConsumption += laborer.getNConsumption();
					avgEConsumption += laborer.getEConsumption();
					avgAge += laborer.getAgeYears();
					count++;
				}
			if (count > 0) {
				avgWage /= count;
				avgIC /= count;
				avgConsumption /= count;
				avgSavings = totSavings / count;
				avgSavingsRate /= count;
				avgNStock /= count;
				avgEStock /= count;
				avgNConsumption /= count;
				avgEConsumption /= count;
				avgAge /= count;
			}
			printWriter.println(economy.getDate(), avgWage, avgIC, avgConsumption,
					avgSavings, totSavings, avgSavingsRate, avgNStock, avgEStock,
					avgNConsumption, avgEConsumption, avgAge, count);
		}
	}

	/**
	 * Print column titles
	 */
	public void printTitles() {
		printWriter.println("Date", "AvgWage", "AvgTotalIncome",
				"AvgConsumption", "AvgSavings", "TotalSavings", "AvgSavings_Rate", "AvgNStock",
				"AvgEStock", "AvgNConsumption", "AvgEConsumption", "AvgAge", "Count");
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
