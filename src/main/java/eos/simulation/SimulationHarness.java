package eos.simulation;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;
import java.util.function.IntToDoubleFunction;

import eos.agent.Agent;
import eos.agent.firm.CFirm;
import eos.agent.firm.EFirm;
import eos.agent.firm.FirmConfig;
import eos.agent.firm.NFirm;
import eos.agent.firm.StrategicFirm;
import eos.agent.firm.StrategicFirmConfig;
import eos.agent.laborer.Laborer;
import eos.agent.laborer.LaborerConfig;
import eos.bank.Bank;
import eos.bank.BankConfig;
import eos.settlement.Settlement;
import eos.settlement.GameSession;
import eos.io.SimLog;
import eos.io.printer.*;
import eos.market.*;
import lombok.Getter;

/**
 * Shared construction and run logic for the bundled simulations. Each
 * {@code Simulation} creates an {@link Settlement} from a {@link GameSession}
 * (which owns the seed) and populates it through this harness, supplying only
 * what differs between runs: which bank each agent uses, and how each agent's
 * initial state is drawn. After {@link #run()} the harness exposes the
 * constructed markets, banks and agents so tests can assert on the final state.
 * <p>
 * Call order mirrors the original hand-written simulations and matters because
 * of deferred settlement and reproducible RNG consumption: {@link
 * #createMarkets()}, {@link #addBank(BankConfig)}, {@link #createFirms}, {@link
 * #createLaborers}, printers, then {@link #run()}.
 */
@Getter
public class SimulationHarness {

	// fixed necessity stock granted to a replacement household
	private static final int REPLACEMENT_NECESSITY_STOCK = 15;

	private final SimulationConfig cfg;
	private final Settlement colony;
	private final List<Bank> banks = new ArrayList<>();

	// behavioral parameters for the consumer-good firms; defaults to the
	// canonical values with the run's labor-share applied (see constructor).
	// Replace via setFirmConfig before createFirms to vary other firm params.
	private FirmConfig firmConfig;

	private ConsumerGoodMarket enjoymentMkt;
	private ConsumerGoodMarket necessityMkt;
	private LaborMarket laborMkt;
	private LaborMarket nobleLaborMkt;
	private CapitalMarket capitalMkt;

	private CFirm[] capitalFirms;
	private EFirm[] eFirms;
	private NFirm[] nFirms;
	private Laborer[] laborers;
	private StrategicFirm strategicFirm;

	/**
	 * Build an empty harness for {@code cfg} from a fresh {@link GameSession}
	 * seeded with {@code seed}: create the session, a {@link Settlement} from it
	 * (using the config's calendar and demographic constants), and initialize
	 * logging. This is the prologue every simulation's {@code run()} shares; the
	 * caller then populates the colony (markets, banks, agents, printers) and
	 * calls {@link #run()}.
	 *
	 * @param cfg
	 *            the run configuration
	 * @param seed
	 *            the random-number seed for this run
	 * @return an empty harness ready to be populated
	 */
	public static SimulationHarness create(SimulationConfig cfg, long seed) {
		GameSession session = new GameSession(seed);
		Settlement colony = session.newSettlement(cfg.startDate(),
				cfg.meanInitAgeYears(), cfg.targetNStock(), cfg.meanSkill());
		SimLog.init(colony);
		return new SimulationHarness(cfg, colony);
	}

	public SimulationHarness(SimulationConfig cfg, Settlement colony) {
		this.cfg = cfg;
		this.colony = colony;
		// seed the firm parameters with the run's labor-share (the rest are the
		// canonical defaults); setFirmConfig can override before createFirms
		this.firmConfig =
				FirmConfig.DEFAULT.toBuilder().laborShare(cfg.laborShare()).build();
	}

	/** Create the four markets and register them (labor market first). */
	public void createMarkets() {
		enjoymentMkt = new ConsumerGoodMarket("Enjoyment", cfg.ePrice().min(),
				cfg.ePrice().max(), colony);
		necessityMkt = new ConsumerGoodMarket("Necessity", cfg.nPrice().min(),
				cfg.nPrice().max(), colony);
		laborMkt = new LaborMarket(colony);
		capitalMkt = new CapitalMarket(colony);
		colony.addMarket(laborMkt);
		colony.addMarket(enjoymentMkt);
		colony.addMarket(necessityMkt);
		colony.addMarket(capitalMkt);
	}

