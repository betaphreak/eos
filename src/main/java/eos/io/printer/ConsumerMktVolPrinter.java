package eos.io.printer;

import eos.market.*;
import eos.economy.*;

/**
 * This printer tracks the trading volume of a consumer market (e.g. enjoyment
 * market or necessity market) and prints to a CSV file. To use it:
 * <p>
 * 1. Create a new <tt>ConsumerMktVolPrinter</tt>. See
 * {@link #ConsumerMktVolPrinter(String fileName, int start, int end, ConsumerGoodMarket market)}.
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
 * Col0: in-game date<br>
 * Col1: volume traded<br>
 * Col2: market supply<br>
 */
public class ConsumerMktVolPrinter extends Printer {

	// print writer that writes output to a CSV file
	private final CSVPrintWriter printWriter;

	// market to be tracked
	private final ConsumerGoodMarket mkt;

	/**
	 * Create a new <tt>ConsumerMktVolPrinter</tt>.
	 * <p>
	 * e.g. To track the necessity volume,
	 * <p>
	 * <tt> ConsumerMktVolPrinter nVolPrinter = new ConsumerMktVolPrinter(5, nMarket);</tt>
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
	 * @param market
	 *            market to be tracked
	 *            <p>
	 * 
	 */
	public ConsumerMktVolPrinter(String fileName, int start,
			int end, ConsumerGoodMarket market) {
		super(start, end);
		this.printWriter = new CSVPrintWriter(fileName);
		this.mkt = market;
	}

	/**
	 * Create a new <tt>ConsumerMktVolPrinter</tt>. See
	 * {@link #ConsumerMktVolPrinter(String fileName, int start, int end, ConsumerGoodMarket market)}
	 * . <tt>end</tt> is set to the end of the simulation.
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
	 * @param market
	 *            market to be tracked
	 *            <p>
	 * 
	 */
	public ConsumerMktVolPrinter(String fileName, int start,
			ConsumerGoodMarket market) {
		this(fileName, start, Integer.MAX_VALUE, market);
	}

	/**
	 * Create a new <tt>ConsumerMktVolPrinter</tt>. See
	 * {@link #ConsumerMktVolPrinter(String fileName, int start, int end, ConsumerGoodMarket market)}
	 * . <tt>start</tt> is set to 0. <tt>end</tt> is set to the end of the
	 * simulation.
	 * <p>
	 * 
	 * @param fileName
	 *            name of the CSV output file. A default name will be used if it
	 *            is omitted
	 *            <p>
	 * 
	 * @param market
	 *            market to be tracked
	 *            <p>
	 * 
	 */
	public ConsumerMktVolPrinter(String fileName,
			ConsumerGoodMarket market) {
		this(fileName, 0, market);
	}

	/**
	 * Create a new <tt>ConsumerMktVolPrinter</tt>. See
	 * {@link #ConsumerMktVolPrinter(String fileName, int start, int end, ConsumerGoodMarket market)}
	 * . A default <tt>fileName</tt> is used.
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
	 * @param market
	 *            market to be tracked
	 *            <p>
	 * 
	 */
	public ConsumerMktVolPrinter(int start, int end,
			ConsumerGoodMarket market) {
		super(start, end);
		this.mkt = market;
		String fileName = market.getGood() + "_Vol";
		this.printWriter = new CSVPrintWriter(fileName);
	}

	/**
	 * Create a new <tt>ConsumerMktVolPrinter</tt>. See
	 * {@link #ConsumerMktVolPrinter(String fileName, int start, int end, ConsumerGoodMarket market)}
	 * . A default <tt>fileName</tt> is used. <tt>end</tt> is set to the end of
	 * the simulation.
	 * <p>
	 * 
	 * @param start
	 *            starting time step, no data will be printed before this
	 *            <p>
	 * @param market
	 *            market to be tracked
	 *            <p>
	 * 
	 */
	public ConsumerMktVolPrinter(int start,
			ConsumerGoodMarket market) {
		this(start, Integer.MAX_VALUE, market);
	}

	/**
	 * Create a new <tt>ConsumerMktVolPrinter</tt>. See
	 * {@link #ConsumerMktVolPrinter(String fileName, int start, int end, ConsumerGoodMarket market)}
	 * . A default <tt>fileName</tt> is used. <tt>start</tt> is set to 0.
	 * <tt>end</tt> is set to the end of the simulation.
	 * <p>
	 * 
	 * @param market
	 *            market to be tracked
	 *            <p>
	 * 
	 */
	public ConsumerMktVolPrinter(ConsumerGoodMarket market) {
		this(0, market);
	}

	/**
	 * Print data, called by Economy at each time step
	 */
	public void print(Economy economy) {
		if (shouldPrint(economy))
			printWriter.println(economy.getDate(), mkt.getLastMktGoodVol(),
					mkt.getLastMktSupply());
	}

	/**
	 * Print column titles
	 */
	public void printTitles() {
		printWriter.println("Date", mkt.getGood() + "_Volume", mkt.getGood()
				+ "_Supply");
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
