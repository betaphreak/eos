package eos.simulation;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import eos.agent.firm.Firm;
import eos.agent.noble.Noble;
import eos.agent.noble.NobleConfig;
import eos.bank.Bank;
import eos.bank.BankConfig;
import eos.settlement.Settlement;
import eos.settlement.GameSession;
import eos.io.SimLog;
import eos.io.printer.NoblesPrinter;

/**
 * Simulation (with an aristocracy): the homogeneous, single-bank colony of
 * {@link HomogeneousEconomy}, plus a small class of <b>nobles</b> who own the means of
 * production — the firms <i>and</i> the bank — and live off their profits. The
 * firms and laborers are unchanged; two noble households split ownership of all
 * the consumer and capital firms between them, and the senior noble also owns the
 * bank. Each draws a dividend every step — from its firms' surplus and, for the
 * bank owner, from the bank's retained interest spread — through the bank's
 * previously-dormant secondary-income channel, then spends it back into the
 * consumer-good markets. The bank is given a small {@value #BANK_SPREAD} interest
 * spread (the documented-safe level) so it actually turns a profit to distribute;
 * the noble owner skims that profit out of the bank's equity, leaving the
 * inheritance / external-funds buffer untouched.
 * <p>
 * This is the "option A" design: nobles are rentier owners, not workers — they
 * never enter the labor market, and influence it only on the demand side via
 * their consumption. Each noble is a named household (a unique dynasty surname,
 * drawn just like a laborer's) that ages, dies and passes its estate and its
 * holdings to a same-dynasty heir; the {@code Nobles.csv} printer tracks them.
 */
public class AristocraticEconomy {

	/** Number of noble households the firms are divided among. */
	static final int NUM_NOBLES = 2;

	/** Each noble's opening savings (its seed fortune). */
	static final double NOBLE_INITIAL_SAVINGS = 1000;

	/** Interest spread given to the (noble-owned) bank so it earns a profit. */
	static final double BANK_SPREAD = 0.005;

	/**
	 * Build and run the simulation.
	 *
	 * @return the harness, exposing the constructed markets, bank, firms and
	 *         laborers (the nobles are reachable via {@code getColony()})
	 */
	public static SimulationHarness run() {
		SimulationConfig cfg = SimulationConfig.DEFAULT;
		GameSession session = new GameSession(7654321);
		Settlement colony = session.newSettlement(cfg.startDate(),
				cfg.meanInitAgeYears(), cfg.targetNStock());
		SimLog.init(colony);

		SimulationHarness h = new SimulationHarness(cfg, colony);
		h.createMarkets();
		Bank bank = h.addBank(
				BankConfig.DEFAULT.toBuilder().spread(BANK_SPREAD).build());
		h.createFirms(bank, i -> bank,
				i -> cfg.eFirm().savings(), i -> cfg.nFirm().savings());
		h.createLaborers(i -> bank, i -> 15, i -> cfg.laborer().savings());
		h.enableExternalInflow(bank);

		// gather every firm and divide ownership round-robin among the nobles
		List<Firm> allFirms = new ArrayList<>();
		allFirms.addAll(Arrays.asList(h.getEFirms()));
		allFirms.addAll(Arrays.asList(h.getNFirms()));
		allFirms.addAll(Arrays.asList(h.getCapitalFirms()));

		for (int n = 0; n < NUM_NOBLES; n++) {
			List<Firm> owned = new ArrayList<>();
			for (int i = n; i < allFirms.size(); i += NUM_NOBLES)
				owned.add(allFirms.get(i));
			// the senior noble (n == 0) also owns the bank
			List<Bank> ownedBanks = (n == 0) ? List.of(bank) : List.<Bank>of();
			Noble noble = new Noble(0, NOBLE_INITIAL_SAVINGS, owned, ownedBanks,
					NobleConfig.DEFAULT, bank, colony);
			colony.addAgent(noble);
		}

		// when a noble's head dies, a successor of the same dynasty inherits its
		// estate, firms and banks, so the aristocracy persists
		colony.addReplacementPolicy(dead -> dead instanceof Noble n
				? new Noble(n, NobleConfig.DEFAULT, colony)
				: null);

		h.addCommonPrinters();
		h.addBankPrinter("Bank", bank);
		colony.addPrinter(new NoblesPrinter("Nobles"));
		h.run();
		return h;
	}

	public static void main(String[] args) {
		run();
	}
}
