package com.civstudio.simulation;

import java.util.EnumMap;
import java.util.Map;

import com.civstudio.bank.Bank;
import com.civstudio.race.Race;

/**
 * Simulation (mixed-race case): the worked example of the {@link Race race} feature
 * (see {@code docs/race.md}). A colony <b>founded by the Harimari</b> — the tiger-folk
 * of <i>Anbennar</i> — with a mixed population: its ruler and the aristocracy it
 * raises are Harimari (the colony's founding race), while
 * the peasant pool it promotes its labor force from is rolled ~70/30 Harimari/human,
 * so laborers (and the nobles ennobled from them) are of both ancestries.
 * <p>
 * Being Harimari-founded, the colony keeps the <b>Harimari liturgical calendar</b>
 * ({@code /feasts-harimari.json}) and researches under the <b>Harimari tech-effect
 * overlay</b> ({@code /tech-effects-harimari.json}, which leans the colony's bonuses
 * toward export/scholarship and gives the naval techs nothing). Each person is named
 * from its own race's tables — Harimari from the South-Asian-flavoured given names and
 * the Anbennar clan surnames, humans from the human tables — ages on its race's life
 * table, and matures at its race's pace (the Harimari come of age younger).
 * <p>
 * The pool is sized modestly so the finite Harimari surname pool (278 names) is not
 * exhausted by promotions; otherwise this is a standard {@link
 * SimulationHarness#foundStandardColony founding sequence}, and like every pool colony
 * it collapses once the reserve drains.
 */
public class HarimariEconomy {

	/** Seed for the game session, so the whole run is reproducible. */
	static final long SEED = 7654321L;

	/**
	 * Build and run the simulation.
	 *
	 * @return the harness, exposing the constructed markets, banks and agents
	 */
	public static SimulationHarness run() {
		// a modest pool: ~0.45 of 200 peasants are promoted to laborers, ~70% of them
		// Harimari, so the Harimari living-household count stays well under the 278-name
		// Harimari surname pool even as deaths churn it
		SimulationConfig cfg = SimulationConfig.DEFAULT.toBuilder()
				.settlementName("Sehir")
				.retinueSize(200)
				.promotionRatio(0.45)
				.build();
		Map<Race, Double> mix = new EnumMap<>(Race.class);
		mix.put(Race.HARIMARI, 0.7);
		mix.put(Race.HUMAN, 0.3);

		SimulationHarness h =
				SimulationHarness.create(cfg, SEED, Race.HARIMARI, mix);
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
