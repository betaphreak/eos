package com.civstudio.simulation;

import java.util.ArrayList;
import java.util.List;

import com.civstudio.agent.RetinueConfig;
import com.civstudio.agent.laborer.Laborer;
import com.civstudio.settlement.Settlement;

/**
 * A calibration sweep for the open (pool-immigration) colony: it founds the same
 * standard colony as {@link OpenColonyEconomy} across a grid of the levers that decide
 * whether the peasant reserve can feed itself off the market — relief budget, larder
 * depth, founding necessity-firm count, and the province plot cap — runs each to a long
 * horizon, and reports whether the colony <b>held its population</b> or collapsed (and
 * when). It writes no CSVs; it prints one result row per scenario to stdout.
 * <p>
 * Not a simulation entry point in the usual sense — a developer tool, run via
 * {@code mvn exec:exec -Dsim.main=com.civstudio.simulation.CalibrationSweep}.
 */
public class CalibrationSweep {

	private static final long SEED = 7654322L;
	private static final int HORIZON_YEARS = 50;
	private static final int DHENIJANSAR = 4411; // the small (size-4) default province

	private record Scenario(int retinueSize, double reliefBudget, int bufferDays,
			int numNFirms) {
	}

	private record Result(boolean alive, int deathYear, int laborers, int firms) {
	}

	public static void main(String[] args) {
		// the pool levers (deep larder, generous relief budget) and founding food are
		// fixed at the values the first sweep showed monotonically best; this pass varies
		// colony scale (retinueSize → founding workforce) against the size-4 province's
		// fixed food cap to find a scale that reaches a stable equilibrium rather than
		// decaying to near-collapse.
		List<Scenario> grid = new ArrayList<>();
		for (int retinueSize : new int[] { 120, 240, 450, 900 })
			for (int numNFirms : new int[] { 4, 8 })
				for (double reliefBudget : new double[] { 3.0 })
					grid.add(new Scenario(retinueSize, reliefBudget, 150, numNFirms));

		System.out.println("retinue  nFirms  buffer  relief  ->  alive  deathYr  "
				+ "laborers  firms");
		for (Scenario s : grid) {
			Result r = run(s);
			System.out.printf(
					"%7d %6d %7d %6.1f  ->  %5s  %7s %9d %6d%n",
					s.retinueSize(), s.numNFirms(), s.bufferDays(), s.reliefBudget(),
					r.alive() ? "YES" : "no",
					r.alive() ? "-" : String.valueOf(r.deathYear()),
					r.laborers(), r.firms());
		}
	}

	private static Result run(Scenario s) {
		SimulationConfig cfg = SimulationConfig.DEFAULT.toBuilder()
				.durationYears(HORIZON_YEARS)
				.retinueSize(s.retinueSize())
				.numNFirms(s.numNFirms())
				.externalInflowPerStep(1000.0)
				.immigrationThreshold(100.0)
				.build();
		SimulationHarness h = SimulationHarness.create(cfg, SEED, DHENIJANSAR);
		h.setRetinueConfig(RetinueConfig.DEFAULT.toBuilder()
				.reliefBudgetPerPeasant(s.reliefBudget())
				.bufferDays(s.bufferDays())
				.build());
		// no printers — this is a throughput sweep, not a logged run
		h.foundStandardColony(i -> cfg.eFirm().savings(),
				i -> cfg.nFirm().savings(), i -> 15);
		Settlement c = h.getColony();
		c.run(cfg.numStep());

		int laborers = 0, firms = 0;
		for (var a : c.getAgents()) {
			if (!a.isAlive())
				continue;
			if (a instanceof Laborer)
				laborers++;
			else if (a instanceof com.civstudio.agent.firm.Firm)
				firms++;
		}
		int deathYear = c.isDead() ? c.getDeathDate().getYear() : -1;
		return new Result(c.isAlive(), deathYear, laborers, firms);
	}
}