	/**
	 * Create a bank from <tt>bankConfig</tt>, register it, and return it.
	 */
	public Bank addBank(BankConfig bankConfig) {
		Bank bank = new Bank(bankConfig, colony);
		banks.add(bank);
		colony.addBank(bank);
		return bank;
	}

	/**
	 * Override the consumer-good firms' behavioral parameters (default {@link
	 * FirmConfig#DEFAULT}). Must be called before {@link #createFirms} to take
	 * effect.
	 *
	 * @param firmConfig
	 *            the firm parameters to use for the enjoyment and necessity firms
	 */
	public void setFirmConfig(FirmConfig firmConfig) {
		this.firmConfig = firmConfig;
	}

	/**
	 * Create the capital firm (banking at <tt>capitalFirmBank</tt>) and the
	 * consumer-good firms, then add them to the colony. The bank and initial
	 * savings of each consumer-good firm are supplied by the caller (by index);
	 * everything else comes from the config.
	 */
	public void createFirms(Bank capitalFirmBank, IntFunction<Bank> firmBank,
			IntToDoubleFunction eSavings, IntToDoubleFunction nSavings) {
		CFirm cFirm = new CFirm(cfg.cFirm().checking(), cfg.cFirm().savings(),
				cfg.cFirm().wageBudget(), capitalFirmBank, colony);
		capitalFirms = new CFirm[] { cFirm };

		eFirms = new EFirm[cfg.numEFirms()];
		for (int i = 0; i < cfg.numEFirms(); i++)
			eFirms[i] = new EFirm(cfg.eFirm().checking(),
					eSavings.applyAsDouble(i), cfg.eFirm().output(),
					cfg.eFirm().wageBudget(), cfg.eFirm().capital(),
					capitalFirms, firmConfig, firmBank.apply(i), colony);

		nFirms = new NFirm[cfg.numNFirms()];
		for (int i = 0; i < cfg.numNFirms(); i++)
			nFirms[i] = new NFirm(cfg.nFirm().checking(),
					nSavings.applyAsDouble(i), cfg.nFirm().output(),
					cfg.nFirm().wageBudget(), cfg.nFirm().capital(),
					capitalFirms, firmConfig, firmBank.apply(i), colony);

		colony.addAgent(cFirm);
		for (NFirm f : nFirms)
			colony.addAgent(f);
		for (EFirm f : eFirms)
			colony.addAgent(f);
	}

	/**
	 * Create the dedicated noble-only labor market the {@link StrategicFirm}
	 * employs from, and register it. Must be called <em>before</em> the strategic
	 * firm and the nobles are created (both look it up by name), and before
	 * {@link #primeNobleLabor()}.
	 *
	 * @return the created noble labor market
	 */
	public LaborMarket createNobleLaborMarket() {
		nobleLaborMkt = new LaborMarket(StrategicFirm.LABOR_MARKET, colony);
		colony.addMarket(nobleLaborMkt);
		return nobleLaborMkt;
	}

	/**
	 * Clear the noble labor market once before the run, so the strategic firm has
	 * its noble workers in step 0 (the analogue of the pre-run labor clearing in
	 * {@link #createLaborers}). Call after the strategic firm and all nobles have
	 * been created (so their constructor-time postings are present).
	 */
	public void primeNobleLabor() {
		nobleLaborMkt.clear();
	}

	/**
	 * Create the colony's single export firm (banking at <tt>bank</tt>) and add
	 * it to the colony. Must be called <em>before</em> {@link #createLaborers} so
	 * the firm is a registered employer for the one pre-run labor clearing (and
	 * thus has workers in step 0, like the other firms).
	 *
	 * @param bank
	 *            the bank at which the export firm holds its accounts and into
	 *            whose equity its export earnings flow
	 * @param config
	 *            the export firm's parameters
	 * @return the created strategic firm
	 */
	public StrategicFirm createStrategicFirm(Bank bank,
			StrategicFirmConfig config) {
		strategicFirm = new StrategicFirm(config, bank, colony);
		colony.addAgent(strategicFirm);
		return strategicFirm;
	}

