package com.civstudio.io.printer;

import static com.civstudio.io.sink.ColumnSpec.*;

import com.civstudio.agent.Retinue;
import com.civstudio.io.sink.ColumnSpec;
import com.civstudio.settlement.Settlement;

/**
 * Writes a time-series of the {@link Retinue}: how many peasants remain, their
 * average skill and age, the food it carries in its larder, the necessity it ate
 * and the number that starved last step, and the cumulative relief cost billed to
 * the Ruler. Register with {@link Settlement#addPrinter} and finalize with {@link
 * Settlement#cleanUpPrinters}.
 * <p>
 * Columns: Date, Count, AvgSkill, AvgAge, Larder (necessity the pool holds — what a
 * collapsing colony's abandoned food stores fold into), Consumed, Starved,
 * BilledTotal, Imported (cumulative gold-funded immigrants recruited into the pool).
 */
public class RetinuePrinter extends Printer {

	private final Retinue retinue;

	/**
	 * Create a new {@code RetinuePrinter}.
	 *
	 * @param fileName name of the CSV output file
	 * @param retinue  the peasant pool to report on
	 */
	public RetinuePrinter(String fileName, Retinue retinue) {
		super(fileName);
		this.retinue = retinue;
	}

	@Override
	public String tableName() {
		return "retinue";
	}

	@Override
	public ColumnSpec[] columns() {
		return new ColumnSpec[] { date("Date"), integer("Count"), real("AvgSkill"),
				real("AvgAge"), real("Larder"), real("Consumed"), integer("Starved"),
				real("BilledTotal"), integer("Imported") };
	}

	@Override
	public void print(Settlement colony) {
		if (!shouldPrint(colony))
			return;
		sink.writeRow(colony.getDate(), retinue.size(), retinue.avgSkill(),
				retinue.avgAgeYears(), retinue.getLarder(), retinue.getLastConsumed(),
				retinue.getLastStarved(), retinue.getTotalBilledToRuler(),
				retinue.getImmigrantCount());
	}
}
