package com.civstudio.io.printer;

import static com.civstudio.io.sink.ColumnSpec.*;

import com.civstudio.agent.firm.BuilderFirm;
import com.civstudio.io.sink.ColumnSpec;
import com.civstudio.settlement.BuildProject;
import com.civstudio.settlement.Settlement;

/**
 * Writes a time-series of the colony's construction: how big the colony is, how
 * much capacity it has, and what its {@link BuilderFirm} is doing. Register with
 * {@link Settlement#addPrinter} and finalize with {@link Settlement#cleanUpPrinters}.
 * <p>
 * Columns: Date, Size, EffectiveSlots, Delivered, TotalDelivered, Revenue,
 * WageBudget, Labor, Wage, ActiveTasks, RemainingWork. {@code Size} and {@code
 * EffectiveSlots} are the colony's current size and usable slot count (they step up
 * as the builder finishes a ring); {@code Delivered} is the build-units the builder
 * applied this step and {@code TotalDelivered} the cumulative total; {@code
 * ActiveTasks} and {@code RemainingWork} are the count of, and outstanding
 * build-units across, the ring currently being built (both 0 when idle).
 */
public class BuilderPrinter extends Printer {

	private final BuilderFirm builder;

	/**
	 * Create a new {@code BuilderPrinter}.
	 *
	 * @param fileName name of the CSV output file
	 * @param builder  the colony's builder
	 */
	public BuilderPrinter(String fileName, BuilderFirm builder) {
		super(fileName);
		this.builder = builder;
	}

	@Override
	public String tableName() {
		return "builder";
	}

	@Override
	public ColumnSpec[] columns() {
		return new ColumnSpec[] { date("Date"), integer("Size"),
				integer("EffectiveSlots"), real("Delivered"), real("TotalDelivered"),
				real("Revenue"), real("WageBudget"), real("Labor"), real("Wage"),
				integer("ActiveTasks"), real("RemainingWork") };
	}

	@Override
	public void print(Settlement colony) {
		if (!shouldPrint(colony))
			return;

		double remainingWork = 0;
		for (BuildProject p : colony.activeProjects())
			remainingWork += p.getWorkRemaining();

		sink.writeRow(colony.getDate(), colony.getSize(),
				colony.getSlotInfo().effective(), builder.getBuildUnitsDelivered(),
				builder.getTotalDelivered(), builder.getRevenue(),
				builder.getLaborCost(), builder.getLabor(), builder.getWage(),
				colony.activeProjects().size(), remainingWork);
	}
}
