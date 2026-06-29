package com.civstudio.io.printer;

import static com.civstudio.io.sink.ColumnSpec.*;

import com.civstudio.agent.firm.StrategicFirm;
import com.civstudio.bank.Bank;
import com.civstudio.io.sink.ColumnSpec;
import com.civstudio.settlement.BuildProject;
import com.civstudio.settlement.Settlement;
import com.civstudio.tech.ResearchState;
import com.civstudio.tech.Tech;

/**
 * Writes a time-series of the colony's <b>crown services</b> — the three
 * ruler-funded public undertakings — consolidated into one CSV (one wide row per
 * cycle), rather than a file each:
 * <ul>
 * <li><b>export</b> — what the {@link StrategicFirm} shipped out and the bank equity
 * its earnings build up ({@code Exported}, {@code TotalExported}, {@code Equity});</li>
 * <li><b>construction</b> — the colony's plot count and capacity and the plots its
 * builder is opening ({@code Plots}, {@code MaxPlots}, {@code ActiveTasks},
 * {@code RemainingWork}); all read from the colony, so present even before any
 * builder works;</li>
 * <li><b>research</b> — the tech being researched and progress toward it ({@code
 * Focus}, {@code Progress}, {@code Cost}, {@code RP}, {@code Completed}).</li>
 * </ul>
 * A service the colony lacks (no export firm, no research) contributes blank/zero
 * cells, so the schema is uniform across colonies. The firm-level finance of the
 * export and builder firms is reported with every other firm in {@code Firms.csv}
 * ({@code FirmsPrinter}); the per-sector tech multipliers a completed tech raises are
 * read live off the colony (and folded into firm output), not charted here.
 * <p>
 * One row is written on the first day of each in-game month (see {@link
 * Printer#shouldPrint}). Register with {@link Settlement#addPrinter} and finalize with
 * {@link Settlement#cleanUpPrinters}.
 */
public class ServicesPrinter extends Printer {

	// the colony's export firm and the bank its earnings accrue to, or null for a
	// colony with no export sector (then the export columns are 0)
	private final StrategicFirm strategic;
	private final Bank strategicBank;

	/**
	 * Create a new {@code ServicesPrinter}.
	 *
	 * @param fileName     name of the CSV output file
	 * @param strategic    the colony's export firm, or {@code null} if it has none
	 * @param strategicBank the bank the export earnings accrue to, or {@code null}
	 */
	public ServicesPrinter(String fileName, StrategicFirm strategic, Bank strategicBank) {
		super(fileName);
		this.strategic = strategic;
		this.strategicBank = strategicBank;
	}

	@Override
	public String tableName() {
		return "services";
	}

	@Override
	public ColumnSpec[] columns() {
		return new ColumnSpec[] { date("Date"),
				// export
				real("Exported"), real("TotalExported"), real("Equity"),
				// construction
				integer("Plots"), integer("MaxPlots"), integer("ActiveTasks"),
				real("RemainingWork"),
				// research
				text("Focus"), real("Progress"), real("Cost"), real("RP"),
				integer("Completed") };
	}

	@Override
	public void print(Settlement colony) {
		if (!shouldPrint(colony))
			return;

		// export (0 when the colony has no export sector)
		double exported = strategic != null ? strategic.getExported() : 0;
		double totalExported = strategic != null ? strategic.getTotalExported() : 0;
		double equity = strategicBank != null ? strategicBank.getEquity() : 0;

		// construction — all read from the colony, so always meaningful
		double remainingWork = 0;
		for (BuildProject p : colony.activeProjects())
			remainingWork += p.getWorkRemaining();

		// research ("-"/0 when the colony does no research)
		ResearchState research = colony.getResearch();
		String focus = "-";
		double progress = 0, cost = 0, rp = 0;
		int completed = 0;
		if (research != null) {
			Tech f = research.getFocus();
			focus = f == null ? "-" : f.type();
			progress = research.getProgress();
			cost = research.effectiveCost();
			rp = research.getLastResearchPoints();
			completed = research.getCompletedCount();
		}

		sink.writeRow(colony.getDate(), exported, totalExported, equity,
				colony.getPlotCount(), colony.getMaxPlots(),
				colony.activeProjects().size(), remainingWork, focus, progress, cost,
				rp, completed);
	}
}
