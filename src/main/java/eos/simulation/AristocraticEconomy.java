package eos.simulation;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import eos.agent.firm.Firm;
import eos.agent.firm.StrategicFirmConfig;
import eos.agent.noble.Noble;
import eos.agent.noble.NobleConfig;
import eos.bank.Bank;
import eos.settlement.Settlement;
import eos.io.printer.NoblesPrinter;
import eos.io.printer.PersonsOfInterestPrinter;

/**
 * Simulation (with an aristocracy): the homogeneous colony of {@link
 * HomogeneousEconomy}, plus a small class of <b>nobles</b> who own the means of
 * production — the firms <i>and</i> a bank — and live off their profits. It uses
 * the default tiered banking (see {@link SimulationHarness#getCopperBank()}):
 * commoners (the firms and laborers, unchanged) bank in copper, the nobles in
 * silver. Five noble households split ownership of all the consumer and capital
 * firms between them, and the senior noble also owns the silver bank. Each draws a
 * dividend every step — from its firms' surplus and, for the bank owner, from the
 * silver bank's retained profit — through the bank's secondary-income channel,
 * then spends it back into the (copper-priced) consumer-good markets. The silver
 * bank is a <b>money-changer</b>: it profits from the FX fee it skims whenever a
 * noble's money crosses the copper boundary (its dividends in, its purchases out),
 * so it has a real profit to distribute; the noble owner skims that out of the
 * bank's equity, leaving the inheritance / external-funds buffer untouched.
 * <p>
 * Like every settlement, this colony also has an <b>export sector</b> — a {@link
 * eos.agent.firm.StrategicFirm} banking in copper — and the nobles staff it: they
 * are not pure rentiers but also work the noble-only labor market, earning export
 * wages on top of their dividends (their wages cross copper → silver, like their
 * dividends). The export earnings, net of those wages, accrue to the copper bank's
 * equity, so the copper bank is no longer a zero-profit intermediary. Beyond that
 * the nobles influence the consumer markets only on the demand side, via their
 * consumption. Each noble is a named household (a unique dynasty surname, drawn
 * just like a laborer's) that ages, dies and passes its estate and its holdings to
 * a same-dynasty heir; the {@code Nobles.csv} printer tracks them.
 */
public class AristocraticEconomy {

	/**
	 * Number of noble households the firms are divided among. They also staff the
	 * settlement's export sector (every settlement has one), so there must be enough
	 * of them for the fixed-wage {@link eos.agent.firm.StrategicFirm} to earn more in
	 * exports than it pays in wages — otherwise it would run at a loss and drain the
	 * copper bank's equity. Five gives the export sector a comfortable margin (cf.
	 * {@link SimulationHarness#DEFAULT_NUM_NOBLES}).
	 */
	static final int NUM_NOBLES = 5;

	/** Each noble's opening savings (its seed fortune). */
	static final double NOBLE_INITIAL_SAVINGS = 1000;

	/**
	 * Build and run the simulation.
	 *
	 * @return the harness, exposing the constructed markets, bank, firms and
	 *         laborers (the nobles are reachable via {@code getColony()})
	 */
	public static SimulationHarness run() {
		SimulationConfig cfg = SimulationConfig.DEFAULT;
		SimulationHarness h = SimulationHarness.create(cfg, 7654321);
		Settlement colony = h.getColony();
		h.createMarkets();
		// the default tiered banking: commoners (laborers + firms) bank in copper,
		// the nobles in silver — a money-changer that profits from the FX fee on
		// their copper-quoted dividends and purchases
		Bank copper = h.getCopperBank();
		Bank silver = h.getSilverBank();
		// the export sector: the noble-only labor market must exist before the
		// nobles are created (they staff it), and the export firm banks in copper
		h.createNobleLaborMarket();
		h.createFirms(copper, i -> copper,
				i -> cfg.eFirm().savings(), i -> cfg.nFirm().savings());
		h.createStrategicFirm(copper, StrategicFirmConfig.DEFAULT);
		h.createLaborers(i -> copper, i -> 15, i -> cfg.laborer().savings());
		h.enableExternalInflow(copper);

		// gather every firm and divide ownership round-robin among the nobles
		List<Firm> allFirms = new ArrayList<>();
		allFirms.addAll(Arrays.asList(h.getEFirms()));
		allFirms.addAll(Arrays.asList(h.getNFirms()));
		allFirms.addAll(Arrays.asList(h.getCapitalFirms()));

		for (int n = 0; n < NUM_NOBLES; n++) {
			List<Firm> owned = new ArrayList<>();
			for (int i = n; i < allFirms.size(); i += NUM_NOBLES)
				owned.add(allFirms.get(i));
			// the senior noble (n == 0) also owns the silver bank, drawing its FX
			// profit as a bank dividend
			List<Bank> ownedBanks = (n == 0) ? List.of(silver) : List.<Bank>of();
			Noble noble = new Noble(0, NOBLE_INITIAL_SAVINGS, owned, ownedBanks,
					NobleConfig.DEFAULT, silver, colony);
			colony.addAgent(noble);
		}

		// when a noble's head dies, a successor of the same dynasty inherits its
		// estate, firms and banks (so the aristocracy persists) — produced by the
		// colony's built-in household-succession policy (see Noble.successor)

		// the nobles posted to the noble labor market in their constructors; clear
		// it once so the export firm has workers in step 0
		h.primeNobleLabor();

		// every settlement has a ruler, banking in gold; created last so its
		// demographic draws don't perturb the commoners' or nobles'
		Bank gold = h.createDefaultRuler();

		h.addCommonPrinters();
		h.addBankPrinter("Copper", copper);
		h.addBankPrinter("Silver", silver);
		h.addBankPrinter("Gold", gold);
		h.addStrategicPrinter("Strategic", copper);
		colony.addPrinter(new NoblesPrinter("Nobles"));
		colony.addPrinter(new PersonsOfInterestPrinter("PersonsOfInterest"));
		h.run();
		return h;
	}

	public static void main(String[] args) {
		run();
	}
}
