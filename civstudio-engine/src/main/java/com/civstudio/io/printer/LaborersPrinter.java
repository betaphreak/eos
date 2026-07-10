package com.civstudio.io.printer;

import static com.civstudio.io.sink.ColumnSpec.*;

import com.civstudio.agent.Agent;
import com.civstudio.agent.laborer.Laborer;
import com.civstudio.io.sink.ColumnSpec;
import com.civstudio.settlement.Settlement;

/**
 * Tracks statistics of the colony's laborer population. Each row aggregates over
 * <em>all laborers currently alive in the colony</em> (read from {@link
 * Settlement#getAgents()} at print time), so it reflects the living population as
 * it changes — the founding cohort plus any replacement and immigrant households —
 * not a fixed initial set. Averages are over the living count.
 * <p>
 * Register with {@link Settlement#addPrinter} and finalize with {@link
 * Settlement#cleanUpPrinters}. Columns: Date, AvgWage, AvgTotalIncome,
 * AvgConsumption, AvgSavings, TotalSavings, AvgSavings_Rate, AvgNStock, AvgEStock,
 * AvgNConsumption, AvgEConsumption, AvgAge, Count.
 */
public class LaborersPrinter extends Printer {

	public LaborersPrinter(String fileName, int start, int end) {
		super(fileName, start, end);
	}

	public LaborersPrinter(String fileName, int start) {
		super(fileName, start);
	}

	public LaborersPrinter(String fileName) {
		super(fileName);
	}

	public LaborersPrinter(int start, int end) {
		super("laborers", start, end);
	}

	public LaborersPrinter(int start) {
		super("laborers", start);
	}

	public LaborersPrinter() {
		super("laborers");
	}

	@Override
	public String tableName() {
		return "laborers";
	}

	@Override
	public ColumnSpec[] columns() {
		return new ColumnSpec[] { date("Date"), real("AvgWage"),
				real("AvgTotalIncome"), real("AvgConsumption"), real("AvgSavings"),
				real("TotalSavings"), real("AvgSavings_Rate"), real("AvgNStock"),
				real("AvgEStock"), real("AvgNConsumption"), real("AvgEConsumption"),
				real("AvgAge"), integer("Count") };
	}

	/** Print data, called by Settlement at each time step. */
	public void print(Settlement colony) {
		if (!shouldPrint(colony))
			return;

		double avgWage = 0;
		double avgIC = 0;
		double avgConsumption = 0;
		double avgSavings = 0;
		double totSavings = 0;
		double avgSavingsRate = 0;
		double avgNStock = 0;
		double avgEStock = 0;
		double avgNConsumption = 0;
		double avgEConsumption = 0;
		double avgAge = 0;
		int count = 0;

		// aggregate over the living laborer population, which grows and is
		// replenished over the run (founders, replacements and immigrants)
		for (Agent agent : colony.getAgents())
			if (agent instanceof Laborer laborer) {
				avgWage += laborer.getWage();
				avgIC += laborer.getIncome();
				avgConsumption += laborer.getConsumption();
				totSavings += laborer.getSavings();
				avgSavingsRate += laborer.getSavingsRate();
				avgNStock += laborer.getGood("Necessity").getQuantity();
				avgEStock += laborer.getGood("Enjoyment").getQuantity();
				avgNConsumption += laborer.getNConsumption();
				avgEConsumption += laborer.getEConsumption();
				avgAge += laborer.getAgeYears();
				count++;
			}
		if (count > 0) {
			avgWage /= count;
			avgIC /= count;
			avgConsumption /= count;
			avgSavings = totSavings / count;
			avgSavingsRate /= count;
			avgNStock /= count;
			avgEStock /= count;
			avgNConsumption /= count;
			avgEConsumption /= count;
			avgAge /= count;
		}
		sink.writeRow(colony.getDate(), avgWage, avgIC, avgConsumption, avgSavings,
				totSavings, avgSavingsRate, avgNStock, avgEStock, avgNConsumption,
				avgEConsumption, avgAge, count);
	}
}
