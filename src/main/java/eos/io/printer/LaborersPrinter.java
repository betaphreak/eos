package eos.io.printer;

import eos.agent.laborer.Laborer;
import eos.economy.*;

/**
 * This printer tracks statistics of a group of laborers. To use it:
 * <p>
 * 1. Create a new <tt>LaborersPrinter</tt>. See
 * {@link #LaborersPrinter(String fileName, int period, int start, int end, Laborer[] laborers)}.
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
 * 
 */
public class LaborersPrinter extends Printer {

	// print writer that writes output to a CSV file
	private final CSVPrintWriter printWriter;
	
	// laborers to be tracked
	private final Laborer[] laborers;

	/**
	 * Create a new <tt>LaborersPrinter</tt>.
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
	 * @param laborers
	 *            laborers to be tracked
	 *            <p>
	 * 
	 */
	public LaborersPrinter(String fileName, int period, int start, int end,
			Laborer[] laborers) {
		super(period, start, end);
		this.printWriter = new CSVPrintWriter(fileName);
		this.laborers = laborers;
	}

	/**
	 * Create a new <tt>LaborersPrinter</tt>. See
	 * {@link #LaborersPrinter(String fileName, int period, int start, int end, Laborer[] laborers)}
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
	 * @param laborers
	 *            laborers to be tracked
	 *            <p>
	 * 
	 */
	public LaborersPrinter(String fileName, int period, int start,
			Laborer[] laborers) {
		this(fileName, period, start, Integer.MAX_VALUE, laborers);
	}

	/**
	 * Create a new <tt>LaborersPrinter</tt>. See
	 * {@link #LaborersPrinter(String fileName, int period, int start, int end, Laborer[] laborers)}
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
	 * @param laborers
	 *            laborers to be tracked
	 *            <p>
	 * 
	 */
	public LaborersPrinter(String fileName, int period, Laborer[] laborers) {
		this(fileName, period, 0, laborers);
	}

	/**
	 * Create a new <tt>LaborersPrinter</tt>. See
	 * {@link #LaborersPrinter(String fileName, int period, int start, int end, Laborer[] laborers)}
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
	 * @param laborers
	 *            laborers to be tracked
	 *            <p>
	 * 
	 */
	public LaborersPrinter(int period, int start, int end, Laborer[] laborers) {
		super(period, start, end);
		this.laborers = laborers;
		String fileName = "laborers";
		this.printWriter = new CSVPrintWriter(fileName);
	}

	/**
	 * Create a new <tt>LaborersPrinter</tt>. See
	 * {@link #LaborersPrinter(String fileName, int period, int start, int end, Laborer[] laborers)}
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
	 * @param laborers
	 *            laborers to be tracked
	 *            <p>
	 * 
	 */
	public LaborersPrinter(int period, int start, Laborer[] laborers) {
		this(period, start, Integer.MAX_VALUE, laborers);
	}

	/**
	 * Create a new <tt>LaborersPrinter</tt>. See
	 * {@link #LaborersPrinter(String fileName, int period, int start, int end, Laborer[] laborers)}
	 * . A default <tt>fileName</tt> is used. <tt>start</tt> is set to 0.
	 * <tt>end</tt> is set to the end of the simulation.
	 * <p>
	 * 
	 * @param period
	 *            number of steps between two prints. e.g. if <tt>period</tt> =
	 *            5, data will be printed every 5 time steps.
	 *            <p>
	 * @param laborers
	 *            laborers to be tracked
	 *            <p>
	 * 
	 */
	public LaborersPrinter(int period, Laborer[] laborers) {
		this(period, 0, laborers);
	}

	/**
	 * Print data, called by Economy at each time step
	 */
	public void print() {
		int step = Economy.getTimeStep();
		if (step >= start && step <= end && (step - start) % period == 0) {
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

			for (int i = 0; i < laborers.length; i++)
				if (laborers[i].isAlive()) {
					avgWage += laborers[i].getWage();
					avgIC += laborers[i].getIncome();
					avgConsumption += laborers[i].getConsumption();
					totSavings += laborers[i].getSavings();
					avgSavingsRate += laborers[i].getSavingsRate();
					avgNStock += laborers[i].getGood("Necessity").getQuantity();
					avgEStock += laborers[i].getGood("Enjoyment").getQuantity();
					avgNConsumption += laborers[i].getNConsumption();
					avgEConsumption += laborers[i].getEConsumption();
				}
			avgWage /= laborers.length;
			avgIC /= laborers.length;
			avgConsumption /= laborers.length;
			avgSavings = totSavings / laborers.length;
			avgSavingsRate /= laborers.length;
			avgNStock /= laborers.length;
			avgEStock /= laborers.length;
			avgNConsumption /= laborers.length;
			avgEConsumption /= laborers.length;
			printWriter.println(step, avgWage, avgIC, avgConsumption,
					avgSavings, totSavings, avgSavingsRate, avgNStock, avgEStock,
					avgNConsumption, avgEConsumption);
		}
	}

	/**
	 * Print column titles
	 */
	public void printTitles() {
		printWriter.println("Step", "AvgWage", "AvgTotalIncome",
				"AvgConsumption", "AvgSavings", "TotalSavings", "AvgSavings_Rate", "AvgNStock",
				"AvgEStock", "AvgNConsumption", "AvgEConsumption");
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
