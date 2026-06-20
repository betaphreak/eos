package com.civstudio.io.printer;

import static com.civstudio.io.sink.ColumnSpec.*;

import com.civstudio.agent.Caravan;
import com.civstudio.agent.Agent;
import com.civstudio.agent.firm.ConsumerGoodFirm;
import com.civstudio.io.sink.ColumnSpec;
import com.civstudio.market.ConsumerGoodMarket;
import com.civstudio.settlement.Settlement;

/**
 * Tracks the colony's living consumer-good firms of <b>every sector</b> (enjoyment,
 * necessity). Each print cycle it writes one row per consumer sector (in {@link
 * Settlement#getConsumerGoodMarkets()} order), aggregating over <em>all firms of
 * that sector currently alive</em> read from {@link Settlement#getAgents()} — so it
 * follows the count as the ruler's dynamic firm provisioning charters and dissolves
 * firms. The rows are told apart by a <b>Good</b> column (the sector's product); a
 * sector with no living firm still gets a row (count 0).
 * <p>
 * One row per sector is written on the first day of each in-game month (see {@link
 * Printer#shouldPrint}). Columns: Date, Good, Count, TotalRevenue, TotalOutput,
 * TotalStock, TotalProfit, AvgUtilization, TotalLoan, TotalLaborCost,
 * TotalCapitalCost. {@code TotalStock} is the firms' unsold inventory of their
 * product (the necessity firms' food being what a collapsing colony hands to its
 * departing band — see {@link Caravan#dissolve}).
 */
public class FirmsPrinter extends Printer {

	/**
	 * Create a printer writing every consumer sector's firms, over the whole run.
	 *
	 * @param fileName name of the CSV output file
	 */
	public FirmsPrinter(String fileName) {
		super(fileName);
	}

	@Override
	public String tableName() {
		return "firms";
	}

	@Override
	public ColumnSpec[] columns() {
		return new ColumnSpec[] { date("Date"), text("Good"), integer("Count"),
				real("TotalRevenue"), real("TotalOutput"), real("TotalStock"),
				real("TotalProfit"), real("AvgUtilization"), real("TotalLoan"),
				real("TotalLaborCost"), real("TotalCapitalCost") };
	}

	/** Print one row per consumer sector, called by {@link Settlement#newDay()}. */
	public void print(Settlement colony) {
		if (!shouldPrint(colony))
			return;
		for (ConsumerGoodMarket mkt : colony.getConsumerGoodMarkets()) {
			String good = mkt.getGood();
			int count = 0;
			double totRevenue = 0;
			double totOutput = 0;
			double totStock = 0;
			double totProfit = 0;
			double totUtil = 0;
			double totLoan = 0;
			double totLaborCost = 0;
			double totCapitalCost = 0;

			for (Agent agent : colony.getAgents())
				if (agent instanceof ConsumerGoodFirm f && f.isAlive()
						&& f.getProductName().equals(good)) {
					count++;
					totRevenue += f.getRevenue();
					totOutput += f.getOutput();
					totStock += f.getStock();
					totProfit += f.getProfit();
					totUtil += f.getSmoothedUtilization();
					totLoan += f.getLoan();
					totLaborCost += f.getLaborCost();
					totCapitalCost += f.getCapitalCost();
				}
			double avgUtil = count > 0 ? totUtil / count : 0;

			sink.writeRow(colony.getDate(), good, count, totRevenue, totOutput,
					totStock, totProfit, avgUtil, totLoan, totLaborCost,
					totCapitalCost);
		}
	}
}
