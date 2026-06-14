package eos.io.printer;

import eos.agent.Agent;
import eos.agent.firm.ConsumerGoodFirm;
import eos.settlement.Settlement;

/**
 * Tracks the colony's living firms of one product (enjoyment or necessity),
 * aggregating over <em>all such firms currently alive</em> read from {@link
 * Settlement#getAgents()} at print time — so it follows the count as the ruler's
 * dynamic firm provisioning charters and dissolves firms, rather than a fixed
 * initial array (the role {@link FirmsPrinter} plays for a static set). Its first
 * data column is the firm <b>Count</b>, the headline of the dynamic model.
 * <p>
 * One row is written on the first day of each in-game month (see {@link
 * Printer#shouldPrint}). Columns: Date, Count, TotalRevenue, TotalOutput,
 * TotalProfit, AvgUtilization, TotalLoan, TotalLaborCost, TotalCapitalCost.
 */
public class DynamicFirmsPrinter extends Printer {

	private final CSVPrintWriter printWriter;

	// the concrete firm type tracked (e.g. EFirm.class / NFirm.class)
	private final Class<? extends ConsumerGoodFirm> type;

	/**
	 * Create a printer tracking every living firm of <tt>type</tt>.
	 *
	 * @param fileName
	 *            the CSV output file name
	 * @param type
	 *            the concrete consumer-good firm class to aggregate over
	 */
	public DynamicFirmsPrinter(String fileName,
			Class<? extends ConsumerGoodFirm> type) {
		super(0, Integer.MAX_VALUE);
		this.printWriter = new CSVPrintWriter(fileName);
		this.type = type;
	}

	/**
	 * Print data, called by Settlement.newDay() at each time step.
	 */
	public void print(Settlement colony) {
		if (shouldPrint(colony)) {
			int count = 0;
			double totRevenue = 0;
			double totOutput = 0;
			double totProfit = 0;
			double totUtil = 0;
			double totLoan = 0;
			double totLaborCost = 0;
			double totCapitalCost = 0;

			for (Agent agent : colony.getAgents())
				if (type.isInstance(agent)) {
					ConsumerGoodFirm f = type.cast(agent);
					if (!f.isAlive())
						continue;
					count++;
					totRevenue += f.getRevenue();
					totOutput += f.getOutput();
					totProfit += f.getProfit();
					totUtil += f.getSmoothedUtilization();
					totLoan += f.getLoan();
					totLaborCost += f.getLaborCost();
					totCapitalCost += f.getCapitalCost();
				}
			double avgUtil = count > 0 ? totUtil / count : 0;

			printWriter.println(colony.getDate(), count, totRevenue, totOutput,
					totProfit, avgUtil, totLoan, totLaborCost, totCapitalCost);
		}
	}

	/**
	 * Print column titles.
	 */
	public void printTitles() {
		printWriter.println("Date", "Count", "TotalRevenue", "TotalOutput",
				"TotalProfit", "AvgUtilization", "TotalLoan", "TotalLaborCost",
				"TotalCapitalCost");
	}

	/**
	 * Clean up the printer.
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
