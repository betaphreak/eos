package com.civstudio.simulation;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import com.civstudio.agent.Agent;
import com.civstudio.agent.Member;
import com.civstudio.agent.laborer.Laborer;
import com.civstudio.settlement.Settlement;

/**
 * Developer experiment (not a scenario): the §6 success-criteria probe for the food
 * redesign (see {@code docs/granary.md}). Runs an <b>open</b> (pool-immigration) colony —
 * the one configuration whose workforce does not decline from day one — over a long
 * horizon with all of Phases 1–3 active (granary, pool/child relief, granary-funded
 * fission), and snapshots the renewal signals each year, to answer: does the colony now
 * survive past child-maturity (~16 y) and does the <b>fission valve engage</b> (home-grown
 * households forming), or does it still collapse first — and if so, where?
 *
 * <p>Run: {@code mvn exec:exec -Dsim.main=com.civstudio.simulation.SurvivalExperiment}.
 */
public class SurvivalExperiment {

	private static final int DHENIJANSAR = 4411;
	private static final int HORIZON_YEARS = 50;

	public static void main(String[] args) {
		// the open colony of OpenColonyEconomy (steady inflow refilling the peasant pool),
		// but over a long horizon and instrumented yearly
		SimulationConfig cfg = SimulationConfig.DEFAULT.toBuilder()
				.durationYears(HORIZON_YEARS)
				.externalInflowPerStep(OpenColonyEconomy.EXTERNAL_INFLOW_PER_STEP)
				.immigrationThreshold(OpenColonyEconomy.IMMIGRATION_THRESHOLD)
				.build();
		SimulationHarness h = SimulationHarness.create(cfg, 7654322, DHENIJANSAR);
		h.foundStandardColony(i -> cfg.eFirm().savings(), i -> cfg.nFirm().savings(),
				i -> 15);

		Settlement colony = h.getColony();
		List<String> snapshots = new ArrayList<>();
		colony.addStepAction(() -> {
			LocalDate d = colony.getDate();
			if (d.getDayOfMonth() == 1 && d.getMonthValue() == 1)
				snapshots.add(snapshot(h, colony, d));
		});

		h.run();

		System.out.println(
				"year   labs  child marri pool  pAdult granary  nPrice fissions");
		for (String s : snapshots)
			System.out.println(s);
		System.out.println("--");
		System.out.printf("founded %s, %s after %s, fissions=%d%n", cfg.startDate(),
				colony.isDead() ? "DIED " + colony.getDeathDate() : "ALIVE @ end",
				colony.isDead()
						? (colony.getDeathDate().getYear() - cfg.startDate().getYear())
								+ "y"
						: HORIZON_YEARS + "y",
				h.getFissionCount());
	}

	// one yearly row: living laborer households, the children among them, how many
	// households are married (size > 1 with an adult spouse), the peasant pool size, the
	// granary's stock, cumulative fissions, and a crude cumulative births proxy
	private static String snapshot(SimulationHarness h, Settlement colony, LocalDate d) {
		int labs = 0, children = 0, married = 0;
		for (Agent a : colony.getAgents()) {
			if (!(a instanceof Laborer l) || !l.isAlive())
				continue;
			labs++;
			int adults = 0;
			for (Member m : l.getMembers()) {
				if (m.isAdult(d))
					adults++;
				else
					children++;
			}
			if (adults > 1)
				married++;
		}
		int pool = h.getRetinue() != null ? h.getRetinue().size() : 0;
		// promotable peasants = pool adults (children cannot replace a dead laborer); if
		// this hits 0 while the pool is non-empty, replacement fails on a child-only reserve
		int poolAdults = h.getRetinue() != null
				? pool - h.getRetinue().childCount(d) : 0;
		double granary = h.getGranary() != null ? h.getGranary().getStock() : 0;
		double nPrice = ((com.civstudio.market.ConsumerGoodMarket) colony
				.getMarket("Necessity")).getLastMktPrice();
		return String.format("%-6d %-5d %-5d %-5d %-5d %-6d %-8.0f %-6.2f %-8d",
				d.getYear(), labs, children, married, pool, poolAdults, granary, nPrice,
				h.getFissionCount());
	}
}
