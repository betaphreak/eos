package eos.simulation;

import eos.agent.PeasantPool;
import eos.bank.Bank;

/**
 * Simulation (worked example of the peasant pool): the {@link HomogeneousEconomy}
 * colony plus a {@link PeasantPool} of {@value #PEASANT_RESERVE} peasants that the
 * settlement's ruler feeds. Each step the pool eats one necessity per peasant,
 * bought on the necessity market and billed to the ruler (who borrows to cover it);
 * peasants age, can die of old age, and their skills decay, so with no inflow yet
 * the pool simply drains over the run. See {@code docs/peasant-pool.md}.
 * <p>
 * It charts the pool's relief spending (the ruler is billed) before the colony
 * collapses; it keeps a <b>larger reserve</b> than the default by promoting a
 * smaller fraction of the seeded pool.
 */
public class PeasantEconomy {

	/**
	 * Fraction of the seeded pool promoted into laborer households on day 0 — kept
	 * low so a large reserve remains (the pool is seeded with the default {@code
	 * peasantPoolSize} of 900 peasants), foregrounding the relief the ruler funds
	 * for the standing reserve before the pool drains and the colony collapses.
	 */
	static final double PROMOTION_RATIO = 0.3;

	/**
	 * Build and run the simulation.
	 *
	 * @return the harness, exposing the constructed markets, banks, agents and the
	 *         peasant pool (via {@code getPeasantPool()})
	 */
	public static SimulationHarness run() {
		SimulationConfig cfg = SimulationConfig.DEFAULT.toBuilder()
				.promotionRatio(PROMOTION_RATIO).numEFirms(1).numNFirms(1).build();
		SimulationHarness h = SimulationHarness.create(cfg, 7654321);
		h.createMarkets();
		Bank bank = h.getCopperBank();
		h.createFirms(bank, i -> bank,
				i -> cfg.eFirm().savings(), i -> cfg.nFirm().savings());
		h.createDefaultStrategicSector(bank);
		// the ruler (founding cash) and the pool precede the labor force, which the
		// ruler founds and replaces by promotion from the pool
		Bank gold = h.createDefaultRuler();
		h.enableDynamicFirmProvisioning(bank);
		h.createDefaultPeasantPool();
		h.foundLaborersFromPool(i -> bank, i -> 15);
		h.enableExternalInflow(bank);
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