	/**
	 * Create the laborers and add them to the colony, then clear the labor
	 * market once so firms have workers before step 0. The bank, initial
	 * necessity stock and initial savings of each laborer are supplied by the
	 * caller (by index).
	 */
	public void createLaborers(IntFunction<Bank> laborerBank,
			IntToDoubleFunction initN, IntToDoubleFunction savings) {
		laborers = new Laborer[cfg.numLaborers()];
		for (int i = 0; i < cfg.numLaborers(); i++) {
			laborers[i] = new Laborer(cfg.laborer().e(), initN.applyAsDouble(i),
					cfg.laborer().checking(), savings.applyAsDouble(i),
					cfg.laborer().savingsRate(), LaborerConfig.DEFAULT,
					laborerBank.apply(i), colony);
			colony.addAgent(laborers[i]);
		}

		// when a household's head dies, a successor household continues the same
		// dynasty at the same bank, inheriting the estate (so money and the
		// labor force stay roughly constant)
		colony.addReplacementPolicy(dead -> {
			if (!(dead instanceof Laborer))
				return null;
			return new Laborer((Laborer) dead, cfg.laborer().e(),
					REPLACEMENT_NECESSITY_STOCK, cfg.laborer().savingsRate(),
					LaborerConfig.DEFAULT, colony);
		});

		laborMkt.clear();
	}

	/**
	 * Open the colony through <tt>gatewayBank</tt>: external money flows into
	 * that bank's equity each step (a standalone per-step colony action), and
	 * the immigration policy funds one brand-new immigrant household out of
	 * equity for every {@code immigrationThreshold} accumulated — population
	 * growth bankrolled from outside. The simulation chooses which bank is the
	 * gateway. A no-op when {@code externalInflowPerStep} is 0 (closed colony).
	 *
	 * @param gatewayBank
	 *            the bank through which external money enters the colony
	 */
	public void enableExternalInflow(Bank gatewayBank) {
		if (cfg.externalInflowPerStep() <= 0)
			return;
		colony.addStepAction(() -> gatewayBank
				.injectExternalFunds(cfg.externalInflowPerStep()));
		colony.setImmigrationPolicy(() -> {
			List<Agent> immigrants = new ArrayList<>();
			while (gatewayBank.getEquity() >= cfg.immigrationThreshold()) {
				immigrants.add(new Laborer(cfg.laborer().e(),
						REPLACEMENT_NECESSITY_STOCK, cfg.immigrationThreshold(),
						0, cfg.laborer().savingsRate(), LaborerConfig.DEFAULT,
						gatewayBank, colony, true));
			}
			return immigrants;
		});
	}

	/** Register the printers common to every simulation. */
	public void addCommonPrinters() {
		colony.addPrinter(new LaborersPrinter("Laborer"));
		colony.addPrinter(
				new ConsumerMktPricePrinter("EPrice", enjoymentMkt));
		colony.addPrinter(
				new ConsumerMktVolPrinter("EVol", enjoymentMkt));
		colony.addPrinter(new FirmsPrinter("EFirms", eFirms));
		colony.addPrinter(
				new ConsumerMktPricePrinter("NPrice", necessityMkt));
		colony.addPrinter(
				new ConsumerMktVolPrinter("NVol", necessityMkt));
		colony.addPrinter(new FirmsPrinter("NFirms", nFirms));
	}

	/** Register a {@link BankPrinter} writing to <tt>fileName</tt>. */
	public void addBankPrinter(String fileName, Bank bank) {
		colony.addPrinter(new BankPrinter(fileName, bank));
	}

	/** Run the simulation for the configured number of steps, then clean up. */
	public void run() {
		colony.run(cfg.numStep());
		colony.cleanUpPrinters();
	}

	/** Number of the <em>initial</em> laborers still alive after the run. */
	public long aliveLaborerCount() {
		long n = 0;
		for (Laborer l : laborers)
			if (l.isAlive())
				n++;
		return n;
	}

	/**
	 * Number of laborers (households) currently alive in the colony, including
	 * replacements that succeeded the initial cohort.
	 */
	public long currentLaborerCount() {
		return colony.getAgents().stream().filter(a -> a instanceof Laborer)
				.count();
	}
}
