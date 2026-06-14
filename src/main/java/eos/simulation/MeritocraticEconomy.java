package eos.simulation;

import java.time.LocalDate;
import java.util.List;

import eos.agent.firm.StrategicFirmConfig;
import eos.agent.noble.Noble;
import eos.agent.noble.NobleConfig;
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
	 * Fraction of the seeded pool the ruler promotes into laborer households on day
	 * 0. The pool is seeded with the default {@code peasantPoolSize} (900) peasants,
	 * so a high ratio here promotes most of them and leaves a small standing reserve — foregrounding
	 * promotion while keeping the reserve modest enough that it drains (by promotion
	 * and old age) within the run, after which deaths go unreplaced, the workforce
	 * shrinks, food production falls, and the colony spirals to collapse. The refill
	 * that would make this sustainable is a later phase.
	 */
	static final double PROMOTION_RATIO = 0.8;

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
				.startDate(LocalDate.of(1444, 12, 11))
				.durationYears(25)
				.promotionRatio(PROMOTION_RATIO)
				.numEFirms(1).numNFirms(1)
				.build();
		SimulationHarness h = SimulationHarness.create(cfg, 7654321);
		Bank bank = h.getCopperBank();
		h.createMarkets();
		h.createFirms(bank, i -> bank,
				i -> cfg.eFirm().savings(), i -> cfg.nFirm().savings());
		// the full three-currency hierarchy: commoners/firms in copper, the export
		// nobles in silver, the ruler in gold. The strategic firm banks copper (its
		// export earnings accrue to copper equity), so the nobles' export wages cross
		// copper -> silver and the silver money-changer skims its FX fee.
		Bank silver = h.getSilverBank();
		h.createNobleLaborMarket();
		h.createStrategicFirm(bank, StrategicFirmConfig.DEFAULT);
		for (int n = 0; n < SimulationHarness.DEFAULT_NUM_NOBLES; n++)
			h.getColony().addAgent(new Noble(0, SimulationHarness.DEFAULT_NOBLE_SAVINGS,
					List.of(), List.of(), NobleConfig.DEFAULT, silver, h.getColony()));
		h.primeNobleLabor();
		// the ruler (founding cash) and the pool precede the labor force, which the
		// ruler founds and replaces by promotion from the pool
		Bank gold = h.createDefaultRuler();
		h.enableDynamicFirmProvisioning(bank);
		h.createDefaultPeasantPool();
		h.foundLaborersFromPool(i -> bank, i -> 15);
		h.enableExternalInflow(bank);
		h.addCommonPrinters();
		h.addBankPrinter("Copper", bank);
		h.addBankPrinter("Silver", silver);
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
