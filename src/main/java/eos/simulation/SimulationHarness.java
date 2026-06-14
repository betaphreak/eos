package eos.simulation;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;
import java.util.function.IntToDoubleFunction;

import eos.agent.Agent;
import eos.agent.firm.BuilderConfig;
import eos.agent.firm.BuilderFirm;
import eos.agent.firm.CFirm;
import eos.agent.firm.EFirm;
import eos.agent.firm.FirmConfig;
import eos.agent.firm.NFirm;
import eos.agent.firm.StrategicFirm;
import eos.agent.firm.StrategicFirmConfig;
import eos.agent.Member;
import eos.agent.PeasantPool;
import eos.agent.laborer.Laborer;
import eos.agent.laborer.LaborerConfig;
import eos.agent.noble.Noble;
import eos.agent.noble.NobleConfig;
import eos.agent.ruler.Ruler;
import eos.bank.Bank;
import eos.bank.BankConfig;
import eos.bank.CurrencyType;
import eos.name.Person;
import eos.settlement.Settlement;
import eos.settlement.GameSession;
import eos.io.SimLog;
import eos.io.printer.*;
import eos.market.*;
import lombok.AccessLevel;
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

	/**
	 * Number of noble households the default export sector creates to staff its
	 * {@link StrategicFirm} (see {@link #createDefaultStrategicSector(Bank)}).
	 */
	public static final int DEFAULT_NUM_NOBLES = 5;

	/** Opening savings (seed fortune) of each default-sector noble. */
	public static final double DEFAULT_NOBLE_SAVINGS = 1000;

	/** The default ruler's opening fortune, in <b>gold</b> (see {@link #createDefaultRuler()}). */
	public static final double DEFAULT_RULER_GOLD = 10;

	/**
	 * Fraction of its treasury the default ruler spends on enjoyment each step — a
	 * small rate, so the sovereign's luxury habit draws the reserves down gradually
	 * rather than exhausting them.
	 */
	public static final double DEFAULT_RULER_CONSUMPTION_RATE = 0.0002;

	/**
	 * Currency-exchange (FX) fee charged by the default non-copper money-changer
	 * banks ({@link #getSilverBank()} / {@link #getGoldBank()}) on every payment
	 * crossing the copper boundary; the copper bank, being the base currency,
	 * charges nothing.
	 */
	public static final double DEFAULT_EXCHANGE_FEE_RATE = 0.02;

	private final SimulationConfig cfg;
	private final Settlement colony;
	private final List<Bank> banks = new ArrayList<>();

	// the default tiered banks, created lazily on first request (so a commoner-only
	// settlement that never asks for silver/gold carries neither). Excluded from
	// the class-level @Getter — the accessors below add the lazy construction.
	@Getter(AccessLevel.NONE)
	private Bank copperBank;
	@Getter(AccessLevel.NONE)
	private Bank silverBank;
	@Getter(AccessLevel.NONE)
	private Bank goldBank;

	// behavioral parameters for the consumer-good firms; defaults to the
	// canonical values with the run's labor-share applied (see constructor).
	// Replace via setFirmConfig before createFirms to vary other firm params.
	private FirmConfig firmConfig;

	// necessity (food) firms run a higher technology coefficient than the other
	// consumer firms: because production stops on the weekly day of rest and on
	// feast days (~79 days/year) while the population eats every day, food output
	// on the ~286 working days must cover all 365 days' consumption. This factor
	// lifts the necessity firms' Cobb-Douglas A so the colony can feed itself
	// across the rest-day calendar (see Firm.operatesOn / the day-type wiring).
	public static final double NECESSITY_TECH_FACTOR = 2.0;

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
	private BuilderFirm builderFirm;
	private PeasantPool peasantPool;

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
		Settlement colony = session.newSettlement(cfg.settlementName(),
				cfg.startDate(), cfg.meanInitAgeYears(), cfg.targetNStock(),
				cfg.meanSkill(), cfg.latitude(), cfg.longitude());
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

	/*
	 * The default tiered banking system, mirroring the social hierarchy: commoners
	 * (laborers and firms) bank in copper, nobles in silver, the ruler in gold.
	 * Copper is the base currency (prices are quoted in it) and charges no FX fee;
	 * silver and gold are money-changers that skim DEFAULT_EXCHANGE_FEE_RATE on
	 * every payment crossing the copper boundary. Each is created on first request
	 * and shared thereafter, so a settlement carries only the tiers its classes
	 * need (a commoner-only colony never creates silver or gold). Request copper
	 * before silver/gold so the banks are numbered/ordered copper, silver, gold.
	 */

	/**
	 * The colony's default <b>copper</b> bank — the base-currency, zero-profit
	 * intermediary where commoners (laborers and firms) hold their accounts.
	 *
	 * @return the shared copper bank (created on first call)
	 */
	public Bank getCopperBank() {
		if (copperBank == null)
			copperBank = addBank(BankConfig.DEFAULT);
		return copperBank;
	}

	/**
	 * The colony's default <b>silver</b> bank — the nobles' money-changer, charging
	 * the {@value #DEFAULT_EXCHANGE_FEE_RATE} FX fee on payments crossing the copper
	 * boundary (so it profits from the nobles' copper-quoted dividends and
	 * purchases).
	 *
	 * @return the shared silver bank (created on first call)
	 */
	public Bank getSilverBank() {
		if (silverBank == null)
			silverBank = addBank(BankConfig.DEFAULT.toBuilder()
					.currency(CurrencyType.SILVER)
					.exchangeFeeRate(DEFAULT_EXCHANGE_FEE_RATE).build());
		return silverBank;
	}

	/**
	 * The colony's default <b>gold</b> bank — the ruler's money-changer (same FX
	 * fee as silver).
	 *
	 * @return the shared gold bank (created on first call)
	 */
	public Bank getGoldBank() {
		if (goldBank == null)
			goldBank = addBank(BankConfig.DEFAULT.toBuilder()
					.currency(CurrencyType.GOLD)
					.exchangeFeeRate(DEFAULT_EXCHANGE_FEE_RATE).build());
		return goldBank;
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

		// necessity firms get a higher technology coefficient (see
		// NECESSITY_TECH_FACTOR) so food output on working days covers the rest
		// days when production stops; everything else matches the other firms.
		FirmConfig nFirmConfig = firmConfig.toBuilder()
				.A(firmConfig.A() * NECESSITY_TECH_FACTOR).build();
		nFirms = new NFirm[cfg.numNFirms()];
		for (int i = 0; i < cfg.numNFirms(); i++)
			nFirms[i] = new NFirm(cfg.nFirm().checking(),
					nSavings.applyAsDouble(i), cfg.nFirm().output(),
					cfg.nFirm().wageBudget(), cfg.nFirm().capital(),
					capitalFirms, nFirmConfig, firmBank.apply(i), colony);

		// add each firm to the colony and place it on an effective build slot; the
		// colony grows itself just enough to hold them all (founded at the floor
		// size). Slot placement is pure bookkeeping, so runs stay byte-identical.
		colony.addAgent(cFirm);
		colony.claimSlot(cFirm);
		for (NFirm f : nFirms) {
			colony.addAgent(f);
			colony.claimSlot(f);
		}
		for (EFirm f : eFirms) {
			colony.addAgent(f);
			colony.claimSlot(f);
		}
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
		colony.claimSlot(strategicFirm);
		return strategicFirm;
	}

	/**
	 * Give the colony its default <b>export sector</b>, so every settlement has
	 * one: the noble-only labor market, the single {@link StrategicFirm} (banking
	 * at <tt>bank</tt>, into whose equity its export earnings flow), the {@value
	 * #DEFAULT_NUM_NOBLES} worker-nobles that staff it (banking at the same
	 * <tt>bank</tt>), and the primed noble labor market — bundling the steps {@link
	 * StrategicEconomy} performs by hand. Mirror that simulation's order: call this
	 * after {@link #createFirms} and <em>before</em> {@link #createLaborers}.
	 * <p>
	 * This creates fresh nobles. A colony that already has its own nobles (e.g.
	 * {@link AristocraticEconomy}) should instead call the granular {@link
	 * #createNobleLaborMarket()} / {@link #createStrategicFirm} before its nobles
	 * and {@link #primeNobleLabor()} after them — its existing nobles then staff
	 * the export firm (a {@link Noble} automatically works any noble labor market
	 * present).
	 *
	 * @param bank
	 *            the bank at which the export firm and its nobles hold accounts
	 */
	public void createDefaultStrategicSector(Bank bank) {
		createNobleLaborMarket();
		createStrategicFirm(bank, StrategicFirmConfig.DEFAULT);
		for (int n = 0; n < DEFAULT_NUM_NOBLES; n++)
			colony.addAgent(new Noble(0, DEFAULT_NOBLE_SAVINGS, List.of(),
					List.of(), NobleConfig.DEFAULT, bank, colony));
		// a noble's same-dynasty successor (which keeps working the export sector)
		// is produced by the colony's built-in household-succession policy (see
		// Noble.successor), so no rule is wired here
		primeNobleLabor();
	}

	/**
	 * Give the colony its default <b>ruler</b>, so every settlement has a sovereign:
	 * a {@link Ruler} banking in gold (so this also lazily creates the colony's gold
	 * bank), holding {@value #DEFAULT_RULER_GOLD} gold and spending it down on
	 * enjoyment at {@value #DEFAULT_RULER_CONSUMPTION_RATE} per step — which converts
	 * gold to copper and so turns the gold bank's FX fee. A same-dynasty heir
	 * succeeds it when its head dies of old age. Create the ruler <em>last</em>
	 * (after the commoners and any nobles) so its demographic draws don't perturb
	 * theirs.
	 *
	 * @return the gold bank the ruler owns and banks at (so the caller can register
	 *         a {@link BankPrinter} for it)
	 */
	public Bank createDefaultRuler() {
		Bank gold = getGoldBank();
		Ruler ruler = new Ruler(CurrencyType.GOLD.toCopper(DEFAULT_RULER_GOLD),
				DEFAULT_RULER_CONSUMPTION_RATE, cfg.bankProfitTaxRate(),
				cfg.nobleIncomeTaxRate(), gold, colony);
		colony.addAgent(ruler);
		// record the sovereign so a builder can bill it for public works (the roads
		// and walls of a growth ring); a no-op for any colony that never grows
		colony.setRuler(ruler);
		// a same-dynasty heir succeeds the ruler via the colony's built-in
		// household-succession policy (see Ruler.successor, which also keeps the
		// colony's ruler reference current); no rule is wired here
		return gold;
	}

	/**
	 * Give the colony its <b>peasant pool</b> (banking in copper), seeded with
	 * {@code cfg.peasantReserveSize()} peasants the {@link eos.agent.ruler.Ruler}
	 * feeds. A no-op (returns {@code null}) when the reserve size is 0, so the pool
	 * is opt-in per simulation. Requires the necessity market (see {@link
	 * #createMarkets()}) and a ruler (see {@link #createDefaultRuler()}) to exist
	 * first; create it <em>last</em> (after the laborers and the ruler) so its
	 * demographic/naming draws don't perturb theirs.
	 *
	 * @return the created peasant pool, or {@code null} if none was configured
	 */
	public PeasantPool createDefaultPeasantPool() {
		if (cfg.peasantReserveSize() <= 0)
			return null;
		peasantPool = new PeasantPool(cfg.peasantReserveSize(), getCopperBank(),
				colony);
		colony.addAgent(peasantPool);
		return peasantPool;
	}

	/**
	 * Register a {@link PeasantPrinter} for the colony's peasant pool. The pool must
	 * already exist (see {@link #createDefaultPeasantPool()}).
	 *
	 * @param fileName
	 *            the CSV output file name
	 */
	public void addPeasantPrinter(String fileName) {
		colony.addPrinter(new PeasantPrinter(fileName, peasantPool));
	}

	/**
	 * Give the colony a <b>builder</b> (banking at <tt>bank</tt>) and place it on an
	 * effective slot at founding, like the other firms. Registering a builder
	 * changes how the colony grows: once it is running, the builder is the only way
	 * it gets bigger — a slot demand it cannot satisfy is built (firm-funded land,
	 * ruler-funded roads and walls) rather than granted instantly. A colony with a
	 * builder therefore also needs a ruler (its public-works sponsor; see {@link
	 * #createDefaultRuler()}).
	 *
	 * @param bank
	 *            the bank at which the builder holds its account
	 * @param config
	 *            the builder's parameters
	 * @return the created builder
	 */
	public BuilderFirm createBuilder(Bank bank, BuilderConfig config) {
		// the builder is staffed exclusively by peasants, on a dedicated labor
		// market (its workers are supplied by the colony's peasant pool); create it
		// before the builder, which looks it up. A builder colony therefore also
		// needs a peasant pool (see createDefaultPeasantPool).
		if (colony.getMarket(BuilderFirm.LABOR_MARKET) == null)
			colony.addMarket(new LaborMarket(BuilderFirm.LABOR_MARKET, colony));
		builderFirm = new BuilderFirm(config, bank, colony);
		colony.addAgent(builderFirm);
		colony.claimSlot(builderFirm);
		return builderFirm;
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

		// register how a dead laborer is replaced. By default a successor household
		// continues the same dynasty at the same bank, inheriting the estate (so
		// money and the labor force stay roughly constant). When the run opts into
		// pool promotion, the ruler instead elevates the ablest peasant into a fresh
		// laborer household — merit-based mobility — and an empty pool yields no
		// replacement, so the labor force declines as the reserve drains.
		if (cfg.promoteLaborersFromPool())
			colony.addReplacementPolicy(dead -> {
				if (!(dead instanceof Laborer) || peasantPool == null)
					return null;
				Member peasant = peasantPool.promoteHighestSkilled();
				return peasant == null ? null
						: promoteToLaborer(peasant, ((Laborer) dead).getBank());
			});
		else
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
	 * Build a laborer household for a peasant promoted out of the pool: it adopts
	 * the peasant as its head (keeping its given name, skills and age) under a
	 * freshly-drawn dynasty surname, opens with the laborer config's balances, and
	 * those balances are <b>funded by the ruler</b> (debited from the treasury,
	 * borrowing if short — so the money has a counterparty rather than appearing from
	 * nowhere; the dead laborer's estate stays folded into equity as before).
	 *
	 * @param peasant
	 *            the promoted peasant to adopt as the new household's head
	 * @param bank
	 *            the bank the new laborer holds its accounts at (the dead one's)
	 * @return the promoted laborer household
	 */
	private Laborer promoteToLaborer(Member peasant, Bank bank) {
		// keep the peasant's given name, skills and age; give it a fresh dynasty
		// surname (it carried none while pooled)
		String surname = colony.getNames().nextDynastyName();
		Member head = new Member(
				new Person(peasant.person().givenName(), surname, peasant.skills()),
				peasant.getBirthDate());
		double checking = cfg.laborer().checking();
		double savings = cfg.laborer().savings();
		Laborer laborer = new Laborer(head, cfg.laborer().e(),
				REPLACEMENT_NECESSITY_STOCK, checking, savings,
				cfg.laborer().savingsRate(), LaborerConfig.DEFAULT, bank, colony);
		// the ruler capitalizes the new household (borrowing if its treasury is
		// short); skip only during an interregnum (a dead ruler has no account)
		Ruler ruler = colony.getRuler();
		if (ruler != null && ruler.isAlive())
			ruler.getBank().withdraw(ruler.getID(), checking + savings);
		return laborer;
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
		addCommonPrinters("");
	}

	/**
	 * Register the common printers with <tt>prefix</tt> prepended to every output
	 * filename. Several colonies in one session write to the same {@code output/}
	 * directory, so a per-settlement prefix (e.g. {@code "Lubeck-"}) keeps their
	 * CSVs from colliding.
	 *
	 * @param prefix
	 *            prepended to each printer's filename ({@code ""} for the default)
	 */
	public void addCommonPrinters(String prefix) {
		colony.addPrinter(new LaborersPrinter(prefix + "Laborer"));
		colony.addPrinter(
				new ConsumerMktPricePrinter(prefix + "EPrice", enjoymentMkt));
		colony.addPrinter(
				new ConsumerMktVolPrinter(prefix + "EVol", enjoymentMkt));
		colony.addPrinter(new FirmsPrinter(prefix + "EFirms", eFirms));
		colony.addPrinter(
				new ConsumerMktPricePrinter(prefix + "NPrice", necessityMkt));
		colony.addPrinter(
				new ConsumerMktVolPrinter(prefix + "NVol", necessityMkt));
		colony.addPrinter(new FirmsPrinter(prefix + "NFirms", nFirms));
	}

	/** Register a {@link BankPrinter} writing to <tt>fileName</tt>. */
	public void addBankPrinter(String fileName, Bank bank) {
		colony.addPrinter(new BankPrinter(fileName, bank));
	}

	/**
	 * Register a {@link BuilderPrinter} for the colony's builder, charting the
	 * colony's size and the builder's construction activity. The builder must
	 * already exist (see {@link #createBuilder}).
	 *
	 * @param fileName
	 *            the CSV output file name
	 */
	public void addBuilderPrinter(String fileName) {
		colony.addPrinter(new BuilderPrinter(fileName, builderFirm));
	}

	/**
	 * Register a {@link StrategicPrinter} for the colony's export firm, reporting
	 * equity in <tt>bank</tt>'s currency. The strategic firm must already exist.
	 *
	 * @param fileName
	 *            the CSV output file name
	 * @param bank
	 *            the bank whose equity the export earnings flow into
	 */
	public void addStrategicPrinter(String fileName, Bank bank) {
		colony.addPrinter(new StrategicPrinter(fileName, strategicFirm, bank));
	}

	/**
	 * Register the export sector's printers — the {@link StrategicPrinter}, the
	 * {@link NoblesPrinter} and the {@link PersonsOfInterestPrinter} — for a colony
	 * whose nobles are the default export workforce (and so has no other noble
	 * printers). The filenames are prefixed with <tt>prefix</tt> (see {@link
	 * #addCommonPrinters(String)}).
	 *
	 * @param prefix
	 *            prepended to each printer's filename ({@code ""} for the default)
	 * @param bank
	 *            the bank whose equity the export earnings flow into
	 */
	public void addStrategicSectorPrinters(String prefix, Bank bank) {
		colony.addPrinter(
				new StrategicPrinter(prefix + "Strategic", strategicFirm, bank));
		colony.addPrinter(new NoblesPrinter(prefix + "Nobles"));
		colony.addPrinter(
				new PersonsOfInterestPrinter(prefix + "PersonsOfInterest"));
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
