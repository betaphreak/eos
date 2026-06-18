package eos.io.printer;

import eos.agent.Retinue;
import eos.settlement.Settlement;

/**
 * Writes a CSV time-series of the {@link Retinue}: how many peasants remain,
 * their average skill and age, the food it carries in its larder, the necessity it
 * ate and the number that starved last step, and the cumulative relief cost billed
 * to the Ruler. Register with {@link Settlement#addPrinter} and finalize with {@link
 * Settlement#cleanUpPrinters}.
 * <p>
 * Columns: Date, Count, AvgSkill, AvgAge, Larder (necessity the pool holds — what a
 * collapsing colony's abandoned food stores fold into), Consumed, Starved,
 * BilledTotal, Imported (cumulative gold-funded immigrants recruited into the pool).
 */
public class RetinuePrinter extends Printer {

	private final CSVPrintWriter printWriter;
	private final Retinue retinue;

	/**
	 * Create a new {@code RetinuePrinter}.
	 *
	 * @param fileName
	 *            name of the CSV output file
	 * @param retinue
	 *            the peasant pool to report on
	 */
	public RetinuePrinter(String fileName, Retinue retinue) {
		super();
		this.printWriter = new CSVPrintWriter(fileName);
		this.retinue = retinue;
	}

	@Override
	public void print(Settlement colony) {
		if (!shouldPrint(colony))
			return;
		printWriter.println(colony.getDate(), retinue.size(), retinue.avgSkill(),
				retinue.avgAgeYears(), retinue.getLarder(), retinue.getLastConsumed(),
				retinue.getLastStarved(), retinue.getTotalBilledToRuler(),
				retinue.getImmigrantCount());
	}

	@Override
	public void printTitles() {
		printWriter.println("Date", "Count", "AvgSkill", "AvgAge", "Larder",
				"Consumed", "Starved", "BilledTotal", "Imported");
	}

	@Override
	public void cleanup() {
		printWriter.cleanup();
	}

	@Override
	public String getFileName() {
		return printWriter.getFileName();
	}
}
