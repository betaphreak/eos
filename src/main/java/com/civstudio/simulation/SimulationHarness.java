package com.civstudio.simulation;

import java.time.DayOfWeek;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;
import java.util.function.IntToDoubleFunction;

import com.civstudio.era.Era;
import com.civstudio.io.printer.*;
import com.civstudio.market.*;
import com.civstudio.geo.Province;
import com.civstudio.race.Race;

import com.civstudio.agent.Agent;
import com.civstudio.agent.Caravan;
import com.civstudio.agent.MigrantCaravan;
import com.civstudio.agent.Household;
import com.civstudio.agent.firm.BuilderConfig;
import com.civstudio.agent.firm.BuilderFirm;
import com.civstudio.agent.firm.CFirm;
import com.civstudio.agent.firm.ConsumerGoodFirm;
import com.civstudio.agent.firm.EFirm;
import com.civstudio.agent.firm.FirmConfig;
import com.civstudio.agent.firm.FirmFactory;
import com.civstudio.agent.firm.NFirm;
import com.civstudio.agent.firm.ScienceConfig;
import com.civstudio.agent.firm.ScienceFirm;
import com.civstudio.agent.firm.StrategicFirm;
import com.civstudio.agent.firm.StrategicFirmConfig;
import com.civstudio.agent.Member;
import com.civstudio.agent.Retinue;
import com.civstudio.agent.Rank;
import com.civstudio.agent.RankLadder;
import com.civstudio.agent.laborer.Laborer;
import com.civstudio.agent.laborer.LaborerConfig;
import com.civstudio.agent.noble.Noble;
import com.civstudio.agent.noble.NobleConfig;
import com.civstudio.agent.ruler.Ruler;
import com.civstudio.tech.ResearchState;
import com.civstudio.bank.Bank;
import com.civstudio.bank.BankConfig;
import com.civstudio.bank.CurrencyType;
import com.civstudio.name.Person;
import com.civstudio.settlement.Settlement;
import com.civstudio.skill.Skill;
import com.civstudio.settlement.GameSession;
import com.civstudio.io.SimLog;
import com.civstudio.io.printer.*;
import com.civstudio.market.*;
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

	// a noble insolvent (a net debtor) for this many consecutive days is "ruined"
	// and demoted back to a laborer (see demoteRuinedNobles). A one-year grace (as
	// for MIN_FIRM_LIFETIME_DAYS) lets a cash-poor noble — e.g. one just ennobled
	// from an indebted laborer — earn its way back into the black before then.
	// A placeholder pending calibration.
	private static final int NOBLE_INSOLVENCY_GRACE_DAYS = 365;

	/**
	 * Number of noble households the default export sector creates to staff its
	 * {@link StrategicFirm} (see {@link #createDefaultStrategicSector(Bank)}).
	 */
	public static final int DEFAULT_NUM_NOBLES = 5;

	/** Opening savings (seed fortune) of each default-sector noble. */
	public static final double DEFAULT_NOBLE_SAVINGS = 1000;

	/**
	 * The default ruler's opening fortune, in <b>gold</b> (see {@link
	 * #createDefaultRuler()}). Sized so the sovereign can capitalize the whole
	 * initial labor force on day 0 when founding through the pool (each laborer's
	 * skill-sum endowment, ~60 copper), with surplus left as its standing treasury:
	 * 50 gold = 60 000 copper comfortably covers the default founding (with the
	 * default promotionRatio the ruler promotes ~405 of the 900-peasant pool, ~24 300
	 * copper).
	 */
	public static final double DEFAULT_RULER_GOLD = 50;

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

	// the necessity firms' config (firmConfig with the necessity tech factor
	// applied), set in createFirms and reused when the dynamic provisioning
	// charters a new necessity firm so it matches the founding ones.
	private FirmConfig nFirmConfig;

	// the bank a dynamically chartered firm holds its accounts at, captured from
	// createFirms; createDefaultRuler installs the dynamic provisioning factory with
	// it, so every ruler-bearing colony grows its firms by default.
	private Bank charteredFirmBank;

	// parameters for the wedding market; defaults to the canonical values.
	// Replace via setWeddingConfig before createMarkets (e.g. capacity 0 to
	// disable weddings in a test that isolates another mechanism).
	private WeddingConfig weddingConfig = WeddingConfig.DEFAULT;

	// parameters for nobles raised by ennoblement (the export aristocracy is now
	// built from laborers, not created up front); defaults to the canonical values.
	// Replace via setNobleConfig before createDefaultRuler (e.g. to give a colony's
	// nobles a necessity reserve, as HanseaticEconomy does).
	private NobleConfig nobleConfig = NobleConfig.DEFAULT;

	// the social-mobility engine for this colony (promotion/demotion across ranks),
	// built lazily on first use with the realized ranks' factories registered (see
	// rankLadder()). Today only ennoblement (HOUSEHOLD -> HOLDING) uses it.
	private RankLadder rankLadder;

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
	private WeddingMarket weddingMkt;

	private CFirm[] capitalFirms;
	private EFirm[] eFirms;
	private NFirm[] nFirms;
	private Laborer[] laborers;
	private StrategicFirm strategicFirm;
	private BuilderFirm builderFirm;
	private Retinue retinue;

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
				cfg.meanSkillMale(), cfg.meanSkillFemale(), cfg.latitude(),
				cfg.longitude());
		SimLog.init(colony);
		return new SimulationHarness(cfg, colony);
	}

	/**
	 * Build an empty harness as {@link #create(SimulationConfig, long)} but with an
	 * explicit {@link Race founding race} (selecting the colony's calendar and tech
	 * overlay) and a per-person {@code raceMix} (the weights each generated person —
	 * pool peasants, immigrants — is rolled against). A mono-cultural human run uses
	 * the other overload; see {@code docs/race.md}.
	 *
	 * @param cfg
	 *            the run configuration
	 * @param seed
	 *            the random-number seed for this run
	 * @param foundingRace
	 *            the colony's founding (ruler's) race
	 * @param raceMix
	 *            race &rarr; weight every generated person is rolled against
	 * @return an empty harness ready to be populated
	 */
	public static SimulationHarness create(SimulationConfig cfg, long seed,
			Race foundingRace, Map<Race, Double> raceMix) {
		GameSession session = new GameSession(seed);
		Settlement colony = session.newSettlement(cfg.settlementName(),
				cfg.startDate(), cfg.meanInitAgeYears(), cfg.targetNStock(),
				cfg.meanSkillMale(), cfg.meanSkillFemale(), cfg.latitude(),
				cfg.longitude(), foundingRace, raceMix);
		SimLog.init(colony);
		return new SimulationHarness(cfg, colony);
	}

	/**
	 * Build an empty harness as {@link #create(SimulationConfig, long)} but founded
	 * <b>into a {@link Province}</b> of the session's world map: the province
	 * supplies the colony's latitude/longitude (its climate) and its {@code plots}
	 * cap the settlement's size (see {@code docs/geography.md}). The config's
	 * {@code latitude}/{@code longitude} are ignored in favour of the province's.
	 *
	 * @param cfg      the run configuration
	 * @param seed     the random-number seed for this run
	 * @param province the province to found the colony into
	 * @return an empty harness ready to be populated
	 */
	public static SimulationHarness create(SimulationConfig cfg, long seed,
			Province province) {
		GameSession session = new GameSession(seed);
		Settlement colony = session.newSettlement(cfg.settlementName(),
				cfg.startDate(), cfg.meanInitAgeYears(), cfg.targetNStock(),
				cfg.meanSkillMale(), cfg.meanSkillFemale(), province);
		SimLog.init(colony);
		return new SimulationHarness(cfg, colony);
	}

	/**
	 * Build an empty harness founded into the world-map province with this
	 * {@code provinceId} (resolved from the fresh session's {@link
	 * GameSession#getWorldMap() world map}) — the convenience used by a scenario
	 * that founds into a known province (e.g. the default {@code HomogeneousEconomy}
	 * into Dhenijansar). See {@link #create(SimulationConfig, long, Province)} and
	 * {@code docs/geography.md}.
	 *
	 * @param cfg        the run configuration
	 * @param seed       the random-number seed for this run
	 * @param provinceId the {@code province_id} of the province to found into
	 * @return an empty harness ready to be populated
	 */
	public static SimulationHarness create(SimulationConfig cfg, long seed,
			int provinceId) {
		GameSession session = new GameSession(seed);
		Settlement colony = session.newSettlement(cfg.settlementName(),
				cfg.startDate(), cfg.meanInitAgeYears(), cfg.targetNStock(),
				cfg.meanSkillMale(), cfg.meanSkillFemale(),
				session.getWorldMap().province(provinceId));
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

	/** Create the markets and register them (labor market first). */
	public void createMarkets() {
		enjoymentMkt = new ConsumerGoodMarket("Enjoyment", cfg.ePrice().min(),
				cfg.ePrice().max(), colony);
		necessityMkt = new ConsumerGoodMarket("Necessity", cfg.nPrice().min(),
				cfg.nPrice().max(), colony);
		laborMkt = new LaborMarket(colony);
		capitalMkt = new CapitalMarket(colony);
		// the wedding market is harmless without a pool (it then clears to nothing),
		// so every colony carries one; the pool registers with it when created
		weddingMkt = new WeddingMarket(colony, weddingConfig);
		colony.addMarket(laborMkt);
		colony.addMarket(enjoymentMkt);
		colony.addMarket(necessityMkt);
		colony.addMarket(capitalMkt);
		colony.addMarket(weddingMkt);
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
		if (copperBank == null) {
			copperBank = addBank(BankConfig.DEFAULT);
			// the commoners' bank has no single owner — name it for its clientele
			copperBank.setName("Commoners' Bank");
		}
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
		if (silverBank == null) {
			silverBank = addBank(BankConfig.DEFAULT.toBuilder()
					.currency(CurrencyType.SILVER)
					.exchangeFeeRate(DEFAULT_EXCHANGE_FEE_RATE).build());
			// the nobles' shared money-changer; a noble that comes to own it is
			// renamed after its house (see Noble's constructor)
			silverBank.setName("Nobles' Bank");
		}
		return silverBank;
	}

	/**
	 * The colony's default <b>gold</b> bank — the ruler's money-changer (same FX
	 * fee as silver).
	 *
	 * @return the shared gold bank (created on first call)
	 */
	public Bank getGoldBank() {
		if (goldBank == null) {
			goldBank = addBank(BankConfig.DEFAULT.toBuilder()
					.currency(CurrencyType.GOLD)
					.exchangeFeeRate(DEFAULT_EXCHANGE_FEE_RATE).build());
			// the crown's bank; renamed after the ruling house once the ruler exists
			// (see installRuler), but named for the crown until then
			goldBank.setName("Crown Bank");
		}
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
	 * Override the wedding-market parameters (default {@link WeddingConfig#DEFAULT}).
	 * Must be called before {@link #createMarkets()} to take effect. Pass a config
	 * with {@code capacity == 0} to disable weddings entirely.
	 *
	 * @param weddingConfig
	 *            the wedding parameters to use
	 */
	public void setWeddingConfig(WeddingConfig weddingConfig) {
		this.weddingConfig = weddingConfig;
	}

	/**
	 * Override the parameters of nobles raised by ennoblement (default {@link
	 * NobleConfig#DEFAULT}). Must be called before {@link #createDefaultRuler()} (which
	 * registers the aristocracy top-up) to take effect.
	 *
	 * @param nobleConfig
	 *            the parameters for ennobled nobles
	 */
	public void setNobleConfig(NobleConfig nobleConfig) {
		this.nobleConfig = nobleConfig;
	}

	/**
	 * Create the capital firm (banking at <tt>capitalFirmBank</tt>) and the
	 * consumer-good firms, then add them to the colony. The bank and initial
	 * savings of each consumer-good firm are supplied by the caller (by index);
	 * everything else comes from the config.
	 */
	public void createFirms(Bank capitalFirmBank, IntFunction<Bank> firmBank,
			IntToDoubleFunction eSavings, IntToDoubleFunction nSavings) {
		// a representative firm bank for any firm the dynamic provisioning charters
		// later (createDefaultRuler installs the factory with it)
		charteredFirmBank = firmBank.apply(0);
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
		// Stored on the harness so the dynamic provisioning can charter matching ones.
		nFirmConfig = firmConfig.toBuilder()
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
	 * Give the colony its default <b>export sector</b>, so every settlement has one:
	 * the noble-only labor market and the single {@link StrategicFirm} (banking at
	 * <tt>bank</tt>, into whose equity its export earnings flow). <b>No nobles are
	 * created up front</b> — the ruler works the strategic firm from day 0 (so it is
	 * never unstaffed) and the ablest laborers are ennobled up to {@code
	 * cfg.targetNobles()} over the first weeks (see {@link #createDefaultRuler()} /
	 * {@code topUpAristocracy}). Call this after {@link #createFirms} and
	 * <em>before</em> {@link #createDefaultRuler()}.
	 *
	 * @param bank
	 *            the bank at which the export firm holds its accounts
	 */
	public void createDefaultStrategicSector(Bank bank) {
		createNobleLaborMarket();
		createStrategicFirm(bank, StrategicFirmConfig.DEFAULT);
		// the nobles who staff the export firm are raised from the laborers by
		// ennoblement (they bank in silver); reserve the silver tier now so the banks
		// stay ordered copper, silver, gold even though no noble exists yet
		getSilverBank();
		primeNobleLabor();
		// install research + the science firm (see ensureResearchAndScience);
		// createDefaultRuler calls it too, so even a colony reaching a ruler without
		// going through this method (e.g. HanseaticEconomy) still researches.
		ensureResearchAndScience(bank);
	}

	/**
	 * Ensure the colony <b>researches the tech tree</b>: install a {@link ResearchState}
	 * and a {@link ScienceFirm} if it has neither yet — so <b>every ruler-bearing colony
	 * researches</b> (the ruler funds and directs research, the aristocracy staffs the
	 * science firm). Idempotent: a colony that already has research (the strategic-sector
	 * setup, or a re-founded band's carried research) is left untouched, so the call is
	 * safe to make from more than one founding path. Called by {@link
	 * #createDefaultStrategicSector} and, as the "if there are zero science firms the
	 * ruler creates one" guarantee, by {@link #createDefaultRuler}/{@link
	 * #createRulerFromLeader} before the {@link Ruler} is built (so the ruler finds the
	 * scholar labor market to work during the ennoblement ramp).
	 *
	 * @param bank the bank at which the science firm holds its account
	 */
	public void ensureResearchAndScience(Bank bank) {
		// the session's era sets the research baseline: a colony founding in era E knows
		// every tech up to E-below and warm-starts E's entry tech (TECH_E_LIFESTYLE), on
		// its founding race's tech tree (the shared graph under that race's effect
		// overlay — see docs/race.md)
		if (colony.getResearch() == null) {
			Era era = colony.getSession().getEra();
			ResearchState research = new ResearchState(
					colony.getSession().getTechTree(colony.getFoundingRace()), colony,
					era.below(), cfg.researchCostScale());
			research.seedInitialFocus("TECH_" + era.name() + "_LIFESTYLE",
					cfg.researchInitialFraction());
			colony.setResearch(research);
		}
		if (!hasScienceFirm())
			createScienceFirm(bank, ScienceConfig.DEFAULT);
	}

	// whether the colony already has a (living) science firm, so the research guarantee
	// does not charter a second one
	private boolean hasScienceFirm() {
		for (Agent a : colony.getAgents())
			if (a instanceof ScienceFirm)
				return true;
		return false;
	}

	/**
	 * Create the colony's single <b>science firm</b> (banking at <tt>bank</tt>) and
	 * add it, creating its dedicated {@value ScienceFirm#LABOR_MARKET} market first.
	 * The firm produces the colony's research points from the scholarly labor of the
	 * nobles and ruler, funded out of the ruler's treasury (see {@link ScienceFirm}).
	 *
	 * @param bank
	 *            the bank at which the science firm holds its account
	 * @param config
	 *            the science firm's parameters
	 * @return the created science firm
	 */
	public ScienceFirm createScienceFirm(Bank bank, ScienceConfig config) {
		if (colony.getMarket(ScienceFirm.LABOR_MARKET) == null)
			colony.addMarket(new LaborMarket(ScienceFirm.LABOR_MARKET, colony));
		ScienceFirm scienceFirm = new ScienceFirm(config, bank, colony);
		colony.addAgent(scienceFirm);
		colony.claimSlot(scienceFirm);
		return scienceFirm;
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
	 * @return the gold bank the ruler owns and banks at (the colony's banks are all
	 *         reported together by {@link #addBanksPrinter})
	 */
	public Bank createDefaultRuler() {
		// every ruler-bearing colony researches: if the colony has no science firm yet
		// (it did not go through createDefaultStrategicSector), the ruler founds one now,
		// before it is built, so it finds the scholar labor market to work
		ensureResearchAndScience(getCopperBank());
		Bank gold = getGoldBank();
		return installRuler(new Ruler(CurrencyType.GOLD.toCopper(DEFAULT_RULER_GOLD),
				DEFAULT_RULER_CONSUMPTION_RATE, cfg.bankProfitTaxRate(),
				cfg.nobleIncomeTaxRate(), gold, colony));
	}

	/**
	 * Install the band's leader as the ruler of a <b>re-founded</b> colony (the
	 * {@code CARAVAN → HOLDING} settle, see {@code docs/caravan.md}): the {@link
	 * Ruler} adopts <tt>leader</tt> as its head — so the same dynasty that led the band
	 * out rules the new settlement — and opens its gold treasury with the band's carried
	 * <tt>hoard</tt> (the founding capital that capitalizes the labor force). Wired
	 * exactly like {@link #createDefaultRuler()} otherwise (dynamic firm provisioning,
	 * the ennoblement top-up, the ruin demotion).
	 *
	 * @param leader
	 *            the band's leader, adopted as the new colony's sovereign head
	 * @param hoard
	 *            the band's carried money (copper), the ruler's opening treasury
	 * @return the gold bank the ruler owns and banks at
	 */
	public Bank createRulerFromLeader(Member leader, double hoard) {
		// guarantee research (idempotent — the re-founding path's strategic sector
		// already installed it), before the ruler is built so it finds the scholar market
		ensureResearchAndScience(getCopperBank());
		Bank gold = getGoldBank();
		return installRuler(new Ruler(leader, hoard, DEFAULT_RULER_CONSUMPTION_RATE,
				cfg.bankProfitTaxRate(), cfg.nobleIncomeTaxRate(), gold, colony));
	}

	// register a freshly-built ruler with the colony and wire the standard sovereign
	// behaviours, shared by the founding (createDefaultRuler) and re-founding
	// (createRulerFromLeader) paths.
	private Bank installRuler(Ruler ruler) {
		colony.addAgent(ruler);
		// name the crown's gold bank after the ruling house (a same-dynasty heir keeps
		// the surname, so the name carries across successions)
		ruler.getBank().setName(ruler.getHead().surname() + " Bank");
		// record the sovereign so a builder can bill it for public works (the roads
		// and walls of a growth ring); a no-op for any colony that never grows
		colony.setRuler(ruler);
		// a same-dynasty heir succeeds the ruler via the colony's built-in
		// household-succession policy (see Ruler.successor, which also keeps the
		// colony's ruler reference current); no rule is wired here

		// dynamic firm provisioning is the default for every ruler-bearing colony: the
		// ruler's monthly sector review grows/shrinks the firm count to fit demand
		// (the firms were created with a representative bank captured in createFirms)
		if (charteredFirmBank != null)
			enableDynamicFirmProvisioning(charteredFirmBank);

		// a colony with an export sector staffs it by ennoblement: while it has fewer
		// than cfg.targetNobles() living nobles, the ruler raises the ablest laborer
		// into a silver-banking noble (the ruler works the strategic firm meanwhile —
		// see Ruler.act — so it is never unstaffed)
		if (colony.getMarket(StrategicFirm.LABOR_MARKET) != null)
			colony.addStepAction(this::topUpAristocracy);

		// the converse of ennoblement: a noble ruined (insolvent past a grace period)
		// is demoted back to a laborer. Registered for every ruler-bearing colony,
		// since nobles can arise even without an export sector (the no-owner charter
		// fallback); a no-op while every noble is solvent.
		colony.addStepAction(this::demoteRuinedNobles);
		return ruler.getBank();
	}

	/**
	 * Turn on <b>dynamic firm provisioning</b>: install a {@link FirmFactory} so the
	 * ruler's monthly sector review can charter and dissolve consumer-good firms as
	 * demand warrants, rather than the colony carrying a fixed founding count. A new
	 * firm banks at <tt>firmBank</tt>, is built with the run's standard initial
	 * parameters, has its seed capital funded out of the ruler's treasury, and is
	 * granted to the least-encumbered living noble (or left unowned if the colony has
	 * no noble); a dissolved firm is detached from its owner, its slot freed and its
	 * account settled into equity. Called automatically by {@link
	 * #createDefaultRuler()} (so every ruler-bearing colony grows its firms by
	 * default), after the firms (see {@link #createFirms}) exist.
	 *
	 * @param firmBank
	 *            the bank a dynamically chartered firm holds its accounts at
	 */
	public void enableDynamicFirmProvisioning(Bank firmBank) {
		colony.setFirmFactory(new FirmFactory() {
			@Override
			public ConsumerGoodFirm charter(boolean necessity) {
				Ruler ruler = colony.getRuler();
				if (ruler == null || !ruler.isAlive())
					return null;

				double seed;
				ConsumerGoodFirm firm;
				if (necessity) {
					seed = cfg.nFirm().checking() + cfg.nFirm().savings();
					firm = new NFirm(cfg.nFirm().checking(), cfg.nFirm().savings(),
							cfg.nFirm().output(), cfg.nFirm().wageBudget(),
							cfg.nFirm().capital(), capitalFirms, nFirmConfig,
							firmBank, colony);
				} else {
					seed = cfg.eFirm().checking() + cfg.eFirm().savings();
					firm = new EFirm(cfg.eFirm().checking(), cfg.eFirm().savings(),
							cfg.eFirm().output(), cfg.eFirm().wageBudget(),
							cfg.eFirm().capital(), capitalFirms, firmConfig,
							firmBank, colony);
				}

				// the crown funds the firm's seed money out of its treasury, so the
				// money the firm opened with has a counterparty (the firm's account
				// was credited from nothing; this destroys an equal sum). Gold→copper
				// fires the gold bank's FX fee; a short treasury borrows.
				ruler.getBank().withdraw(ruler.getID(), seed);

				// grant it to the noble with the fewest holdings (spreading ownership);
				// if the colony has no noble, ennoble the ablest laborer to own it —
				// deferred to end of step, once that laborer's offers have cleared so
				// its account can be moved (see ennobleBestLaborer). Re-check for a
				// noble inside the deferred action, so a second charter the same step
				// (e.g. both sectors) reuses the one just raised rather than raising
				// another from the same laborer.
				Noble owner = leastLoadedNoble();
				if (owner != null)
					owner.addFirm(firm);
				else
					colony.scheduleEndOfStepAction(() -> {
						Noble raised = leastLoadedNoble();
						if (raised == null)
							raised = ennobleBestLaborer();
						if (raised != null)
							raised.addFirm(firm);
					});

				// claim a slot — a live colony queues a builder growth ring and holds
				// the firm pending, but it is economically active from its constructor
				// (which posted a labor demand) regardless — then admit it to the step
				// loop at end of step (so the agent set is not mutated mid-iteration)
				colony.claimSlot(firm);
				colony.scheduleAddAgent(firm);
				return firm;
			}

			@Override
			public void dissolve(ConsumerGoodFirm firm) {
				// detach from its owner so no dividend is drawn next step
				for (Agent a : colony.getAgents())
					if (a instanceof Noble noble && noble.removeFirm(firm))
						break;
				// mark it dissolved and remove it at end of step; its slot is freed and
				// its account settled into equity there (its final offers clear first)
				firm.markDissolved();
				colony.scheduleRemoveAgent(firm);
			}

			// the living noble currently owning the fewest firms, or null if none
			private Noble leastLoadedNoble() {
				Noble best = null;
				for (Agent a : colony.getAgents())
					if (a instanceof Noble noble && noble.isAlive() && (best == null
							|| noble.getFirmCount() < best.getFirmCount()))
						best = noble;
				return best;
			}
		});
	}

	/**
	 * Ennoble the colony's ablest laborer household so it can own a firm chartered
	 * when no noble yet exists: the laborer with the highest head {@link
	 * Skill#SOCIAL} (the youngest breaking a tie) is <b>elevated to a {@link
	 * Noble}</b> banking in <b>silver</b>, adopting its head and members and carrying
	 * its (copper) balances over into a fresh silver account; the old copper account
	 * is then closed, so the colony's money is conserved. The laborer leaves the
	 * workforce and the new noble joins the step loop at end of step.
	 * <p>
	 * Called only as a deferred end-of-step action (see the charter path), so the
	 * laborer's buy offers have already cleared and its account is safe to move.
	 *
	 * @return the new noble, or {@code null} if the colony has no laborer to raise
	 */
	private Noble ennobleBestLaborer() {
		Laborer best = null;
		for (Agent a : colony.getAgents())
			if (a instanceof Laborer lab && lab.isAlive()
					&& (best == null || moreEnnoblable(lab, best)))
				best = lab;
		if (best == null)
			return null;
		// reform the chosen laborer up the rank ladder: HOUSEHOLD -> HOLDING (the
		// reserved CARAVAN rung in between has no factory, so it is skipped). The
		// HOLDING factory re-banks it in silver, carries its balances and members
		// over, and the ladder closes the old account and swaps the agent — money
		// conserved, the laborer's surname staying in use with the noble that adopted
		// its head. Safe here: this runs only as a deferred end-of-step action (the
		// laborer's offers have cleared), exactly as before.
		return (Noble) rankLadder().promote(best);
	}

	/**
	 * Demote <tt>household</tt> one realized rank down the {@link RankLadder} — e.g.
	 * a ruined {@link Noble} ({@link Rank#HOLDING}) reformed back into a
	 * copper-banking {@link Laborer} ({@link Rank#HOUSEHOLD}), adopting its head and
	 * members and carrying its (copper) balances over so the colony's money is
	 * conserved. This is the capability the rank ladder unlocks; <b>no automatic
	 * trigger fires it yet</b> (a bankruptcy/attainder rule is future work — see
	 * {@code docs/rank-ladder.md}). Like ennoblement it must run from an
	 * <em>end-of-step</em> context (the household's offers must have cleared).
	 *
	 * @param household
	 *            the household to demote
	 * @return the reformed (lower-ranked) household, or {@code null} if there is no
	 *         realized rank below it
	 */
	public Household demote(Household household) {
		return rankLadder().demote(household);
	}

	/**
	 * The colony's {@link RankLadder}, built lazily with the realized ranks'
	 * factories. Two ranks are realized:
	 * <ul>
	 * <li>{@link Rank#HOLDING} — a silver-banking {@link Noble} (ennoblement, a
	 * laborer reformed upward, adopting its head, members and balances);</li>
	 * <li>{@link Rank#HOUSEHOLD} — a copper-banking {@link Laborer} (demotion, a
	 * noble reformed downward, mirroring the pool-promotion construction).</li>
	 * </ul>
	 * The unrealized {@code CARAVAN} rung between them has no factory, so promoting a
	 * {@code HOUSEHOLD} laborer skips it and lands on {@code HOLDING}, and demoting a
	 * {@code HOLDING} noble skips it and lands on {@code HOUSEHOLD}.
	 *
	 * @return the colony's rank ladder
	 */
	private RankLadder rankLadder() {
		if (rankLadder == null) {
			rankLadder = new RankLadder(colony);
			// HOLDING: a laborer ennobled into a silver-banking noble
			rankLadder.register(Rank.HOLDING, (estate, c) -> {
				Member head = estate.head();
				Noble noble = new Noble(head, estate.checking(), estate.savings(),
						nobleConfig, getSilverBank(), c);
				// carry any further members (e.g. a spouse) across
				for (Member m : estate.members())
					if (m != head)
						noble.addMember(m);
				return noble;
			});
			// HOUSEHOLD: a noble demoted into a copper-banking laborer, built like a
			// pool-promoted laborer (same init template) but adopting the carried
			// balances rather than a fresh ruler-funded endowment
			rankLadder.register(Rank.HOUSEHOLD, (estate, c) -> {
				Member head = estate.head();
				Laborer laborer = new Laborer(head, cfg.laborer().e(),
						REPLACEMENT_NECESSITY_STOCK, estate.checking(),
						estate.savings(), cfg.laborer().savingsRate(),
						LaborerConfig.DEFAULT, getCopperBank(), c);
				for (Member m : estate.members())
					if (m != head)
						laborer.addMember(m);
				return laborer;
			});
		}
		return rankLadder;
	}

	/**
	 * Maintain the aristocracy at {@code cfg.targetNobles()} by ennoblement: a step
	 * action (registered by {@link #createDefaultRuler()} for colonies with an export
	 * sector) that, once a week, raises the ablest laborer into a noble while the
	 * colony has too few. Weekly so the class forms gradually over the first weeks
	 * (the ruler staffs the export firm meanwhile). The actual ennoblement is deferred
	 * to end of step (the laborer's offers must clear before its account moves).
	 */
	private void topUpAristocracy() {
		if (colony.getDate().getDayOfWeek() != DayOfWeek.MONDAY)
			return;
		long nobles = colony.getAgents().stream()
				.filter(a -> a instanceof Noble n && n.isAlive()).count();
		if (nobles >= cfg.targetNobles())
			return;
		boolean hasLaborer = colony.getAgents().stream()
				.anyMatch(a -> a instanceof Laborer l && l.isAlive());
		if (hasLaborer)
			colony.scheduleEndOfStepAction(this::ennobleBestLaborer);
	}

	/**
	 * Demote every <b>ruined</b> noble — one insolvent (a net debtor) for at least
	 * {@value #NOBLE_INSOLVENCY_GRACE_DAYS} consecutive days — back to a laborer, the
	 * converse of ennoblement. A step action registered by {@link #createDefaultRuler()};
	 * the actual demotion is deferred to end of step (the noble's offers must clear
	 * before its account moves), exactly as ennoblement is.
	 */
	private void demoteRuinedNobles() {
		for (Agent a : colony.getAgents())
			if (a instanceof Noble n && n.isAlive() && n
					.getConsecutiveInsolventDays() >= NOBLE_INSOLVENCY_GRACE_DAYS)
				colony.scheduleEndOfStepAction(() -> demoteRuinedNoble(n));
	}

	// demote one ruined noble (deferred to end of step): hand its holdings to another
	// living noble first (a laborer owns none), then reform it down the rank ladder
	// HOLDING -> HOUSEHOLD (skipping the unrealized CARAVAN rung). If it is the
	// colony's only noble its firms go unowned until the next charter's no-owner
	// fallback re-ennobles an owner. Re-checks the trigger in case state changed
	// between the schedule and end of step.
	private void demoteRuinedNoble(Noble ruined) {
		if (!ruined.isAlive() || ruined
				.getConsecutiveInsolventDays() < NOBLE_INSOLVENCY_GRACE_DAYS)
			return;
		Noble heir = leastLoadedNobleExcept(ruined);
		if (heir != null)
			ruined.transferPropertyTo(heir);
		demote(ruined);
	}

	// the living noble other than `excluded` owning the fewest firms, or null if none
	private Noble leastLoadedNobleExcept(Noble excluded) {
		Noble best = null;
		for (Agent a : colony.getAgents())
			if (a instanceof Noble n && n != excluded && n.isAlive() && (best == null
					|| n.getFirmCount() < best.getFirmCount()))
				best = n;
		return best;
	}

	// the more ennoblable of two laborers: higher head SOCIAL, the younger
	// (smaller age) breaking a tie
	private static boolean moreEnnoblable(Laborer candidate, Laborer incumbent) {
		int ci = candidate.getHead().skills().level(Skill.SOCIAL);
		int ii = incumbent.getHead().skills().level(Skill.SOCIAL);
		if (ci != ii)
			return ci > ii;
		return candidate.getAgeYears() < incumbent.getAgeYears();
	}

	/**
	 * Give the colony its <b>peasant pool</b> (banking in copper), seeded with
	 * {@code cfg.retinueSize()} peasants the {@link Ruler}
	 * feeds, and — so every pool-bearing colony can grow — a default {@link
	 * BuilderFirm} staffed from that pool. Requires the necessity market (see {@link
	 * #createMarkets()}) and a ruler (see {@link #createDefaultRuler()}) to exist
	 * first; create it <em>last</em> (after the laborers and the ruler) so its
	 * demographic/naming draws don't perturb theirs.
	 *
	 * @return the created peasant pool
	 */
	public Retinue createDefaultRetinue() {
		return createDefaultRetinue(getCopperBank());
	}

	/**
	 * As {@link #createDefaultRetinue()}, but the pool (and the builder it
	 * staffs) bank at <tt>bank</tt> (for colonies whose commoners do not use {@link
	 * #getCopperBank()} — e.g. a multi-bank colony, so the pool does not add a
	 * third copper bank).
	 *
	 * @param bank
	 *            the (copper) bank the pool transacts through
	 * @return the created peasant pool
	 */
	public Retinue createDefaultRetinue(Bank bank) {
		// every pool-bearing colony gets a builder (the only thing that can grow a
		// live colony), staffed from this pool. Create it first so the dedicated
		// PeasantLabor market exists when the pool's constructor looks it up.
		if (colony.getBuilder() == null)
			createBuilder(bank, BuilderConfig.DEFAULT);
		// seed the whole pool with cfg.retinueSize() peasants, each with a
		// BUFFER_DAYS larder. foundLaborersFromRetinue then promotes promotionRatio of
		// them on day 0, the rest stay as the standing reserve.
		retinue = new Retinue(cfg.retinueSize(), bank, colony);
		colony.addAgent(retinue);
		return retinue;
	}

	/**
	 * Seed this colony's pool from a re-founding <b>band</b> (the {@code CARAVAN →
	 * HOLDING} settle, see {@code docs/caravan.md}): a fresh {@link Retinue} adopts the
	 * {@link Caravan}'s following — its surviving people (with their skills and ages)
	 * and its carried larder — rather than drawing peasants anew. As with {@link
	 * #createDefaultRetinue(Bank)} the colony also gets a builder (staffed from the
	 * pool). A <em>fresh</em> Retinue is built because a band's own Retinue is bound to
	 * its old (vanished) colony; the band's people thread across at the data level.
	 *
	 * @param band
	 *            the wandering band re-founding here; its following seeds the pool
	 * @param bank
	 *            the (copper) bank the pool transacts through
	 * @return the created peasant pool, seeded from the band
	 */
	public Retinue createRetinueFromBand(MigrantCaravan band, Bank bank) {
		if (colony.getBuilder() == null)
			createBuilder(bank, BuilderConfig.DEFAULT);
		Retinue following = band.getFollowing();
		retinue = new Retinue(following.getMembers(), following.getLarder(), bank,
				colony);
		colony.addAgent(retinue);
		return retinue;
	}

	/**
	 * Register a {@link RetinuePrinter} for the colony's peasant pool. The pool must
	 * already exist (see {@link #createDefaultRetinue()}).
	 *
	 * @param fileName
	 *            the CSV output file name
	 */
	public void addRetinuePrinter(String fileName) {
		colony.addPrinter(new RetinuePrinter(fileName, retinue));
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
		// needs a peasant pool (see createDefaultRetinue).
		if (colony.getMarket(BuilderFirm.LABOR_MARKET) == null)
			colony.addMarket(new LaborMarket(BuilderFirm.LABOR_MARKET, colony));
		builderFirm = new BuilderFirm(config, bank, colony);
		colony.addAgent(builderFirm);
		colony.claimSlot(builderFirm);
		return builderFirm;
	}

	/**
	 * Create <tt>numLaborers</tt> laborers <b>directly</b> (same-dynasty founding,
	 * not through a pool) and add them to the colony, then clear the labor market
	 * once so firms have workers before step 0. The bank, initial necessity stock
	 * and initial savings of each laborer are supplied by the caller (by index).
	 * Used by the bare, pool-less colonies (the analytical sweeps, the small open
	 * colony); a pool-bearing colony instead founds its labor force from the pool
	 * (see {@link #foundLaborersFromRetinue}), which sets the count as {@code
	 * round(promotionRatio * retinueSize)}.
	 *
	 * @param numLaborers
	 *            the number of laborer households to found
	 * @param laborerBank
	 *            the bank each laborer holds its accounts at (by index)
	 * @param initN
	 *            the initial necessity stock of each laborer (by index)
	 * @param savings
	 *            the initial savings of each laborer (by index)
	 */
	public void createLaborers(int numLaborers, IntFunction<Bank> laborerBank,
			IntToDoubleFunction initN, IntToDoubleFunction savings) {
		laborers = new Laborer[numLaborers];
		for (int i = 0; i < numLaborers; i++) {
			laborers[i] = new Laborer(cfg.laborer().e(), initN.applyAsDouble(i),
					cfg.laborer().checking(), savings.applyAsDouble(i),
					cfg.laborer().savingsRate(), LaborerConfig.DEFAULT,
					laborerBank.apply(i), colony);
			colony.addAgent(laborers[i]);
		}
		registerLaborerReplacementPolicy();
		laborMkt.clear();
	}

	/**
	 * Found the initial labor force <b>through the pool</b>: the ruler promotes the
	 * ablest {@code round(promotionRatio * poolSize)} peasants (highest skill first)
	 * into laborer households, each capitalized by the ruler with its skill-sum
	 * endowment and carrying its larder ration out of the pool (see {@link
	 * #promoteToLaborer}). The peasant pool must already be seeded (see {@link
	 * #createDefaultRetinue()}) and a ruler must exist (the sovereign holds the
	 * founding cash). Used in place of {@link #createLaborers} by the pool-founding
	 * sims; the unpromoted remainder stays as the standing reserve.
	 *
	 * @param laborerBank
	 *            the bank each new laborer holds its accounts at (by index)
	 * @param initN
	 *            the initial necessity stock of each new laborer (by index) — drawn
	 *            from the pool's larder, so it is conserved, not created
	 */
	public void foundLaborersFromRetinue(IntFunction<Bank> laborerBank,
			IntToDoubleFunction initN) {
		// promote promotionRatio of the seeded pool into households (highest skill
		// first); the rest — the least skilled — remain as the standing reserve
		int promoted =
				(int) Math.round(cfg.promotionRatio() * retinue.size());
		laborers = new Laborer[promoted];
		// promote the whole highest-skill cohort in one batch (a single sort instead
		// of `promoted` linear scans of the pool); the order is identical to taking
		// the highest skilled one at a time, so the per-laborer endowment draws below
		// are unchanged
		List<Member> cohort = retinue.promoteHighestSkilled(promoted);
		for (int i = 0; i < promoted; i++) {
			Member peasant = cohort.get(i);
			double stock = retinue.drawPromotionStock(initN.applyAsDouble(i));
			laborers[i] = promoteToLaborer(peasant, laborerBank.apply(i), stock);
			colony.addAgent(laborers[i]);
		}
		registerLaborerReplacementPolicy();
		laborMkt.clear();
	}

	/**
	 * Register how a dead laborer is replaced. By default a successor household
	 * continues the same dynasty at the same bank, inheriting the estate (so money
	 * and the labor force stay roughly constant). When the run opts into pool
	 * promotion, the ruler instead elevates the ablest peasant into a fresh laborer
	 * household — merit-based mobility — and an empty pool yields no replacement, so
	 * the labor force declines as the reserve drains.
	 */
	private void registerLaborerReplacementPolicy() {
		// a colony with a peasant pool replaces dead laborers by promotion from it
		// (and collapses once the reserve drains); a pool-less colony (the bare
		// analytical sims) keeps same-dynasty succession
		if (retinue != null)
			colony.addReplacementPolicy(dead -> {
				if (!(dead instanceof Laborer) || retinue == null)
					return null;
				Member peasant = retinue.promoteHighestSkilled();
				if (peasant == null)
					return null;
				// the promoted peasant carries its larder ration out of the pool
				double stock = retinue
						.drawPromotionStock(REPLACEMENT_NECESSITY_STOCK);
				return promoteToLaborer(peasant, ((Laborer) dead).getBank(), stock);
			});
		else
			colony.addReplacementPolicy(dead -> {
				if (!(dead instanceof Laborer))
					return null;
				return new Laborer((Laborer) dead, cfg.laborer().e(),
						REPLACEMENT_NECESSITY_STOCK, cfg.laborer().savingsRate(),
						LaborerConfig.DEFAULT, colony);
			});
	}

	/**
	 * Build a laborer household for a peasant promoted out of the pool: it adopts
	 * the peasant as its head (keeping its given name, skills and age) under a
	 * freshly-drawn dynasty surname, and opens with the <b>sum of the head's twelve
	 * skill levels</b> as its savings (in copper — an abler peasant starts richer).
	 * That endowment is <b>funded by the ruler</b> (debited from the treasury,
	 * borrowing if short — so the money has a counterparty; the dead laborer's estate
	 * stays folded into equity). The ruler banks gold, so the copper-quoted endowment
	 * crosses gold→copper and fires the gold bank's FX fee.
	 *
	 * @param peasant
	 *            the promoted peasant to adopt as the new household's head
	 * @param bank
	 *            the bank the new laborer holds its accounts at
	 * @param initNQty
	 *            the new laborer's initial necessity stock
	 * @return the promoted laborer household
	 */
	private Laborer promoteToLaborer(Member peasant, Bank bank, double initNQty) {
		// keep the peasant's given name, gender, skills, race and age; give it a fresh
		// dynasty surname from its own race's pool (it carried none while pooled)
		Race race = peasant.race();
		String surname = colony.getNames().nextDynastyName(race);
		Member head = new Member(
				new Person(peasant.person().givenName(), surname,
						peasant.gender(), peasant.skills(), race),
				peasant.getBirthDate());
		// skill-based endowment: the sum of the head's twelve skill levels, in copper
		double savings = peasant.skills().totalLevel();
		Laborer laborer = new Laborer(head, cfg.laborer().e(), initNQty, 0, savings,
				cfg.laborer().savingsRate(), LaborerConfig.DEFAULT, bank, colony);
		// the ruler capitalizes the new household out of its treasury (borrowing if
		// short); skip only during an interregnum (a dead ruler has no account)
		Ruler ruler = colony.getRuler();
		if (ruler != null && ruler.isAlive())
			ruler.getBank().withdraw(ruler.getID(), savings);
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

	/**
	 * Found a standard colony on a <b>single copper bank</b> — the canonical
	 * founding sequence the standard single-bank simulations share, packaged as one
	 * reusable operation: create the markets, open the copper bank, found the seed
	 * firms, raise the default export sector, install the ruler (and its gold bank),
	 * seed the peasant pool, promote the initial labor force out of it, and open the
	 * colony to any external inflow — in exactly that order. Commoners (firms,
	 * laborers, pool) all bank at the copper bank; the export aristocracy re-banks in
	 * silver by ennoblement and the ruler in gold, as in every standard run.
	 * <p>
	 * This is the first cut of the <b>foundry</b> (see {@code docs/village-founding.md}):
	 * a colony's whole founding behind a single call, the seam a future runtime
	 * founder (a wandering retinue chartering a village mid-run) will build on. It
	 * reproduces the hand-written sequence the migrated sims used verbatim — same
	 * calls, same order — so those runs stay byte-identical. Simulations whose
	 * founding diverges (multiple or non-copper commoner banks, a different
	 * market/bank ordering) keep composing the granular methods this orchestrates.
	 * <p>
	 * Any config overrides (e.g. {@code setNobleConfig}/{@code setWeddingConfig}) must
	 * be applied before this call, since it creates the markets and ruler.
	 *
	 * @param eFirmSavings
	 *            initial savings of each enjoyment firm, by index
	 * @param nFirmSavings
	 *            initial savings of each necessity firm, by index
	 * @param laborerNStock
	 *            initial necessity stock of each promoted laborer household, by index
	 * @return the colony's gold bank (the ruler's treasury), for printer wiring; the
	 *         copper bank is available from {@link #getCopperBank()}
	 */
	public Bank foundStandardColony(IntToDoubleFunction eFirmSavings,
			IntToDoubleFunction nFirmSavings, IntToDoubleFunction laborerNStock) {
		createMarkets();
		Bank copper = getCopperBank();
		createFirms(copper, i -> copper, eFirmSavings, nFirmSavings);
		createDefaultStrategicSector(copper);
		Bank gold = createDefaultRuler();
		createDefaultRetinue();
		foundLaborersFromRetinue(i -> copper, laborerNStock);
		enableExternalInflow(copper);
		return gold;
	}

	/**
	 * <b>Re-found</b> a standard colony from a wandering <b>band</b> — the {@code
	 * CARAVAN → HOLDING} settle that closes the rise-fall-rise cycle (see {@code
	 * docs/caravan.md}): the same {@link #foundStandardColony founding sequence}, but
	 * the colony is seeded <em>from the band</em> rather than from scratch — its
	 * leader becomes the gold-banking ruler (its hoard the founding treasury, see {@link
	 * #createRulerFromLeader}), and its following seeds the peasant pool (see {@link
	 * #createRetinueFromBand}) the initial labor force is then promoted out of. A band
	 * that fell can rise again.
	 * <p>
	 * The colony itself must already have been raised at the band's position via {@link
	 * GameSession#newSettlement(Caravan, String, java.time.LocalDate,
	 * double, double, double, double)} and wrapped in this harness. Any config overrides
	 * must be applied before this call (it creates the markets and ruler).
	 *
	 * @param band
	 *            the wandering band re-founding here
	 * @param eFirmSavings
	 *            initial savings of each enjoyment firm, by index
	 * @param nFirmSavings
	 *            initial savings of each necessity firm, by index
	 * @param laborerNStock
	 *            initial necessity stock of each promoted laborer household, by index
	 * @return the colony's gold bank (the ruler's treasury)
	 */
	public Bank reFoundStandardColony(MigrantCaravan band, IntToDoubleFunction eFirmSavings,
			IntToDoubleFunction nFirmSavings, IntToDoubleFunction laborerNStock) {
		createMarkets();
		Bank copper = getCopperBank();
		createFirms(copper, i -> copper, eFirmSavings, nFirmSavings);
		createDefaultStrategicSector(copper);
		// resume the band's carried tech tree (overriding the fresh warm-start the
		// strategic sector just installed), re-applying its researched techs' effects
		if (band.getResearch() != null)
			colony.setResearch(ResearchState.restore(
					colony.getSession().getTechTree(colony.getFoundingRace()), colony,
					band.getResearch(), cfg.researchCostScale()));
		Bank gold = createRulerFromLeader(band.getLeader(), band.getHoard());
		createRetinueFromBand(band, copper);
		foundLaborersFromRetinue(i -> copper, laborerNStock);
		enableExternalInflow(copper);
		return gold;
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
		// the consumer sectors (enjoyment, necessity) each report into one consolidated
		// CSV — a row per sector per cycle, told apart by a Good column — the way the
		// banks report into one Banks.csv
		colony.addPrinter(new PricesPrinter(prefix + "Prices"));
		colony.addPrinter(new VolumesPrinter(prefix + "Volumes"));
		colony.addPrinter(new FirmsPrinter(prefix + "Firms"));
		colony.addPrinter(new WeddingPrinter(prefix + "Weddings", weddingMkt));
	}

	/**
	 * Register a {@link BanksPrinter} writing <b>all</b> of the colony's banks to a
	 * single <tt>fileName</tt> (one row per bank per cycle, told apart by the Bank
	 * and Currency columns). Replaces the former per-bank printer, so a colony with
	 * several banks needs only this one call.
	 *
	 * @param fileName
	 *            name of the consolidated CSV (e.g. {@code "Banks"})
	 */
	public void addBanksPrinter(String fileName) {
		colony.addPrinter(new BanksPrinter(fileName));
	}

	/**
	 * Register the export sector's printers — the {@link ServicesPrinter} (the
	 * colony's crown services: export, construction and research in one CSV) and the
	 * {@link NoblesPrinter} — for a colony whose nobles are the default export
	 * workforce (and so has no other noble printers). The filenames are prefixed with
	 * <tt>prefix</tt> (see {@link #addCommonPrinters(String)}). Persons-of-interest
	 * creation and death are recorded in the event log (see {@link
	 * com.civstudio.io.SimLog}), so there is no separate CSV roster.
	 *
	 * @param prefix
	 *            prepended to each printer's filename ({@code ""} for the default)
	 * @param bank
	 *            the bank whose equity the export earnings flow into
	 */
	public void addStrategicSectorPrinters(String prefix, Bank bank) {
		colony.addPrinter(
				new ServicesPrinter(prefix + "Services", strategicFirm, bank));
		colony.addPrinter(new NoblesPrinter(prefix + "Nobles"));
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
