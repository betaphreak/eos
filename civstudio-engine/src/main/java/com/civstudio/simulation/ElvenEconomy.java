package com.civstudio.simulation;

import java.util.EnumMap;
import java.util.Map;

import com.civstudio.bank.Bank;
import com.civstudio.race.Race;

/**
 * Simulation (Anbennar founding race): a colony <b>founded by the {@link Race#ELVEN
 * elves} of <i>Anbennar</i></b>, the worked example of founding with one of the
 * imported Anbennar races (see {@code docs/race.md}). Unlike the mixed-race {@link
 * HarimariEconomy}, this colony is <b>mono-racially elven</b>: its founding race and
 * its entire generated population (the peasant pool, founding draws, immigrants) are
 * elven, so the ruler, the aristocracy it ennobles, and every laborer are elves.
 * <p>
 * Each person is named from the Anbennar elven name tables ({@code /names/elven/}).
 * The elves ship no {@code /feasts-elven.json} or {@code /tech-effects-elven.json},
 * so the colony <b>falls back to the human liturgical calendar and tech overlay</b>
 * — the documented per-race resource fallback. Elves age on the (placeholder) human
 * life table but mature later (a working-age floor of 20).
 * <p>
 * The pool is sized modestly so the finite elven surname pool (228 names) is not
 * exhausted by promotions ({@code round(0.45 * 200) ≈ 90} living households stay well
 * under it even as deaths churn the pool); otherwise this is a standard {@link
 * SimulationHarness#foundStandardColony founding sequence}, and like every pool colony
 * it collapses once the reserve drains.
 */
public class ElvenEconomy {

	/** Seed for the game session, so the whole run is reproducible. */
	static final long SEED = 31415927L;

	/**
	 * Build and run the simulation.
	 *
	 * @return the harness, exposing the constructed markets, banks and agents
	 */
	public static SimulationHarness run() {
		// a modest pool: ~0.45 of 200 peasants are promoted to laborers, all elven,
		// so the living-household count stays well under the 228-name elven surname pool
		SimulationConfig cfg = SimulationConfig.DEFAULT.toBuilder()
				.settlementName("Aelvar")
				.retinueSize(200)
				.promotionRatio(0.45)
				.build();
		Map<Race, Double> mix = new EnumMap<>(Race.class);
		mix.put(Race.ELVEN, 1.0);

		SimulationHarness h =
				SimulationHarness.create(cfg, SEED, Race.ELVEN, mix);
		h.foundStandardColony(
				i -> cfg.eFirm().savings(), i -> cfg.nFirm().savings(), i -> 15);
		Bank bank = h.getCopperBank();
		h.addCommonPrinters();
		h.addBanksPrinter("Banks");
		h.addStrategicSectorPrinters("", bank);
		h.run();
		return h;
	}

	public static void main(String[] args) {
		run();
	}
}
