package eos.simulation;

import java.util.List;

import eos.agent.firm.StrategicFirm;
import eos.agent.firm.StrategicFirmConfig;
import eos.agent.noble.Noble;
import eos.agent.noble.NobleConfig;
import eos.bank.Bank;
import eos.io.printer.NoblesPrinter;
import eos.io.printer.PersonsOfInterestPrinter;
import eos.io.printer.StrategicPrinter;
import eos.settlement.Settlement;

/**
 * Simulation (with a strategic export sector worked by the aristocracy): the
 * homogeneous colony of {@link HomogeneousEconomy}, plus a class of
 * <b>nobles</b> and the settlement's single {@link StrategicFirm}. Unlike the
 * consumer and capital firms (staffed by laborers), the export firm's labor pool
 * is constrained to the nobles: they alone are the employees of a dedicated
 * {@code NobleLabor} market, and the firm converts their <b>skill-scaled</b>
 * labor into the strategic good, which it <b>exports out of the economy</b>. The
 * export earnings flow into the bank's <b>equity</b>, and the firm's wage bill is
 * funded back out of it — so the firm is a pure conduit and the bank's equity
 * climbs with cumulative net exports.
 * <p>
 * The nobles here own no firms or banks; they live off the wages the strategic
 * firm pays them (plus their seed fortunes), so their income is the export
 * sector's labor share. Under the default tiered banking they bank in <b>silver</b>
 * while the export firm banks in copper, so the wages they earn cross the
 * copper → silver boundary and the silver money-changer skims its FX fee on them.
 * They age, die and are succeeded by same-dynasty heirs who keep working the
 * export sector. Unlike the closed default runs (whose copper bank is a
 * zero-profit, ~zero-equity intermediary), here the <b>copper</b> bank's equity
 * grows monotonically with cumulative net exports — the defining observable.
 */
public class StrategicEconomy {

	/** Number of noble households that staff the export sector. */
	static final int NUM_NOBLES = 5;

	/** Each noble's opening savings (its seed fortune). */
	static final double NOBLE_INITIAL_SAVINGS = 1000;

	/**
	 * Build and run the simulation.
	 *
	 * @return the harness, exposing the constructed markets, bank, firms (the
	 *         export firm via {@code getStrategicFirm()}) and agents (the nobles
	 *         via {@code getColony().getAgents()})
	 */
	public static SimulationHarness run() {
		// start with one enjoyment and one necessity firm; the ruler's dynamic
		// provisioning grows the consumer sectors to fit demand
		SimulationConfig cfg = SimulationConfig.DEFAULT.toBuilder()
				.numEFirms(1).numNFirms(1).build();
		SimulationHarness h = SimulationHarness.create(cfg, 7654321);
		Settlement colony = h.getColony();

		h.createMarkets();
		// the default tiered banking: commoners (laborers, firms, the export firm)
		// in copper, the nobles in silver. The export firm banks copper, so its
		// export earnings still accrue to copper equity (the defining observable);
		// the nobles' export wages cross copper -> silver, so the silver
		// money-changer skims its FX fee on them.
		Bank copper = h.getCopperBank();
		Bank silver = h.getSilverBank();
		// the noble-only labor market must exist before the export firm and the
		// nobles are created (both look it up)
		h.createNobleLaborMarket();
		h.createFirms(copper, i -> copper,
				i -> cfg.eFirm().savings(), i -> cfg.nFirm().savings());
		h.createStrategicFirm(copper, StrategicFirmConfig.DEFAULT);

		// the nobles who work the export sector (they own no firms or banks here)
		for (int n = 0; n < NUM_NOBLES; n++) {
			Noble noble = new Noble(0, NOBLE_INITIAL_SAVINGS, List.of(), List.of(),
					NobleConfig.DEFAULT, silver, colony);
			colony.addAgent(noble);
		}
		// a same-dynasty successor (which inherits the estate and keeps working the
		// export sector) is produced by the colony's built-in household-succession
		// policy (see Noble.successor), so no rule is wired here

		// clear the noble labor market once so the export firm has noble workers
		// in step 0 (mirrors the pre-run clearing of the general labor market)
		h.primeNobleLabor();

		// the ruler (founding cash) and the pool precede the labor force, which the
		// ruler founds and replaces by promotion from the pool
		Bank gold = h.createDefaultRuler();
		// grow the consumer sectors dynamically, but keep the nobles firm-less (they
		// live purely on export wages here) — chartered firms stay unowned
		h.enableDynamicFirmProvisioning(copper, false);
		h.createDefaultPeasantPool();
		h.foundLaborersFromPool(i -> copper, i -> 15);
		h.enableExternalInflow(copper);
		h.addCommonPrinters();
		h.addBankPrinter("Copper", copper);
		h.addBankPrinter("Silver", silver);
		h.addBankPrinter("Gold", gold);
		StrategicFirm firm = h.getStrategicFirm();
		colony.addPrinter(new StrategicPrinter("Strategic", firm, copper));
		colony.addPrinter(new NoblesPrinter("Nobles"));
		colony.addPrinter(new PersonsOfInterestPrinter("PersonsOfInterest"));
		h.run();
		return h;
	}

	public static void main(String[] args) {
		run();
	}
}
