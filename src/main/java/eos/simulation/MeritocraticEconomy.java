package eos.simulation;

import java.time.LocalDate;

import eos.bank.Bank;

/**
 * Simulation (worked example of pool <b>promotion</b> — Phase 3 of the peasant
 * pool, see {@code docs/peasant-pool.md}): a small colony whose dead laborers are
 * replaced not by same-dynasty heirs but by the <b>ruler promoting the ablest
 * peasant</b> out of the {@link eos.agent.PeasantPool} into a fresh laborer
 * household (merit-based social mobility). The promoted peasant keeps its name,
 * skills and age, takes a new dynasty surname, and is capitalized by the ruler.
 * <p>
 * With no inflow into the pool yet (the dependents/reproduction refill is a later
 * phase), the reserve only drains — by promotion and by old age — so the labor
 * force declines once it runs dry. This run is sized and dated so promotion is the
 * dominant replacement while the colony stays alive: a generous reserve over a
 * short horizon. It is the only bundled sim that turns on
 * {@code promoteLaborersFromPool}.
 */
public class MeritocraticEconomy {

	/**
	 * Peasants seeded into the pool — the reserve promotions draw from. A balance:
	 * large enough to absorb the run's deaths so promotion can sustain the labor
	 * force for the (short) horizon, but small relative to the labor force, since the
	 * pool's mouths compete with laborers for necessity. The reserve only drains (by
	 * promotion and old age); once dry, deaths go unreplaced, the workforce shrinks,
	 * food production falls, and the colony spirals to collapse — so the run is short
	 * and starts in summer to avoid a founding-winter death cluster exhausting the
	 * reserve at once. The refill that would make this sustainable is a later phase.
	 */
	static final int PEASANT_RESERVE = 40;

	/**
	 * Build and run the simulation.
	 *
	 * @return the harness, exposing the markets, banks, agents and the peasant pool
	 *         (via {@code getPeasantPool()})
	 */
	public static SimulationHarness run() {
		// the calibrated default scale (proven stable with a pool by PeasantEconomy),
		// over a SHORT horizon: while the reserve lasts, promotion replaces dead
		// laborers 1:1 and the population holds. Once it runs dry, deaths go
		// unreplaced and the shrinking workforce produces less food — a death spiral
		// to collapse (the depletion is fundamental without the later refill phase),
		// so the run ends before the reserve is exhausted.
		SimulationConfig cfg = SimulationConfig.DEFAULT.toBuilder()
				.settlementName("Siena")
				.startDate(LocalDate.of(1444, 6, 11)) // summer: no founding-winter death cluster
				.durationYears(2)
				.peasantReserveSize(PEASANT_RESERVE)
				.promoteLaborersFromPool(true)
				.build();
		SimulationHarness h = SimulationHarness.create(cfg, 7654321);
		Bank bank = h.getCopperBank();
		h.createMarkets();
		h.createFirms(bank, i -> bank,
				i -> cfg.eFirm().savings(), i -> cfg.nFirm().savings());
		h.createDefaultStrategicSector(bank);
		// createLaborers registers the promotion replacement policy (cfg flag is on)
		h.createLaborers(i -> bank, i -> 15, i -> cfg.laborer().savings());
		h.enableExternalInflow(bank);
		Bank gold = h.createDefaultRuler();
		h.createDefaultPeasantPool();
		h.addCommonPrinters();
		h.addBankPrinter("Bank", bank);
		h.addBankPrinter("Gold", gold);
		h.addStrategicSectorPrinters("", bank);
		h.addPeasantPrinter("Peasants");
		h.run();
		return h;
	}

	public static void main(String[] args) {
		run();
	}
}
