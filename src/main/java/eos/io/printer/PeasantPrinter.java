package eos.io.printer;

import eos.agent.PeasantPool;
import eos.settlement.Settlement;

/**
 * Writes a CSV time-series of the {@link PeasantPool}: how many peasants remain,
 * their average skill and age, the necessity they ate and the number that starved
 * last step, and the cumulative relief cost billed to the Ruler. Register with
 * {@link Settlement#addPrinter} and finalize with {@link Settlement#cleanUpPrinters}.
 * <p>
 * Columns: Date, Count, AvgSkill, AvgAge, Consumed, Starved, BilledTotal,
 * Imported (cumulative gold-funded immigrants recruited into the pool).
 */
public class PeasantPrinter extends Printer {

	private final CSVPrintWriter printWriter;
	private final PeasantPool pool;

	/**
	 * Create a new {@code PeasantPrinter}.
	 *
	 * @param fileName
	 *            name of the CSV output file
	 * @param pool
	 *            the peasant pool to report on
	 */
	public PeasantPrinter(String fileName, PeasantPool pool) {
		super();
		this.printWriter = new CSVPrintWriter(fileName);
		this.pool = pool;
	}

	@Override
	public void print(Settlement colony) {
		if (!shouldPrint(colony))
			return;
		printWriter.println(colony.getDate(), pool.size(), pool.avgSkill(),
				pool.avgAgeYears(), pool.getLastConsumed(), pool.getLastStarved(),
				pool.getTotalBilledToRuler(), pool.getImmigrantCount());
	}

	@Override
	public void printTitles() {
		printWriter.println("Date", "Count", "AvgSkill", "AvgAge", "Consumed",
				"Starved", "BilledTotal", "Imported");
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
