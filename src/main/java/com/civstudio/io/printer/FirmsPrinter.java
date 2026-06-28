package com.civstudio.io.printer;

import static com.civstudio.io.sink.ColumnSpec.*;

import java.util.function.ToDoubleFunction;

import com.civstudio.agent.Agent;
import com.civstudio.agent.firm.BuilderFirm;
import com.civstudio.agent.firm.CFirm;
import com.civstudio.agent.firm.ConsumerGoodFirm;
import com.civstudio.agent.firm.Firm;
import com.civstudio.agent.firm.ScienceFirm;
import com.civstudio.agent.firm.StrategicFirm;
import com.civstudio.io.sink.ColumnSpec;
import com.civstudio.market.ConsumerGoodMarket;
import com.civstudio.settlement.Settlement;

/**
 * Tracks <b>every kind of firm</b> in the colony, consolidated into one CSV the way
 * {@code BanksPrinter} consolidates the banks: each print cycle it writes one row per
 * firm <b>type</b>, told apart by a <b>Type</b> column, aggregating over all living
 * firms of that type read from {@link Settlement#getAgents()} (so the count follows
 * the ruler's dynamic provisioning as it charters and dissolves firms). The types:
 * <ul>
 * <li>each <b>consumer sector</b> — {@code Enjoyment}, {@code Necessity} (in {@link
 * Settlement#getConsumerGoodMarkets()} order); a sector with no living firm still
 * gets a count-0 row;</li>
 * <li><b>Capital</b> — the machine-making {@link CFirm}s;</li>
 * <li><b>Strategic</b> — the noble-staffed export {@link StrategicFirm};</li>
 * <li><b>Science</b> — the scholar-staffed {@link ScienceFirm} (its output the
 * research points it delivers to the colony's research);</li>
 * <li><b>Builder</b> — the {@link BuilderFirm} that grows the colony.</li>
 * </ul>
 * Only the types the colony actually has produce a row (a colony with no export
 * sector writes no {@code Strategic} row, etc.).
 * <p>
 * The columns are a <b>common firm-finance schema</b> all firm types share (read from
 * the {@link Firm} base): Date, Type, Count, TotalRevenue, TotalOutput, TotalStock,
 * TotalProfit, AvgUtilization, TotalLoan, TotalLaborCost, TotalCapitalCost.
 * {@code TotalOutput} is each type's own product — units of the consumer good, machines,
 * exports, or build-units. {@code TotalStock} (unsold inventory) and {@code
 * AvgUtilization} are meaningful only for the consumer firms (the necessity firms' stock
 * is the food a collapsing colony hands its departing band — see {@code Caravan.dissolve});
 * the other types report {@code 0} for both. The genuinely type-specific detail — the
 * export firm's shipped/earned totals and the builder's size/slots/build-ring progress —
 * lives with the colony's research in the consolidated {@code Services.csv}
 * ({@code ServicesPrinter}).
 * <p>
 * One row per present type is written on the first day of each in-game month (see {@link
 * Printer#shouldPrint}).
 */
public class FirmsPrinter extends Printer {

	/**
	 * Create a printer writing every firm type, over the whole run.
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
		return new ColumnSpec[] { date("Date"), text("Type"), integer("Count"),
				real("TotalRevenue"), real("TotalOutput"), real("TotalStock"),
				real("TotalProfit"), real("AvgUtilization"), real("TotalLoan"),
				real("TotalLaborCost"), real("TotalCapitalCost") };
	}

	/** Print one row per firm type, called by {@link Settlement#newDay()}. */
	public void print(Settlement colony) {
		if (!shouldPrint(colony))
			return;

		// the consumer sectors: one row each (count-0 preserved), with stock and
		// utilization, told apart by the product name
		for (ConsumerGoodMarket mkt : colony.getConsumerGoodMarkets()) {
			String product = mkt.getGood();
			int count = 0;
			double revenue = 0, output = 0, stock = 0, profit = 0, util = 0,
					loan = 0, laborCost = 0, capitalCost = 0;
			for (Agent a : colony.getAgents())
				if (a instanceof ConsumerGoodFirm f && f.isAlive()
						&& f.getProductName().equals(product)) {
					count++;
					revenue += f.getRevenue();
					output += f.getOutput();
					stock += f.getStock();
					profit += f.getProfit();
					util += f.getSmoothedUtilization();
					loan += f.getLoan();
					laborCost += f.getLaborCost();
					capitalCost += f.getCapitalCost();
				}
			double avgUtil = count > 0 ? util / count : 0;
			sink.writeRow(colony.getDate(), product, count, revenue, output, stock,
					profit, avgUtil, loan, laborCost, capitalCost);
		}

		// the other production firms, each mapping output to its own product; they
		// carry no consumer-style stock or utilization (0). A row only if present.
		writeType(colony, "Capital", CFirm.class, Firm::getOutput);
		writeType(colony, "Strategic", StrategicFirm.class,
				f -> ((StrategicFirm) f).getExported());
		writeType(colony, "Science", ScienceFirm.class, Firm::getOutput);
		writeType(colony, "Builder", BuilderFirm.class,
				f -> ((BuilderFirm) f).getBuildUnitsDelivered());
	}

	// aggregate every living firm of one type into a single row (no row if the colony
	// has none); outputFn maps a firm to its own notion of output this step
	private void writeType(Settlement colony, String type, Class<? extends Firm> cls,
			ToDoubleFunction<Firm> outputFn) {
		int count = 0;
		double revenue = 0, output = 0, profit = 0, loan = 0, laborCost = 0,
				capitalCost = 0;
		for (Agent a : colony.getAgents())
			if (cls.isInstance(a) && a.isAlive()) {
				Firm f = (Firm) a;
				count++;
				revenue += f.getRevenue();
				output += outputFn.applyAsDouble(f);
				profit += f.getProfit();
				loan += f.getLoan();
				laborCost += f.getLaborCost();
				capitalCost += f.getCapitalCost();
			}
		if (count == 0)
			return;
		sink.writeRow(colony.getDate(), type, count, revenue, output, 0.0, profit,
				0.0, loan, laborCost, capitalCost);
	}
}
