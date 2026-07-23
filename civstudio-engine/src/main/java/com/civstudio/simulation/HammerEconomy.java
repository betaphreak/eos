package com.civstudio.simulation;

import com.civstudio.bank.Bank;

/**
 * The <b>build-economy calibration scenario</b> (docs/build-queue-plan.md B1): the
 * {@link CampFoundingEconomy} band with the <b>occupation choice</b> switched on — once
 * settled, each landed laborer household weighs selling labor at the center against
 * staying to work its home plot for <b>hammers + commerce</b> (reservation wage with an
 * optimism prior, a hysteresis band, and the unhired fallback). Hammers all donate to
 * the colony sink until B3 gives households housing projects; commerce is minted into
 * the household's copper account. The {@code Hammers} printer is this scenario's whole
 * point: plot/market/fallback day counts and the hammer/commerce flows are the
 * calibration instruments the acceptance bar reads.
 */
public class HammerEconomy {

	// the same founding site and band as CampFoundingEconomy, so the two runs differ in
	// exactly one flag — the contrast IS the experiment
	private static final int DHENIJANSAR = 4411;
	private static final int BAND_SIZE = 60;

	/**
	 * Build and run the simulation.
	 *
	 * @return the harness, exposing the constructed markets, bank and agents
	 */
	public static SimulationHarness run() {
		SimulationConfig cfg = SimulationConfig.DEFAULT.toBuilder()
				.foundAtCamp(true).homePlots(true).buildEconomy(true).build();
		SimulationHarness h = SimulationHarness.create(cfg, 7654321, DHENIJANSAR);
		h.tuneEconomy(e -> e.toBuilder().retinueSize(BAND_SIZE).build());
		h.foundStandardColony();
		// printers wire at the boot, as in CampFoundingEconomy (no ruler economy while a
		// camp), plus the build economy's hammer instrument
		h.setOnEconomyBooted(() -> {
			Bank bank = h.getCopperBank();
			h.addCommonPrinters();
			h.addBanksPrinter("Banks");
			h.addStrategicSectorPrinters("", bank);
			h.addGranaryPrinter("Granary");
			h.addPlotInventoryPrinters("");
			h.addHammerPrinter("Hammers");
		});
		h.run();
		return h;
	}

	public static void main(String[] args) {
		run();
	}
}
