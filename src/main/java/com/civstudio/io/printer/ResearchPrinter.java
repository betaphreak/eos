package com.civstudio.io.printer;

import static com.civstudio.io.sink.ColumnSpec.*;

import com.civstudio.io.sink.ColumnSpec;
import com.civstudio.settlement.Settlement;
import com.civstudio.tech.ResearchState;
import com.civstudio.tech.Sector;
import com.civstudio.tech.Tech;

/**
 * Writes a time-series of the colony's research: the tech it is currently
 * researching and progress toward it, how many techs it has completed and knows,
 * and the per-{@link Sector} tech productivity multipliers those completions have
 * raised. Register with {@link Settlement#addPrinter} (only for a colony with
 * research enabled) and finalize with {@link Settlement#cleanUpPrinters}.
 * <p>
 * Columns: Date, Focus, Progress, Cost, RP, Completed, Known, NecessityMult,
 * EnjoymentMult, CapitalMult, ExportMult. {@code Focus} is the current tech id (or
 * {@code "-"} when none is being researched); {@code Progress}/{@code Cost} are the
 * accumulated research points and the focus's scaled cost; {@code RP} is the
 * research points the science firm produced this step; the {@code *Mult} columns are
 * the live sector productivity multipliers (1.00 until a {@code SECTOR_PRODUCTIVITY}
 * tech completes).
 */
public class ResearchPrinter extends Printer {

	/**
	 * Create a new {@code ResearchPrinter}.
	 *
	 * @param fileName name of the CSV output file
	 */
	public ResearchPrinter(String fileName) {
		super(fileName);
	}

	@Override
	public String tableName() {
		return "research";
	}

	@Override
	public ColumnSpec[] columns() {
		return new ColumnSpec[] { date("Date"), text("Focus"), real("Progress"),
				real("Cost"), real("RP"), integer("Completed"), integer("Known"),
				real("NecessityMult"), real("EnjoymentMult"), real("CapitalMult"),
				real("ExportMult") };
	}

	@Override
	public void print(Settlement colony) {
		if (!shouldPrint(colony))
			return;
		ResearchState research = colony.getResearch();
		if (research == null)
			return;

		Tech focus = research.getFocus();
		String focusName = focus == null ? "-" : focus.type();

		sink.writeRow(colony.getDate(), focusName, research.getProgress(),
				research.effectiveCost(), research.getLastResearchPoints(),
				research.getCompletedCount(), research.getKnownCount(),
				colony.getTechMultiplier(Sector.NECESSITY),
				colony.getTechMultiplier(Sector.ENJOYMENT),
				colony.getTechMultiplier(Sector.CAPITAL),
				colony.getTechMultiplier(Sector.EXPORT));
	}
}
