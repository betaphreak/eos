package com.civstudio.simulation;

import com.civstudio.bank.Bank;

/**
 * The <b>found-at-Camp</b> scenario (docs/settlement-tier-ladder-plan.md Phase D): a <b>small
 * caravan band</b> settles a site as a {@link com.civstudio.settlement.SettlementTier#CAMP camp}
 * led by a {@link com.civstudio.agent.Captain Captain}, forages the land, and climbs the tier
 * ladder on its food surplus — booting the full ruler economy (ruler, banks, firms, a labour force
 * promoted from the pool) when it reaches {@link com.civstudio.settlement.SettlementTier#SMALLHOLDING}.
 * <p>
 * This is the live geographic counterpart to {@link HomogeneousEconomy} (which founds mature, at
 * its site ceiling, as a steady-state economic probe). Where Homogeneous seeds a city's worth of
 * people at once, this seeds a <em>band</em> — a few dozen foragers — so the climb is gradual and
 * the settled economy grows out of a genuine camp rather than appearing fully formed. Delegated to
 * {@link SimulationHarness}; this class supplies the small band size, the {@code foundAtCamp} opt-in,
 * and the printers (wired at the boot, since the ruler/firms/banks do not exist while it is a camp).
 */
public class CampFoundingEconomy {

	// the default founding province: Dhenijansar (province_id 4411), the same site Homogeneous
	// uses — here a small band settles it and grows, rather than founding it fully peopled.
	private static final int DHENIJANSAR = 4411;

	// a founding BAND, not a colony's worth of people: a few dozen foragers who settle a camp and
	// grow it. (Homogeneous seeds ~900 — a city's population — which is not a camp.)
	private static final int BAND_SIZE = 60;

	/**
	 * Build and run the simulation.
	 *
	 * @return the harness, exposing the constructed markets, bank and agents
	 */
	public static SimulationHarness run() {
		// a small band founding at CAMP: the pooled foragers ARE the workforce until the camp
		// climbs to SMALLHOLDING and boots the ruler economy (foundStandardColony -> foundCamp).
		// homePlots: once settled, each laborer household farms its own home plot for subsistence
		// food (the plot-working economy — docs/plot-working-plan.md P1), so survival is decoupled
		// from the market and the small booted colony is viable on its own land.
		SimulationConfig cfg = SimulationConfig.DEFAULT.toBuilder()
				.retinueSize(BAND_SIZE).foundAtCamp(true).homePlots(true).build();
		SimulationHarness h = SimulationHarness.create(cfg, 7654321, DHENIJANSAR);
		h.foundStandardColony();
		// the printers report the ruler/firms/banks/granary/plots — none of which exist while the
		// colony is a foraging camp — so wire them when the ruler economy boots at SMALLHOLDING.
		h.setOnEconomyBooted(() -> {
			Bank bank = h.getCopperBank();
			h.addCommonPrinters();
			h.addBanksPrinter("Banks");
			h.addStrategicSectorPrinters("", bank);
			h.addGranaryPrinter("Granary");
			h.addPlotInventoryPrinters("");
		});
		h.run();
		return h;
	}

	public static void main(String[] args) {
		run();
	}
}
