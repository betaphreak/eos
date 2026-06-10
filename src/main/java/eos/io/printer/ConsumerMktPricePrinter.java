package eos.io.printer;

import eos.market.*;
import eos.economy.*;

/**
 * This printer tracks the price of a consumer market (e.g. enjoyment market or
 * necessity market) and prints to a CSV file. To use it:
 * <p>
 * 1. Create a new <tt>ConsumerMktPricePrinter</tt>. See
 * {@link #ConsumerMktPricePrinter(String fileName, int period, int start, int end, ConsumerGoodMarket market)}.
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
 * Col0: time step <br>
 * Col1: market price<br>
 */
public class ConsumerMktPricePrinter extends Printer {

	// print writer that writes output to a CSV file
	private final CSVPrintWriter printWriter;

	// market to be tracked
	private final ConsumerGoodMarket mkt;

	/**
	 * Create a new <tt>ConsumerMktPricePrinter</tt>.
	 * <p>
	 * e.g. To track the necessity price,
	 * <p>
	 * <tt> ConsumerMktPricePrinter nPricePrinter = new ConsumerMktPricePrinter(5, nMarket);</tt>
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
	 * @param end
	 *            ending step, no data will be printed after this. If
	 *            <tt>end</tt> is omitted, it will be taken to be the last step
	 *            of the simulation. If both <tt>start</tt> and <tt>end</tt> are
	 *            omitted, they will be taken to be the first and last step of
	 *            the simulation respectively.
	 *            <p>
	 * @param market
	 *            market to be tracked
	 *            <p>
	 * 
	 */
	public ConsumerMktPricePrinter(String fileName, int period, int start,
			int end, ConsumerGoodMarket market) {
		super(period, start, end);
		this.printWriter = new CSVPrintWriter(fileName);
		this.mkt = market;
	}

	/**
	 * Create a new <tt>ConsumerMktPricePrinter</tt>. See
	 * {@link #ConsumerMktPricePrinter(String fileName, int period, int start, int end, ConsumerGoodMarket market)}
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
	 * @param market
	 *            market to be tracked
	 *            <p>
	 * 
	 */
	public ConsumerMktPricePrinter(String fileName, int period, int start,
			ConsumerGoodMarket market) {
		this(fileName, period, start, Integer.MAX_VALUE, market);
	}

	/**
	 * Create a new <tt>ConsumerMktPricePrinter</tt>. See
	 * {@link #ConsumerMktPricePrinter(String fileName, int period, int start, int end, ConsumerGoodMarket market)}
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
	 * @param market
	 *            market to be tracked
	 *            <p>
	 * 
	 */
	public ConsumerMktPricePrinter(String fileName, int period,
			ConsumerGoodMarket market) {
		this(fileName, period, 0, market);
	}

	/**
	 * Create a new <tt>ConsumerMktPricePrinter</tt>. See
	 * {@link #ConsumerMktPricePrinter(String fileName, int period, int start, int end, ConsumerGoodMarket market)}
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
	 * @param market
	 *            market to be tracked
	 *            <p>
	 * 
	 */
	public ConsumerMktPricePrinter(int period, int start, int end,
			ConsumerGoodMarket market) {
		super(period, start, end);
		this.mkt = market;
		String fileName = market.getGood() + "_Price";
		this.printWriter = new CSVPrintWriter(fileName);
	}

	/**
	 * Create a new <tt>ConsumerMktPricePrinter</tt>. See
	 * {@link #ConsumerMktPricePrinter(String fileName, int period, int start, int end, ConsumerGoodMarket market)}
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
	 * @param market
	 *            market to be tracked
	 *            <p>
	 * 
	 */
	public ConsumerMktPricePrinter(int period, int start,
			ConsumerGoodMarket market) {
		this(period, start, Integer.MAX_VALUE, market);
	}

	/**
	 * Create a new <tt>ConsumerMktPricePrinter</tt>. See
	 * {@link #ConsumerMktPricePrinter(String fileName, int period, int start, int end, ConsumerGoodMarket market)}
	 * . A default <tt>fileName</tt> is used. <tt>start</tt> is set to 0.
	 * <tt>end</tt> is set to the end of the simulation.
	 * <p>
	 * 
	 * @param period
	 *            number of steps between two prints. e.g. if <tt>period</tt> =
	 *            5, data will be printed every 5 time steps.
	 *            <p>
	 * @param market
	 *            market to be tracked
	 *            <p>
	 * 
	 */
	public ConsumerMktPricePrinter(int period, ConsumerGoodMarket market) {
		this(period, 0, market);
	}

	/**
	 * Print data, called by Economy.step() at each time step
	 */
	public void print() {
		int step = Economy.getTimeStep();
		if (step >= start && step <= end && (step - start) % period == 0)
			printWriter.println(step, mkt.getLastMktPrice());
	}

	/**
	 * Print column titles
	 */
	public void printTitles() {
		printWriter.println("Step", mkt.getGood() + "_Price");
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
