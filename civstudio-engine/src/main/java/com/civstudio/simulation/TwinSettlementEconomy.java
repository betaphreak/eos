package com.civstudio.simulation;

import java.util.List;

import com.civstudio.bank.Bank;
import com.civstudio.geo.Province;
import com.civstudio.io.SimLog;
import com.civstudio.settlement.GameSession;
import com.civstudio.settlement.Settlement;

/**
 * Simulation (two settlements in <b>one</b> province): the worked example of
 * several colonies sharing a single province's land. <b>Upper</b> and <b>Lower</b>
 * are both founded into <b>Dhenijansar</b> ({@code province_id} 4411, the default
 * colony's coastal Rahen province, 74 plots), from one {@link GameSession}, and
 * run <b>concurrently — one thread each — in lockstep</b> (see {@link
 * SessionRunner}). Each is a fully independent colony (its own ruler, peasant
 * pool, firms, banks and economy, on its own per-colony random stream), but the
 * two <b>share the province's plot field</b>: they claim from one
 * {@link com.civstudio.settlement.ProvincePlotPool}, each anchored at a
 * <b>min-distance-spaced</b> founding center so they occupy distinct regions, and
 * <b>compete for the 74 plots</b> until the pool runs dry and growth stops (see
 * {@code docs/province-plots.md}). This replaces the former two-province
 * {@code HanseaticEconomy} as the multi-colony worked example.
 * <p>
 * Both are standard colonies ({@link SimulationHarness#foundStandardColony}) at
 * the default scale, so like every ruler-bearing colony each founds and replaces
 * its labor force from a finite peasant pool and spirals to collapse once the
 * reserve drains — here sooner, as two full colonies crowd one small province.
 * They share the session's name pool and demography (disjoint surname slices) but
 * each has its own economic stream, so the run stays reproducible per colony. The
 * two write to the same {@code output/} directory, their CSVs prefixed
 * {@code Upper-}/{@code Lower-}; the {@link SimLog} date source is per-thread, so
 * each logs its own in-game date with no cross-talk.
 */
public class TwinSettlementEconomy {

	/** Seed for the shared game session, so the whole run is reproducible. */
	static final long SEED = 7654321L;

	/** The province both settlements share — Dhenijansar (74 plots), the default colony's home. */
	static final int DHENIJANSAR = 4411;

	/**
	 * Build and run both settlements, returning the first ({@code Upper}) harness as
	 * the convention hook; both colonies are fully built and run.
	 *
	 * @return the Upper harness (Lower is also built and run on its own thread)
	 */
	public static SimulationHarness run() {
		GameSession session = new GameSession(SEED);
		Province dhenijansar = session.getWorldMap().province(DHENIJANSAR);

		SimulationConfig upperCfg = SimulationConfig.DEFAULT.toBuilder()
				.settlementName("Upper").build();
		SimulationConfig lowerCfg = SimulationConfig.DEFAULT.toBuilder()
				.settlementName("Lower").build();

		// both founded into the same province (Dhenijansar): they share its plot field
		Settlement upper = session.newSettlement(upperCfg.settlementName(),
				upperCfg.startDate(), upperCfg.meanInitAgeYears(), upperCfg.targetNStock(),
				upperCfg.meanSkillMale(), upperCfg.meanSkillFemale(), dhenijansar);
		Settlement lower = session.newSettlement(lowerCfg.settlementName(),
				lowerCfg.startDate(), lowerCfg.meanInitAgeYears(), lowerCfg.targetNStock(),
				lowerCfg.meanSkillMale(), lowerCfg.meanSkillFemale(), dhenijansar);

		// install the log handler and bind this thread to Upper before building it
		SimLog.init(upper);
		SimulationHarness hUpper = build(upperCfg, upper, "Upper-");
		// bind to Lower while building it, so its construction-time records carry it
		SimLog.bind(lower);
		SimulationHarness hLower = build(lowerCfg, lower, "Lower-");

		// run both colonies concurrently — one thread each — in lockstep
		SessionRunner.runConcurrently(List.of(hUpper, hLower));

		return hUpper;
	}

	/**
	 * Populate one settlement as a standard colony (default tiered banking, the
	 * consumer/capital/export firms, ruler + gold treasury, peasant pool, and the
	 * labor force promoted from it) and add its name-prefixed printers. The colony
	 * is not yet run.
	 *
	 * @param cfg    this settlement's configuration
	 * @param colony the colony to populate (already created from the session)
	 * @param prefix the per-settlement CSV filename prefix (e.g. {@code "Upper-"})
	 * @return the populated harness
	 */
	private static SimulationHarness build(SimulationConfig cfg, Settlement colony, String prefix) {
		SimulationHarness h = new SimulationHarness(cfg, colony);
		h.foundStandardColony();
		Bank copper = h.getCopperBank();
		h.addCommonPrinters(prefix);
		h.addBanksPrinter(prefix + "Banks");
		h.addStrategicSectorPrinters(prefix, copper);
		h.addGranaryPrinter(prefix + "Granary");
		h.addPlotInventoryPrinters(prefix);
		return h;
	}

	public static void main(String[] args) {
		run();
	}
}
