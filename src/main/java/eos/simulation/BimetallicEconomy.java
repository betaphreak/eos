package eos.simulation;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import eos.agent.firm.Firm;
import eos.agent.noble.Noble;
import eos.agent.noble.NobleConfig;
import eos.bank.Bank;
import eos.bank.BankConfig;
import eos.bank.CurrencyType;
import eos.io.printer.NoblesPrinter;
import eos.io.printer.PersonsOfInterestPrinter;
import eos.settlement.Settlement;

/**
 * Simulation (two currencies split by class): the homogeneous colony of {@link
 * HomogeneousEconomy} plus a noble aristocracy, but with the two estates banking
 * separately in <b>different currencies</b> — as in {@link TwoBankEconomy} the
 * agents are split across two banks, here strictly by class. The commoners (every
 * laborer and every firm) bank at the default first bank, denominated in {@link
 * CurrencyType#COPPER}; the nobles bank exclusively at a second bank denominated
 * in {@link CurrencyType#SILVER}.
 * <p>
 * The nobles own all the firms and live off their dividends, exactly as in {@link
 * AristocraticEconomy}, but those dividends now cross the currency boundary: a
 * firm's surplus is withdrawn from the copper bank and credited to the noble at
 * the silver bank, and the noble's consumption flows the other way when it buys
 * from the (copper-banking) firms. Both banks are ordinary zero-profit
 * intermediaries (no spread), so each settles interest over its own pool of
 * accounts.
 * <p>
 * Currency is, for now, a label: cross-currency payments move nominal amounts
 * one-for-one (there is no exchange rate). This wires the two-currency split a
 * later exchange-rate mechanism would build on.
 */
public class BimetallicEconomy {

	/** Number of noble households the firms are divided among. */
	static final int NUM_NOBLES = 2;

	/** Each noble's opening savings (its seed fortune), in silver. */
	static final double NOBLE_INITIAL_SAVINGS = 1000;

	/**
	 * Build and run the simulation.
	 *
	 * @return the harness, exposing the constructed markets, banks, firms and
	 *         laborers (the nobles are reachable via {@code getColony()})
	 */
	public static SimulationHarness run() {
		SimulationConfig cfg = SimulationConfig.DEFAULT;
		SimulationHarness h = SimulationHarness.create(cfg, 7654321);
		Settlement colony = h.getColony();

		h.createMarkets();
		// the default first bank is copper (commoners); the nobles' bank is silver
		Bank copper = h.addBank(BankConfig.DEFAULT);
		Bank silver = h.addBank(BankConfig.DEFAULT.toBuilder()
				.currency(CurrencyType.SILVER).build());

		// every firm and laborer banks in copper
		h.createFirms(copper, i -> copper,
				i -> cfg.eFirm().savings(), i -> cfg.nFirm().savings());
		h.createLaborers(i -> copper, i -> 15, i -> cfg.laborer().savings());
		h.enableExternalInflow(copper);

		// gather every firm and divide ownership round-robin among the nobles, who
		// bank in silver (they own the firms but not the banks)
		List<Firm> allFirms = new ArrayList<>();
		allFirms.addAll(Arrays.asList(h.getEFirms()));
		allFirms.addAll(Arrays.asList(h.getNFirms()));
		allFirms.addAll(Arrays.asList(h.getCapitalFirms()));

		for (int n = 0; n < NUM_NOBLES; n++) {
			List<Firm> owned = new ArrayList<>();
			for (int i = n; i < allFirms.size(); i += NUM_NOBLES)
				owned.add(allFirms.get(i));
			Noble noble = new Noble(0, NOBLE_INITIAL_SAVINGS, owned,
					List.<Bank>of(), NobleConfig.DEFAULT, silver, colony);
			colony.addAgent(noble);
		}

		// when a noble's head dies, a same-dynasty successor inherits its estate
		// and firms, continuing to bank in silver (predecessor's bank)
		colony.addReplacementPolicy(dead -> dead instanceof Noble n
				? new Noble(n, NobleConfig.DEFAULT, colony)
				: null);

		h.addCommonPrinters();
		h.addBankPrinter("Copper", copper);
		h.addBankPrinter("Silver", silver);
		colony.addPrinter(new NoblesPrinter("Nobles"));
		colony.addPrinter(new PersonsOfInterestPrinter("PersonsOfInterest"));
		h.run();
		return h;
	}

	public static void main(String[] args) {
		run();
	}
}
