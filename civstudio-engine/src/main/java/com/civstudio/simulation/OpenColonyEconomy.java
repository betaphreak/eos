package com.civstudio.simulation;

import com.civstudio.bank.Bank;

/**
 * Simulation (open colony, pool immigration): the same standard ruler-bearing colony
 * as {@link HomogeneousEconomy} — founded through the peasant pool, into Dhenijansar —
 * but <b>opened</b> so the pool is refilled from outside the colony. A <i>closed</i>
 * pool colony spirals to collapse once its founding reserve drains: with no inflow, the
 * peasants promoted into dead laborers' places are never replaced, the workforce
 * shrinks from the start, food production falls, and the colony dies within a few years
 * (see {@code docs/peasant-pool.md} and the collapsing {@link HomogeneousEconomy}). Here
 * a steady external inflow recruits settlers into the pool toward a modest standing
 * reserve ({@link SimulationHarness#enableExternalInflow} → {@code
 * enablePoolImmigration}), so promotion always finds a replacement: the colony holds a
 * <b>full, stable workforce for years</b> (the firm sector growing the while) rather
 * than declining from day one — the demonstration that pool immigration sustains a
 * colony far longer than the closed {@code HomogeneousEconomy}.
 * <p>
 * It does <b>not</b> yet survive indefinitely: the founding cohort, drawn at clustered
 * ages, reaches old age together after ~8–10 years, and that single death <b>echo</b>
 * outruns the refill and tips the colony into collapse. Smoothing that echo — a wider
 * founding-age spread, or genuine births renewing the population continuously — is the
 * next step beyond immigration; see the discussion in {@code docs/peasant-pool.md}.
 */
public class OpenColonyEconomy {

	// the default founding province, as HomogeneousEconomy: Dhenijansar (province_id
	// 4411), a small coastal LAND province whose plots cap the colony at size 4
	private static final int DHENIJANSAR = 4411;

	// the recruitment budget per step, sized so the pool can be refilled faster than any
	// plausible death wave drains it: at IMMIGRATION_THRESHOLD per settler this funds up
	// to EXTERNAL_INFLOW_PER_STEP / IMMIGRATION_THRESHOLD = 10 settlers/step. A slower
	// rate lets a demographic echo (the founding cohort dying together) outrun the refill
	// and collapse the colony even though the steady-state load is sustainable. The
	// reserve target caps the total, so the high rate only matters while refilling.
	/** Net new money entering the colony each step — the pool-recruitment budget. */
	static final double EXTERNAL_INFLOW_PER_STEP = 1000.0;

	/** Recruitment budget spent per settler drawn into the pool. */
	static final double IMMIGRATION_THRESHOLD = 100.0;

	/**
	 * Build and run the simulation.
	 *
	 * @return the harness, exposing the constructed markets, banks and agents
	 */
	public static SimulationHarness run() {
		// the closed default, opened: a steady inflow refills the peasant pool toward its
		// founding reserve, so the reserve never empties and the labor force is always
		// replaced by promotion — the colony holds its population instead of collapsing
		SimulationConfig cfg = SimulationConfig.DEFAULT.toBuilder()
				.externalInflowPerStep(EXTERNAL_INFLOW_PER_STEP)
				.immigrationThreshold(IMMIGRATION_THRESHOLD)
				.build();
		// a distinct seed from HomogeneousEconomy so the two runs' seed-scoped output
		// (output/<seed>/) does not collide
		SimulationHarness h = SimulationHarness.create(cfg, 7654322, DHENIJANSAR);
		// the same standard founding as HomogeneousEconomy; foundStandardColony wires the
		// external inflow (here, pool immigration) since the config opens the colony
		h.foundStandardColony();
		Bank bank = h.getCopperBank();
		h.addCommonPrinters();
		h.addBanksPrinter("Banks");
		h.addStrategicSectorPrinters("", bank);
		h.addRetinuePrinter("Retinue");
		h.run();
		return h;
	}

	public static void main(String[] args) {
		run();
	}
}
