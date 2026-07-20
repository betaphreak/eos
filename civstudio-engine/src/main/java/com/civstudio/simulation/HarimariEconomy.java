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
 * A standard {@link SimulationHarness#foundStandardColony founding sequence} at the
 * standard pool scale — and like every pool colony it collapses once the reserve drains.
 * <p>
 * It used to found a deliberately small pool, because promoting a full one drew more
 * distinct surnames than the imported Harimari list holds (278) and the colony died
 * mid-founding on an exhausted dynasty pool. The pool now wraps instead of refusing, so
 * the scenario founds at normal scale; four hundred medieval households not holding four
 * hundred distinct surnames is the realistic outcome anyway.
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
		// base on the race's own economy column, so an authored Harimari tuning reaches this
		// scenario without touching it. Nothing is tuned on top: the colony founds at the
		// standard pool scale (900 peasants -> ~405 laborer households), which draws ~284
		// Harimari surnames against a 278-name pool — fine since the dynasty pool wraps
		SimulationConfig cfg = SimulationConfig.defaultFor(Race.HARIMARI).toBuilder()
				.settlementName("Sehir")
				.build();
		Map<Race, Double> mix = new EnumMap<>(Race.class);
		mix.put(Race.HARIMARI, 0.7);
		mix.put(Race.HUMAN, 0.3);

		SimulationHarness h =
				SimulationHarness.create(cfg, SEED, Race.HARIMARI, mix);
		h.foundStandardColony();
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
