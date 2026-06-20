package com.civstudio.io.printer;

import com.civstudio.agent.firm.BuilderFirm;
import com.civstudio.settlement.BuildProject;
import com.civstudio.settlement.Settlement;

/**
 * Writes a CSV time-series of the colony's construction: how big the colony is,
 * how much capacity it has, and what its {@link BuilderFirm} is doing. Register
 * with {@link Settlement#addPrinter} and finalize with {@link
 * Settlement#cleanUpPrinters}.
 * <p>
 * Columns: Date, Size, EffectiveSlots, Delivered, TotalDelivered, Revenue,
 * WageBudget, Labor, Wage, ActiveTasks, RemainingWork. {@code Size} and {@code
 * EffectiveSlots} are the colony's current size and usable slot count (they step
 * up as the builder finishes a ring); {@code Delivered} is the build-units the
 * builder applied this step and {@code TotalDelivered} the cumulative total;
 * {@code ActiveTasks} and {@code RemainingWork} are the count of, and outstanding
 * build-units across, the ring currently being built (both 0 when the builder is
 * idle).
 */
public class BuilderPrinter extends Printer {

	private final CSVPrintWriter printWriter;
	private final BuilderFirm builder;

	/**
	 * Create a new {@code BuilderPrinter}.
	 *
	 * @param fileName
	 *            name of the CSV output file
	 * @param builder
	 *            the colony's builder
	 */
	public BuilderPrinter(String fileName, BuilderFirm builder) {
		super();
		this.printWriter = new CSVPrintWriter(fileName);
		this.builder = builder;
	}

	@Override
	public void print(Settlement colony) {
		if (!shouldPrint(colony))
			return;

		double remainingWork = 0;
		for (BuildProject p : colony.activeProjects())
			remainingWork += p.getWorkRemaining();

		printWriter.println(colony.getDate(), colony.getSize(),
				colony.getSlotInfo().effective(),
				builder.getBuildUnitsDelivered(), builder.getTotalDelivered(),
				builder.getRevenue(), builder.getLaborCost(), builder.getLabor(),
				builder.getWage(), colony.activeProjects().size(), remainingWork);
	}

	@Override
	public void printTitles() {
		printWriter.println("Date", "Size", "EffectiveSlots", "Delivered",
				"TotalDelivered", "Revenue", "WageBudget", "Labor", "Wage",
				"ActiveTasks", "RemainingWork");
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
