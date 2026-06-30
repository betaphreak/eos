package com.civstudio.io.printer;

import static com.civstudio.io.sink.ColumnSpec.date;
import static com.civstudio.io.sink.ColumnSpec.integer;
import static com.civstudio.io.sink.ColumnSpec.text;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import com.civstudio.io.sink.ColumnSpec;
import com.civstudio.settlement.Plot;
import com.civstudio.settlement.Settlement;

/**
 * A running <b>inventory</b> of a colony's claimed plots — what land it holds and
 * how much of it is developed — tallied by category each print cycle. It walks the
 * colony's plots ({@link Settlement#getPlots()}) and emits one row per distinct
 * <b>terrain</b>, <b>relief</b> (flat/hill/peak), <b>feature</b> (forest/jungle/
 * flood-plains) and <b>bonus</b> (resource) present, told apart by a {@code
 * Category} + {@code Type} column the way {@link BanksPrinter}/{@link PricesPrinter}
 * consolidate their rows. The {@code Count} is how many of the colony's plots carry
 * that type; {@code Developed} how many of those have had an improvement raised.
 * <p>
 * Because each colony reports <em>its own</em> claimed plots, a colony sharing a
 * province with another (see {@code docs/province-plots.md}) shows only its share;
 * the multi-colony output merge prefixes a {@code Settlement} column, so two
 * settlements' holdings of one province appear side by side. Terrain and relief are
 * always present, so every type is listed; a plot with no feature or no bonus
 * contributes no row to those categories. One tally per the first day of each
 * in-game month (see {@link Printer#shouldPrint}). Columns: Date, Category, Type,
 * Count, Developed.
 */
public class ProvinceInventoryPrinter extends Printer {

	/**
	 * Create a printer tallying the colony's plot inventory, over the whole run.
	 *
	 * @param fileName name of the CSV output file
	 */
	public ProvinceInventoryPrinter(String fileName) {
		super(fileName);
	}

	@Override
	public String tableName() {
		return "inventory";
	}

	@Override
	public ColumnSpec[] columns() {
		return new ColumnSpec[] { date("Date"), text("Category"), text("Type"),
				integer("Count"), integer("Developed") };
	}

	/** Tally the colony's plots by category, called by {@link Settlement#newDay()}. */
	public void print(Settlement colony) {
		if (!shouldPrint(colony))
			return;
		LocalDate date = colony.getDate();
		List<Plot> plots = colony.getPlots();
		tally(date, "Terrain", plots, p -> p.terrain().type());
		tally(date, "Relief", plots, p -> p.plotType().name());
		tally(date, "Feature", plots, p -> p.feature() == null ? null : p.feature().type());
		tally(date, "Bonus", plots, p -> p.bonus() == null ? null : p.bonus().type());
	}

	// count the colony's plots by a category key (skipping null keys — e.g. a plot with
	// no feature), with a developed sub-count, and write one row per distinct type
	private void tally(LocalDate date, String category, List<Plot> plots, Function<Plot, String> key) {
		Map<String, int[]> counts = new LinkedHashMap<>(); // type -> [count, developed]
		for (Plot p : plots) {
			String type = key.apply(p);
			if (type == null)
				continue;
			int[] c = counts.computeIfAbsent(type, k -> new int[2]);
			c[0]++;
			if (p.improvement() != null)
				c[1]++;
		}
		for (Map.Entry<String, int[]> e : counts.entrySet())
			sink.writeRow(date, category, e.getKey(), e.getValue()[0], e.getValue()[1]);
	}
}
