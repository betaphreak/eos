package eos.simulation;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import eos.agent.firm.Firm;
import eos.agent.noble.Noble;
import eos.agent.noble.NobleConfig;
import eos.bank.Bank;
import eos.bank.BankConfig;
import eos.economy.Economy;
import eos.economy.GameSession;
import eos.io.SimLog;
import eos.io.printer.NoblesPrinter;

/**
 * Simulation (with an aristocracy): the homogeneous, single-bank economy of
 * {@link Simulation1}, plus a small class of <b>nobles</b> who own the firms and
 * live off their profits. The firms and laborers are unchanged; two noble
 * households split ownership of all the consumer and capital firms between them,
 * each drawing a dividend from its firms' surplus every step (through the bank's
 * previously-dormant secondary-income channel) and spending it back into the
 * consumer-good markets.
 * <p>
 * This is the "option A" design: nobles are rentier owners, not workers — they
 * never enter the labor market, and influence it only on the demand side via
 * their consumption. Each noble is a named household (a unique dynasty surname,
 * drawn just like a laborer's) tracked by the {@code Nobles.csv} printer.
 */
public class Simulation6 {

	/** Number of noble households the firms are divided among. */
	static final int NUM_NOBLES = 2;

	/** Each noble's opening savings (its seed fortune). */
	static final double NOBLE_INITIAL_SAVINGS = 1000;

	/**
	 * Build and run the simulation.
	 *
	 * @return the harness, exposing the constructed markets, bank, firms and
	 *         laborers (the nobles are reachable via {@code getEconomy()})
	 */
	public static SimulationHarness run() {
		SimulationConfig cfg = SimulationConfig.DEFAULT;
		GameSession session = new GameSession(7654321);
		Economy economy = session.newEconomy(cfg.startDate(),
				cfg.meanInitAgeYears());
		SimLog.init(economy);

		SimulationHarness h = new SimulationHarness(cfg, economy);
		h.createMarkets();
		Bank bank = h.addBank(BankConfig.DEFAULT);
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
			Noble noble = new Noble(0, NOBLE_INITIAL_SAVINGS, owned,
					NobleConfig.DEFAULT, bank, economy);
			economy.addAgent(noble);
		}

		// when a noble's head dies (mortality), a successor of the same dynasty
		// inherits its estate and its firms, so the aristocracy persists
		if (cfg.mortalityEnabled())
			economy.addReplacementPolicy(dead -> dead instanceof Noble n
					? new Noble(n, NobleConfig.DEFAULT, economy)
					: null);

		h.addCommonPrinters();
		h.addBankPrinter("Bank", bank);
		economy.addPrinter(new NoblesPrinter("Nobles", cfg.stepSize()));
		h.run();
		return h;
	}

	public static void main(String[] args) {
		run();
	}
}
