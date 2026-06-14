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
 * This is the only bundled simulation that seeds a pool (the others leave
 * {@code peasantReserveSize} at its 0 default), so the feature stays opt-in until a
 * later phase makes it a standard colony fixture.
 */
public class PeasantEconomy {

	/** Number of peasants the colony's pool is seeded with. */
	static final int PEASANT_RESERVE = 10;

	/**
	 * Build and run the simulation.
	 *
	 * @return the harness, exposing the constructed markets, banks, agents and the
	 *         peasant pool (via {@code getPeasantPool()})
	 */
	public static SimulationHarness run() {
		SimulationConfig cfg = SimulationConfig.DEFAULT.toBuilder()
				.peasantReserveSize(PEASANT_RESERVE).build();
		SimulationHarness h = SimulationHarness.create(cfg, 7654321);
		h.createMarkets();
		Bank bank = h.getCopperBank();
		h.createFirms(bank, i -> bank,
				i -> cfg.eFirm().savings(), i -> cfg.nFirm().savings());
		h.createDefaultStrategicSector(bank);
		h.createLaborers(i -> bank, i -> 15, i -> cfg.laborer().savings());
		h.enableExternalInflow(bank);
		// the ruler (the pool's sponsor) is created before the pool, and the pool
		// last of all so its demographic/naming draws don't perturb the others
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
