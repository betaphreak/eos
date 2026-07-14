package com.civstudio.io.printer;

import static com.civstudio.io.sink.ColumnSpec.*;

import java.util.function.Supplier;

import com.civstudio.agent.ExpeditionStats;
import com.civstudio.io.sink.ColumnSpec;
import com.civstudio.settlement.Settlement;

/**
 * Writes a monthly time-series of the colony's <b>explorer-expedition activity</b> — the renewal
 * loop of {@code docs/explorer-caravan.md}: how many foraging bands are out, how many have been
 * mustered and have returned over the run, and the split of the return reward — households founded
 * from returned peasants, returnees ennobled to lead (when no abler noble existed), and returns an
 * existing abler noble led (so no new noble was minted). The last two together diagnose whether the
 * aristocracy is ballooning from expedition ennoblement.
 * <p>
 * Reads a {@link ExpeditionStats} snapshot supplied by {@code SimulationHarness.expeditionStats()}
 * (so it stays independent of the mustering/reward machinery). Register with {@link
 * Settlement#addPrinter} and finalize with {@link Settlement#cleanUpPrinters}. A City colony fills
 * these in; a Village (or a bare sim that musters none) writes all-zero rows.
 * <p>
 * Columns: Date, Out (bands in flight), Mustered, Returned, Founded, Ennobled, NobleLed.
 */
public class ExpeditionsPrinter extends Printer {

	private final Supplier<ExpeditionStats> stats;

	/**
	 * Create a new {@code ExpeditionsPrinter}.
	 *
	 * @param fileName name of the CSV output file
	 * @param stats    supplier of the colony's current expedition tallies
	 */
	public ExpeditionsPrinter(String fileName, Supplier<ExpeditionStats> stats) {
		super(fileName);
		this.stats = stats;
	}

	@Override
	public String tableName() {
		return "expeditions";
	}

	@Override
	public ColumnSpec[] columns() {
		return new ColumnSpec[] { date("Date"), integer("Out"), integer("Mustered"),
				integer("Returned"), integer("Founded"), integer("Ennobled"),
				integer("NobleLed") };
	}

	@Override
	public void print(Settlement colony) {
		if (!shouldPrint(colony))
			return;
		ExpeditionStats s = stats.get();
		sink.writeRow(colony.getDate(), s.out(), s.mustered(), (int) s.returns(),
				(int) s.founded(), (int) s.ennobled(), (int) s.nobleLed());
	}
}
