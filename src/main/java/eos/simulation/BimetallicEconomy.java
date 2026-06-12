package eos.simulation;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import eos.agent.firm.Firm;
import eos.agent.noble.Noble;
import eos.agent.noble.NobleConfig;
import eos.agent.ruler.Ruler;
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
 * Above them sits the settlement's <b>ruler</b> ({@link Ruler}), who owns a third
 * bank denominated in <b>gold</b> and holds his fortune ({@value
 * #RULER_INITIAL_GOLD} gold) there as its sole client — the gold bank has no
 * other clients. The ruler earns nothing but indulges a luxury habit, spending a
 * small fraction of the treasury on enjoyment each step (it ages and passes to a
 * heir like everyone). The colony thus mirrors its class structure in metal:
 * commoners in copper, nobles in silver, the ruler in gold.
 * <p>
 * Currency exchange carries <b>friction</b>. The three metals convert at a fixed
 * rate (see {@link CurrencyType}), but because every price is quoted in copper
 * (the base unit), a noble banking in silver must convert on <em>every</em>
 * payment — losing {@value #EXCHANGE_FEE_RATE} of it — when a dividend is credited
 * to its silver account (copper → silver) and again when it spends into the
 * copper consumer markets (silver → copper). The silver bank is the nobles'
 * money-changer and retains that fee as equity; the <b>senior noble owns the
 * silver bank</b>, so that profit flows back to it as a bank dividend. The gold
 * bank likewise skims the fee whenever the ruler buys enjoyment (gold → copper),
 * so it too now turns a profit. Only the copper bank — the base currency, which
 * never converts — stays a zero-profit intermediary. This is the conversion
 * friction that makes the currency split bite: holding a metal other than the
 * copper everything is priced in is no longer free.
 */
public class BimetallicEconomy {

	/** Number of noble households the firms are divided among. */
	static final int NUM_NOBLES = 2;

	/**
	 * Each noble's opening savings (its seed fortune). Like all balances this is
	 * held in copper internally; at the fixed exchange rate it reads as 10 silver
	 * in the silver-denominated reports.
	 */
	static final double NOBLE_INITIAL_SAVINGS = 1000;

	/** The ruler's opening fortune, in <b>gold</b> (held in copper internally). */
	static final double RULER_INITIAL_GOLD = 10;

	/**
	 * Currency-exchange (FX) fee the non-copper banks charge their clients to
	 * convert to/from copper (the quote currency) on each payment — the friction
	 * that makes the currency split bite. The copper bank charges nothing (it is
	 * the base currency).
	 */
	static final double EXCHANGE_FEE_RATE = 0.02;

	/**
	 * Fraction of its treasury the ruler spends on enjoyment each step — a small
	 * rate, so the sovereign's luxury habit draws the reserves down gradually over
	 * the run rather than exhausting them.
	 */
	static final double RULER_CONSUMPTION_RATE = 0.0002;

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
		// the default first bank is copper (commoners); the nobles' bank is silver;
		// the ruler's is gold
		// the non-copper banks act as money-changers: they charge an FX fee to
		// convert their clients' payments to/from copper (the quote currency)
		Bank copper = h.addBank(BankConfig.DEFAULT);
		Bank silver = h.addBank(BankConfig.DEFAULT.toBuilder()
				.currency(CurrencyType.SILVER)
				.exchangeFeeRate(EXCHANGE_FEE_RATE).build());
		Bank gold = h.addBank(BankConfig.DEFAULT.toBuilder()
				.currency(CurrencyType.GOLD)
				.exchangeFeeRate(EXCHANGE_FEE_RATE).build());

		// every firm and laborer banks in copper
		h.createFirms(copper, i -> copper,
				i -> cfg.eFirm().savings(), i -> cfg.nFirm().savings());
		h.createLaborers(i -> copper, i -> 15, i -> cfg.laborer().savings());
		h.enableExternalInflow(copper);

		// gather every firm and divide ownership round-robin among the nobles, who
		// bank in silver. The senior noble (n == 0) also owns the silver bank, so
		// the FX fees it skims (on every noble's purchases and dividends) flow back
		// to that noble as a bank dividend — the money-changer's profit accrues to
		// an aristocrat.
		List<Firm> allFirms = new ArrayList<>();
		allFirms.addAll(Arrays.asList(h.getEFirms()));
		allFirms.addAll(Arrays.asList(h.getNFirms()));
		allFirms.addAll(Arrays.asList(h.getCapitalFirms()));

		for (int n = 0; n < NUM_NOBLES; n++) {
			List<Firm> owned = new ArrayList<>();
			for (int i = n; i < allFirms.size(); i += NUM_NOBLES)
				owned.add(allFirms.get(i));
			List<Bank> ownedBanks = (n == 0) ? List.of(silver) : List.<Bank>of();
			Noble noble = new Noble(0, NOBLE_INITIAL_SAVINGS, owned,
					ownedBanks, NobleConfig.DEFAULT, silver, colony);
			colony.addAgent(noble);
		}

		// when a noble's head dies, a same-dynasty successor inherits its estate
		// and firms, continuing to bank in silver (predecessor's bank)
		colony.addReplacementPolicy(dead -> dead instanceof Noble n
				? new Noble(n, NobleConfig.DEFAULT, colony)
				: null);

		// the ruler owns the gold bank and holds its fortune there as its sole
		// client; it earns nothing but indulges a luxury habit, spending a small
		// fraction of the treasury on enjoyment each step (converting gold -> copper,
		// which fires the gold bank's FX fee). Created last so its demographic draws
		// do not perturb the commoners' or nobles'. When it dies of old age a
		// same-dynasty heir succeeds it.
		Ruler ruler = new Ruler(CurrencyType.GOLD.toCopper(RULER_INITIAL_GOLD),
				RULER_CONSUMPTION_RATE, gold, colony);
		colony.addAgent(ruler);
		colony.addReplacementPolicy(dead -> dead instanceof Ruler r
				? new Ruler(r, colony)
				: null);

		h.addCommonPrinters();
		h.addBankPrinter("Copper", copper);
		h.addBankPrinter("Silver", silver);
		h.addBankPrinter("Gold", gold);
		colony.addPrinter(new NoblesPrinter("Nobles"));
		colony.addPrinter(new PersonsOfInterestPrinter("PersonsOfInterest"));
		h.run();
		return h;
	}

	public static void main(String[] args) {
		run();
	}
}
