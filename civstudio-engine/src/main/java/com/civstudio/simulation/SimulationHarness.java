package com.civstudio.simulation;

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
import com.civstudio.agent.Captain;
import com.civstudio.agent.Caravan;
import com.civstudio.agent.Granary;
import com.civstudio.agent.GranaryConfig;
import com.civstudio.agent.SettlerCaravan;
import com.civstudio.agent.Household;
import com.civstudio.agent.firm.BuilderConfig;
import com.civstudio.agent.firm.BuilderFirm;
import com.civstudio.agent.firm.CFirm;
import com.civstudio.agent.firm.Firm;
import com.civstudio.agent.firm.ChildrenFirm;
import com.civstudio.agent.firm.ChildrenFirmConfig;
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
import com.civstudio.agent.ExpeditionStats;
import com.civstudio.agent.ExplorerProvisioner;
import com.civstudio.agent.Retinue;
import com.civstudio.agent.RetinueConfig;
import com.civstudio.agent.laborer.Laborer;
import com.civstudio.agent.laborer.LaborerConfig;
import com.civstudio.agent.noble.Noble;
import com.civstudio.agent.noble.NobleConfig;
import com.civstudio.agent.Rank;
import com.civstudio.agent.ruler.Mayor;
import com.civstudio.agent.ruler.Ruler;
import com.civstudio.tech.ResearchState;
import com.civstudio.bank.Bank;
import com.civstudio.bank.BankConfig;
import com.civstudio.bank.CurrencyType;
import com.civstudio.name.Person;
import com.civstudio.settlement.Settlement;
import com.civstudio.settlement.SettlementTier;
import com.civstudio.settlement.GameSession;
import com.civstudio.io.SimLog;
import com.civstudio.io.printer.*;
import com.civstudio.market.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.java.Log;

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
@Log
public class SimulationHarness {

	// fixed necessity stock granted to a replacement household (package-visible: also
	// the rank-ladder demotion template and the fission dowry, in SocialMobility)
	static final int REPLACEMENT_NECESSITY_STOCK = 15;

	// the peasant reserve an open colony's inflow maintains, as a fraction of the
	// promoted workforce: a modest buffer so promotion always finds a peasant to replace
	// a dead laborer, while keeping the extra relief mouths small enough not to overload
	// the colony's food supply (refilling to the full founding reserve re-creates the
	// overpopulation that starves the pool — see enablePoolImmigration)
	private static final double IMMIGRATION_RESERVE_FRACTION = 0.15;

	// a floor on that maintained reserve, so even a tiny workforce keeps a usable buffer
	private static final int MIN_IMMIGRATION_RESERVE = 20;

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
	// the necessity stock a freshly promoted laborer household starts with. Every scenario passed
	// `i -> 15` by hand; it is a founding default, not a per-caller decision.
	public static final double DEFAULT_LABORER_NSTOCK = 15;

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
	@Setter
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
	@Setter
	private WeddingConfig weddingConfig = WeddingConfig.DEFAULT;

	// parameters for nobles raised by ennoblement (the export aristocracy is now
	// built from laborers, not created up front); defaults to the canonical values.
	// Replace via setNobleConfig before createDefaultRuler (e.g. to give a colony's
	// nobles a necessity reserve, as a per-run override can).
	@Setter
	private NobleConfig nobleConfig = NobleConfig.DEFAULT;

	// parameters for the peasant pool (larder depth, relief budget, relief ration);
	// defaults to the canonical values. Replace via setRetinueConfig before
	// createDefaultRetinue / foundStandardColony to tune the reserve's food economics.
	@Setter
	private RetinueConfig retinueConfig = RetinueConfig.DEFAULT;

	// parameters for the civic school (its capacity and per-step learning); defaults
	// to the canonical values. Replace via setChildrenFirmConfig before the school is
	// created (createDefaultChildrenFirm / foundStandardColony). See docs/births.md.
	@Setter
	private ChildrenFirmConfig childrenFirmConfig = ChildrenFirmConfig.DEFAULT;

	// parameters for the ever-normal granary (its price band and reserve target);
	// defaults to the canonical values. Replace via setGranaryConfig before the granary
	// is created (createDefaultGranary / foundStandardColony). See docs/granary.md.
	@Setter
	private GranaryConfig granaryConfig = GranaryConfig.DEFAULT;

	// the colony's social-mobility runtime (ennoblement, ruin demotion, household
	// fission and the rank ladder), built lazily on first use (see mobility()); the
	// dynamic firm provisioning also uses it to find/raise a firm's owner
	private SocialMobility mobility;

	// the colony's seasonal explorer-levy provisioner (City settlements only; null for a Village or
	// a bare sim), retained so the Expeditions printer can read its muster tally (see expeditionStats)
	private ExplorerProvisioner explorerProvisioner;


	// necessity (food) firms run a higher technology coefficient than the other
	// consumer firms: because production stops on the weekly day of rest and on
	// feast days (~79 days/year) while the population eats every day, food output
	// on the ~286 working days must cover all 365 days' consumption. This factor
	// lifts the necessity firms' Cobb-Douglas A so the colony can feed itself
	// across the rest-day calendar (see Firm.operatesOn / the day-type wiring).
	public static final double NECESSITY_TECH_FACTOR = 2.0;

	/**
	 * Phase-2 of the food redesign (see {@code docs/granary.md} §5.1): a deliberate
	 * <b>surplus</b> multiplier on the necessity firms' TFP, <em>on top of</em> the
	 * structural rest-day coverage ({@link #NECESSITY_TECH_FACTOR}). Where the rest-day
	 * factor only lets a colony break even across the calendar, this would lift a stable
	 * workforce into net food surplus above ration-capped consumption.
	 * <p>
	 * <b>Defaulted to {@code 1.0} (no-op) by a measured finding.</b> Raising it in
	 * isolation <em>deflates</em> the necessity price: a <em>permanent</em> production
	 * surplus saturates the granary's <em>finite</em> reserve (it fills to target, then
	 * stops buying), after which the steady-state surplus floods a ration-capped market
	 * forever and the price floor falls (measured: factor 1.0→1.3 dropped the price floor
	 * 0.31→0.13). A bigger granary cap or target only changes how fast it saturates, not
	 * the outcome — the §7.3 "TFP deflation if the granary saturates" risk, confirmed. And
	 * the collapse horizon does not improve (it is renewal-bound, not production-bound —
	 * {@code docs/food-balance.md} mode B). So the lever is <b>coupled</b>: a permanent
	 * surplus needs a permanent <em>sink</em> (export earnings or spoilage, §7.3) and/or
	 * the renewal <em>spend</em> (child relief + fission, §5.2–5.3) to consume it. The
	 * factor ships here as tunable infrastructure (override via {@link
	 * #setNecessitySurplusFactor}); raise it only once a sink/spend exists.
	 */
	public static final double DEFAULT_NECESSITY_SURPLUS_FACTOR = 1.0;

	// the surplus multiplier actually used this run (default DEFAULT_NECESSITY_SURPLUS_FACTOR);
	// override via setNecessitySurplusFactor before createFirms (e.g. a calibration sweep)
	@Setter
	private double necessitySurplusFactor = DEFAULT_NECESSITY_SURPLUS_FACTOR;

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
	private ChildrenFirm childrenFirm;
	private Granary granary;
	// a camp-founding scenario's post-boot printer wiring, run once at the ruler-economy boot
	// (the SMALLHOLDING crossing), when the economy-coupled agents finally exist. See foundCamp.
	private Runnable onEconomyBooted;

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

	/**
	 * The numbers this colony runs on — its own {@code (era, race)} cell, not the run's
	 * (see {@link Settlement#getEconomy()}). A session may seat colonies of different races, so the
	 * founding path must ask the colony rather than the run config.
	 */
	// the run config's economy fields as an Era.Economy — the phase-2 bridge above. Mirrors the
	// mapping SimulationConfig.defaultFor does in reverse.
	private static com.civstudio.era.Era.Economy economyOf(SimulationConfig cfg) {
		return com.civstudio.era.Era.Economy.builder()
				.ePrice(cfg.ePrice()).nPrice(cfg.nPrice())
				.eFirm(cfg.eFirm()).nFirm(cfg.nFirm()).cFirm(cfg.cFirm())
				.laborer(cfg.laborer())
				.targetNStock(cfg.targetNStock())
				.externalInflowPerStep(cfg.externalInflowPerStep())
				.immigrationThreshold(cfg.immigrationThreshold())
				.laborShare(cfg.laborShare())
				.bankProfitTaxRate(cfg.bankProfitTaxRate())
				.nobleIncomeTaxRate(cfg.nobleIncomeTaxRate())
				.retinueSize(cfg.retinueSize())
				.promotionRatio(cfg.promotionRatio())
				.targetNobles(cfg.targetNobles())
				.build();
	}

	private com.civstudio.era.Era.Economy econ() {
		return colony.getEconomy();
	}

	public SimulationHarness(SimulationConfig cfg, Settlement colony) {
		this.cfg = cfg;
		this.colony = colony;
		// PHASE-2 BRIDGE. The founding path now reads the colony's economy rather than the run
		// config, but SimulationConfig still CARRIES those 15 fields and callers still override them
		// there — so seed the colony from the config to keep the move of the reads behaviour-
		// preserving. Until phase 3 deletes those fields and converts the override sites to
		// tuneEconomy, econ() is exactly cfg, and a multi-race session still shares one economy.
		// Remove this line with the fields; it is the last thing making the run config win.
		colony.setEconomy(economyOf(cfg));
		// seed the firm parameters with the run's labor-share (the rest are the
		// canonical defaults); setFirmConfig can override before createFirms
		this.firmConfig =
				FirmConfig.DEFAULT.toBuilder().laborShare(econ().laborShare()).build();
		// apply the run's fertility parameters to the colony (read live by Laborer.act);
		// a test can still override via colony.setFertilityConfig before run()
		colony.setFertilityConfig(cfg.fertility());
	}

	/** Create the markets and register them (labor market first). */
	public void createMarkets() {
		enjoymentMkt = new ConsumerGoodMarket("Enjoyment", econ().ePrice().min(),
				econ().ePrice().max(), colony);
		necessityMkt = new ConsumerGoodMarket("Necessity", econ().nPrice().min(),
				econ().nPrice().max(), colony);
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
	 * Create the capital firm (banking at <tt>capitalFirmBank</tt>) and the
	 * consumer-good firms, then add them to the colony. The bank and initial
	 * savings of each consumer-good firm are supplied by the caller (by index);
	 * everything else comes from the config.
	 */
	public void createFirms(Bank capitalFirmBank, IntFunction<Bank> firmBank,
			IntToDoubleFunction eSavings, IntToDoubleFunction nSavings) {
		createFirms(capitalFirmBank, firmBank, eSavings, nSavings, cfg.numEFirms(),
				cfg.numNFirms());
	}

	/**
	 * As {@link #createFirms(Bank, IntFunction, IntToDoubleFunction,
	 * IntToDoubleFunction)}, but with explicit founding firm counts (overriding {@code
	 * cfg.numEFirms()} / {@code cfg.numNFirms()}). Used by {@link #foundStandardColony}
	 * to size the founding food sector to the labor force (see {@code
	 * docs/food-balance.md}).
	 *
	 * @param numEFirms number of enjoyment firms to found
	 * @param numNFirms number of necessity firms to found
	 */
	public void createFirms(Bank capitalFirmBank, IntFunction<Bank> firmBank,
			IntToDoubleFunction eSavings, IntToDoubleFunction nSavings,
			int numEFirms, int numNFirms) {
		// a representative firm bank for any firm the dynamic provisioning charters
		// later (createDefaultRuler installs the factory with it)
		charteredFirmBank = firmBank.apply(0);
		CFirm cFirm = new CFirm(econ().cFirm().checking(), econ().cFirm().savings(),
				econ().cFirm().wageBudget(), capitalFirmBank, colony);
		capitalFirms = new CFirm[] { cFirm };

		eFirms = new EFirm[numEFirms];
		for (int i = 0; i < numEFirms; i++)
			eFirms[i] = new EFirm(econ().eFirm().checking(),
					eSavings.applyAsDouble(i), econ().eFirm().output(),
					econ().eFirm().wageBudget(), econ().eFirm().capital(),
					capitalFirms, firmConfig, firmBank.apply(i), colony);

		// necessity firms get a higher technology coefficient (see NECESSITY_TECH_FACTOR)
		// so food output on working days covers the rest days when production stops, times
		// the Phase-2 surplus factor (see DEFAULT_NECESSITY_SURPLUS_FACTOR) that lifts a
		// stable workforce into net food surplus for the granary to bank; everything else
		// matches the other firms. Stored on the harness so the dynamic provisioning can
		// charter matching ones.
		nFirmConfig = firmConfig.toBuilder()
				.A(firmConfig.A() * NECESSITY_TECH_FACTOR * necessitySurplusFactor)
				.build();
		nFirms = new NFirm[numNFirms];
		for (int i = 0; i < numNFirms; i++)
			nFirms[i] = new NFirm(econ().nFirm().checking(),
					nSavings.applyAsDouble(i), econ().nFirm().output(),
					econ().nFirm().wageBudget(), econ().nFirm().capital(),
					capitalFirms, nFirmConfig, firmBank.apply(i), colony);

		// add each firm to the colony, placing the on-plot ones (the necessity farms)
		// on a plot — the colony appends one plot per such firm (genesis sizing).
		// Center-grouped firms (capital, enjoyment) consume no plot. Plot placement is
		// pure bookkeeping.
		colony.addAgent(cFirm);
		seatFirm(cFirm);
		for (NFirm f : nFirms) {
			colony.addAgent(f);
			seatFirm(f);
		}
		for (EFirm f : eFirms) {
			colony.addAgent(f);
			seatFirm(f);
		}
	}

	// claim a plot for a firm iff it sits on the land (a farm); a center-grouped firm
	// works in town and consumes no plot. The single place plot-occupancy is decided.
	private void seatFirm(Firm firm) {
		if (firm.occupiesPlot())
			colony.claimPlot(firm);
	}

	/**
	 * The number of <b>necessity</b> firms to found, sized to the labor force so food
	 * production matches demand from day 0 rather than ramping from a single seed firm
	 * (failure mode A in {@code docs/food-balance.md}). It is {@code
	 * round(laborForce / cfg.foundingLaborersPerNFirm())} (laborForce =
	 * {@code round(promotionRatio * retinueSize)}), floored at {@code cfg.numNFirms()}
	 * and capped to the colony's {@linkplain Settlement#getMaxPlots() maximum plots} —
	 * the whole plot budget, since under the plot model only the necessity farms
	 * occupy plots (enjoyment/capital/service firms are center-grouped) — so {@code
	 * foundPlot} never has to reject a firm. A configured ratio of {@code 0} keeps the
	 * fixed {@code cfg.numNFirms()} (legacy behavior).
	 *
	 * @return the founding necessity-firm count
	 */
	private int foundingNFirmCount() {
		int perFirm = cfg.foundingLaborersPerNFirm();
		if (perFirm <= 0)
			return cfg.numNFirms();
		int laborForce = (int) Math.round(econ().promotionRatio() * econ().retinueSize());
		int sized = Math.max(cfg.numNFirms(),
				(int) Math.ceil((double) laborForce / perFirm));
		return Math.max(1, Math.min(sized, colony.getMaxPlots()));
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
		seatFirm(strategicFirm);
		return strategicFirm;
	}

	/**
	 * Give the colony its default <b>export sector</b>, so every settlement has one:
	 * the noble-only labor market and the single {@link StrategicFirm} (banking at
	 * <tt>bank</tt>, into whose equity its export earnings flow). <b>No nobles are
	 * created up front</b> — the ruler works the strategic firm from day 0 (so it is
	 * never unstaffed) and the ablest laborers are ennobled up to {@code
	 * econ().targetNobles()} over the first weeks (see {@link #createDefaultRuler()} /
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
		// going through this method (a manually-built colony) still researches.
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
		seatFirm(scienceFirm);
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
				DEFAULT_RULER_CONSUMPTION_RATE, econ().bankProfitTaxRate(),
				econ().nobleIncomeTaxRate(), gold, colony));
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
				econ().bankProfitTaxRate(), econ().nobleIncomeTaxRate(), gold, colony));
	}

	// register a freshly-built ruler with the colony and wire the standard sovereign
	// behaviours, shared by the founding (createDefaultRuler) and re-founding
	// (createRulerFromLeader) paths.
	private Bank installRuler(Ruler ruler) {
		colony.addAgent(ruler);
		// name the crown's gold bank after the ruling house (a same-dynasty heir keeps
		// the surname, so the name carries across successions)
		ruler.getBank().setName(ruler.getHead().surname() + " Bank");
		// record the sovereign (for succession and taxation); a no-op for the economy
		colony.setRuler(ruler);
		// a same-dynasty heir succeeds the ruler via the colony's built-in
		// household-succession policy (see Ruler.successor, which also keeps the
		// colony's ruler reference current); no rule is wired here

		// dynamic firm provisioning is the default for every ruler-bearing colony: the
		// ruler's monthly sector review grows/shrinks the firm count to fit demand
		// (the firms were created with a representative bank captured in createFirms)
		if (charteredFirmBank != null)
			enableDynamicFirmProvisioning(charteredFirmBank);

		// register the colony's social-mobility step actions — the ennoblement top-up
		// (where an export sector exists to staff), the ruin demotion, and household
		// fission (see SocialMobility)
		mobility().install();
		// register the head factories the tier crossings drive (R2/R4, docs/rank-ladder-improvements.md);
		// the harness owns the treasury params. Idempotent — re-registering a rank replaces its
		// factory. CITY builds a Mayor (climb TOWN -> METROPOLIS); VILLAGE builds a Ruler (the
		// symmetric descent METROPOLIS -> TOWN reforms a Mayor back down to a Ruler).
		mobility().registerRankFactory(Rank.CITY, (estate, c) -> new Mayor(estate,
				DEFAULT_RULER_CONSUMPTION_RATE, econ().bankProfitTaxRate(), econ().nobleIncomeTaxRate(),
				getGoldBank(), c));
		mobility().registerRankFactory(Rank.VILLAGE, (estate, c) -> new Ruler(estate.head(),
				estate.checking() + estate.savings(), DEFAULT_RULER_CONSUMPTION_RATE,
				econ().bankProfitTaxRate(), econ().nobleIncomeTaxRate(), getGoldBank(), c));
		return ruler.getBank();
	}

	/**
	 * Reform the colony's {@link Ruler} into a {@link Mayor} — the {@code TOWN → METROPOLIS} head
	 * crossing (R2, {@code docs/rank-ladder-improvements.md}): its settlement has urbanized into a
	 * metropolis, so its head now commands a {@link Rank#CITY}. A same-bank (gold&rarr;gold) reform
	 * that carries the treasury 1:1, run at end of step (the ruler's offers have cleared). A no-op if
	 * the colony has no living ruler or its head is already a {@code Mayor}. Package-visible so a test
	 * can drive the crossing without accumulating a metropolis's worth of population.
	 */
	void reformRulerToMayor() {
		Ruler ruler = colony.getRuler();
		if (ruler == null || !ruler.isAlive() || ruler instanceof Mayor)
			return;
		Mayor mayor = (Mayor) mobility().reformTo(ruler, Rank.CITY);
		if (mayor != null) {
			colony.setRuler(mayor);
			log.info(colony.getName() + " urbanized into a metropolis: its ruler is now a Mayor on "
					+ colony.getDate());
		}
	}

	/**
	 * Reform the colony's {@link Mayor} back into a plain {@link Ruler} — the {@code METROPOLIS →
	 * TOWN} head descent (R4, {@code docs/rank-ladder-improvements.md}): its metropolis has shrunk
	 * back to a town, so its head reverts from a {@link Rank#CITY} to a {@link Rank#VILLAGE}. The
	 * symmetric inverse of {@link #reformRulerToMayor}, carrying the treasury 1:1 (money conserved),
	 * run at end of step. A no-op if the head is not a {@code Mayor} (a plain ruler, or none).
	 */
	void reformMayorToRuler() {
		Ruler head = colony.getRuler();
		if (!(head instanceof Mayor) || !head.isAlive())
			return;
		Ruler ruler = (Ruler) mobility().reformTo(head, Rank.VILLAGE);
		if (ruler != null) {
			colony.setRuler(ruler);
			log.info(colony.getName() + " shrank back to a town: its mayor is now a Ruler on "
					+ colony.getDate());
		}
	}

	/**
	 * Turn on <b>dynamic firm provisioning</b>: install a {@link FirmFactory} so the
	 * ruler's monthly sector review can charter and dissolve consumer-good firms as
	 * demand warrants, rather than the colony carrying a fixed founding count. A new
	 * firm banks at <tt>firmBank</tt>, is built with the run's standard initial
	 * parameters, has its seed capital funded out of the ruler's treasury, and is
	 * granted to the least-encumbered living noble (or left unowned if the colony has
	 * no noble); a dissolved firm is detached from its owner, its plot freed and its
	 * account settled into equity. Called automatically by {@link
	 * #createDefaultRuler()} (so every ruler-bearing colony grows its firms by
	 * default), after the firms (see {@link #createFirms}) exist.
	 *
	 * @param firmBank
	 *            the bank a dynamically chartered firm holds its accounts at
	 */
	public void enableDynamicFirmProvisioning(Bank firmBank) {
		colony.setFirmFactory(new DynamicFirmProvisioner(colony, cfg, capitalFirms,
				firmConfig, nFirmConfig, firmBank, mobility()));
	}

	/**
	 * The colony's {@link SocialMobility} runtime, built lazily on first use (the ruler
	 * install and any {@link #demote} call go through here). It captures the current
	 * {@link #nobleConfig}, so a {@code setNobleConfig} override must precede the
	 * ruler's creation, as the docs require.
	 */
	private SocialMobility mobility() {
		if (mobility == null)
			mobility = new SocialMobility(colony, cfg, nobleConfig,
					this::getSilverBank, this::getCopperBank, this::getRetinue);
		return mobility;
	}

	/**
	 * Demote {@code household} one realized rank down the colony's rank ladder — e.g. a
	 * ruined {@link Noble} reformed back into a {@link Laborer}, its balances carried
	 * over so the colony's money is conserved. Delegates to {@link
	 * SocialMobility#demote}; like ennoblement it must run from an end-of-step context.
	 *
	 * @param household the household to demote
	 * @return the reformed (lower-ranked) household, or {@code null} if none below it
	 */
	public Household demote(Household household) {
		return mobility().demote(household);
	}

	/** Number of household fissions over the run (see {@link SocialMobility}). */
	public long getFissionCount() {
		return mobility == null ? 0 : mobility.getFissionCount();
	}

	/**
	 * Give the colony its <b>peasant pool</b> (banking in copper), seeded with
	 * {@code econ().retinueSize()} peasants the {@link Ruler}
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
		// seed the whole pool with econ().retinueSize() peasants, each with a per-peasant
		// larder (see retinueConfig). foundLaborersFromRetinue then promotes
		// promotionRatio of them on day 0, the rest stay as the standing reserve.
		retinue = new Retinue(econ().retinueSize(), bank, colony, retinueConfig);
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
	public Retinue createRetinueFromBand(SettlerCaravan band, Bank bank) {
		if (colony.getBuilder() == null)
			createBuilder(bank, BuilderConfig.DEFAULT);
		Retinue following = band.getFollowing();
		retinue = new Retinue(following.getMembers(), following.getLarder(), bank,
				colony, retinueConfig);
		colony.addAgent(retinue);
		return retinue;
	}

	/**
	 * Reform a <b>camp's foraging pool</b> into the settled relief reserve when the camp boots its
	 * ruler economy (see {@link #bootRulerEconomy}): a fresh {@link Retinue} (in the default
	 * settled/relief mode, with the builder it staffs) adopts the camp pool's people and larder —
	 * exactly like {@link #createRetinueFromBand} adopts a band's following. A <em>fresh</em> pool
	 * is built (rather than switching the camp pool's mode) so its market/builder references
	 * resolve now that those exist; the camp pool is then retired by the caller.
	 *
	 * @param campPool
	 *            the camp's foraging pool, whose people and larder seed the settled reserve
	 * @param bank
	 *            the (copper) bank the settled pool transacts through
	 * @return the created settled pool
	 */
	public Retinue createRetinueFromPool(Retinue campPool, Bank bank) {
		if (colony.getBuilder() == null)
			createBuilder(bank, BuilderConfig.DEFAULT);
		// cap the adopted larder at a normal fresh-pool buffer (size × bufferDays): a camp that
		// foraged for months banks a huge larder, and a settled reserve that opened over-stocked
		// would buy no market food for months (suppressing necessity demand). Normalizing it makes
		// the booted reserve open like a fresh mature pool — the band's surplus is "spent settling
		// in". (This is correctness for equivalence with a mature founding, not a cure for the
		// young colony's food-balance fragility, which is the accepted upstream issue.)
		double larder = Math.min(campPool.getLarder(),
				campPool.getMembers().size() * retinueConfig.bufferDays());
		retinue = new Retinue(campPool.getMembers(), larder, bank, colony, retinueConfig);
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
	 * Give the colony its <b>ever-normal granary</b> (banking in copper, the base
	 * currency, so its food trades pay no FX fee): the ruler-run food buffer that
	 * stabilizes the necessity price by buying gluts at the floor and selling into
	 * scarcity at the ceiling (see {@link Granary} and {@code docs/granary.md}). Its net
	 * cost is borne by the ruler (it
	 * reconciles its account against the gold treasury each step), so a ruler must exist
	 * first (see {@link #createDefaultRuler()}); the necessity market must also already
	 * exist (see {@link #createMarkets()}). Part of the standard founding sequence ({@link
	 * #foundStandardColony}).
	 *
	 * @param bank
	 *            the copper bank the granary transacts through
	 * @return the created granary (also retained for {@link #addGranaryPrinter})
	 */
	public Granary createDefaultGranary(Bank bank) {
		granary = new Granary(bank, colony, granaryConfig);
		colony.addAgent(granary);
		// register it as the colony's strategic food store, so relief holders (the
		// peasant pool, later children) can draw their ration from it (see docs/granary.md)
		colony.setGranary(granary);
		return granary;
	}

	/**
	 * Register a {@link GranaryPrinter} for the colony's granary. The granary must
	 * already exist (see {@link #createDefaultGranary(Bank)}).
	 *
	 * @param fileName
	 *            the CSV output file name
	 */
	public void addGranaryPrinter(String fileName) {
		colony.addPrinter(new GranaryPrinter(fileName, granary));
	}

	/**
	 * Give the colony its civic <b>school</b> (the {@link ChildrenFirm}): an automatic
	 * institution that trains the colony's children each step (see {@code
	 * docs/births.md}). It produces no good, moves no money, and occupies no build
	 * plot; a colony with no children simply enrolls no one. Part of the default civic
	 * setup ({@link #foundStandardColony}), so every standard colony has a school. The
	 * school references {@link #getCopperBank() the copper bank} but holds no account.
	 *
	 * @return the created school (also retained for {@link #addStrategicSectorPrinters})
	 */
	public ChildrenFirm createDefaultChildrenFirm() {
		childrenFirm = new ChildrenFirm(getCopperBank(), colony, childrenFirmConfig);
		colony.addAgent(childrenFirm);
		return childrenFirm;
	}

	/**
	 * Give the colony a <b>builder</b> (banking at <tt>bank</tt>). The builder is
	 * center-grouped (it consumes no plot itself). Registering it changes how the
	 * colony grows: once it is running, the builder is the only way it gets bigger —
	 * a farm's plot demand it cannot satisfy is built (firm-funded land clearance)
	 * rather than granted instantly. A colony with a builder therefore also needs a
	 * ruler (and a peasant pool to staff it; see {@link #createDefaultRuler()}).
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
		seatFirm(builderFirm);
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
			laborers[i] = new Laborer(econ().laborer().e(), initN.applyAsDouble(i),
					econ().laborer().checking(), savings.applyAsDouble(i),
					econ().laborer().savingsRate(), LaborerConfig.DEFAULT,
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
		// first); the rest — the least skilled, plus every not-yet-grown child —
		// remain as the standing reserve
		int requested =
				(int) Math.round(econ().promotionRatio() * retinue.size());
		// promote the whole highest-skill cohort in one batch (a single sort instead
		// of `requested` linear scans of the pool); the order is identical to taking
		// the highest skilled one at a time, so the per-laborer endowment draws below
		// are unchanged. The cohort excludes pool children, so it may be smaller than
		// requested if too few peasants are of working age — size the array off it.
		List<Member> cohort = retinue.promoteHighestSkilled(requested);
		int promoted = cohort.size();
		laborers = new Laborer[promoted];
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
				return new Laborer((Laborer) dead, econ().laborer().e(),
						REPLACEMENT_NECESSITY_STOCK, econ().laborer().savingsRate(),
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
		Laborer laborer = new Laborer(head, econ().laborer().e(), initNQty, 0, savings,
				econ().laborer().savingsRate(), LaborerConfig.DEFAULT, bank, colony);
		// a home-plots colony seats the new household on a plot it farms for subsistence food
		// (landless — null — if the site is full); the founding cohort and every promoted
		// replacement flow through here. See docs/plot-working-plan.md P1.
		if (cfg.homePlots())
			laborer.setHomePlot(colony.claimHomePlot());
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
		if (econ().externalInflowPerStep() <= 0)
			return;
		// a pool-bearing colony renews its labor force through the peasant pool, so the
		// open-colony inflow recruits settlers into the pool (which promotion then draws
		// the workforce from) rather than minting laborer households directly. Without an
		// inflow a closed pool colony spirals to collapse once the founding reserve drains
		// (dead laborers go unreplaced — see docs/peasant-pool.md); refilling the reserve
		// keeps promotion supplied, so the colony survives.
		if (retinue != null) {
			enablePoolImmigration(gatewayBank);
			return;
		}
		// a bare (pool-less) colony has no reserve to refill, so external money instead
		// bankrolls net-new laborer households opened straight from the gateway equity.
		colony.addStepAction(() -> gatewayBank
				.injectExternalFunds(econ().externalInflowPerStep()));
		colony.setImmigrationPolicy(() -> {
			List<Agent> immigrants = new ArrayList<>();
			while (gatewayBank.getEquity() >= econ().immigrationThreshold()) {
				immigrants.add(new Laborer(econ().laborer().e(),
						REPLACEMENT_NECESSITY_STOCK, econ().immigrationThreshold(),
						0, econ().laborer().savingsRate(), LaborerConfig.DEFAULT,
						gatewayBank, colony, true));
			}
			return immigrants;
		});
	}

	/**
	 * Refill the peasant pool from outside the colony toward a <b>modest standing
	 * reserve</b>, bankrolled by the external inflow. Each step the reserve sits below
	 * target, the inflow is injected into the gateway bank's equity (the recruitment
	 * budget) and as many settlers as the accumulated budget affords are drawn into the
	 * pool — each costing {@code immigrationThreshold}, destroyed via {@link
	 * Bank#extractExternalEquity} since a pooled peasant opens no account of its own.
	 * <p>
	 * The target is a small buffer over the promoted workforce ({@link
	 * #IMMIGRATION_RESERVE_FRACTION}), <b>not</b> the full founding reserve: a colony's
	 * food supply can feed its workforce but not also a reserve as large again, so
	 * refilling to the founding size would starve the pool right back down (the
	 * overpopulation that drives collapse). A modest buffer is enough that promotion
	 * always finds a peasant to replace a dead laborer — which is what keeps the colony
	 * from collapsing — while staying light enough on the food market to be sustained.
	 * Topping up <em>only when short</em> keeps the inflow self-limiting (no unbounded
	 * equity, no overgrown pool).
	 *
	 * @param gatewayBank
	 *            the bank through which external money enters the colony
	 */
	private void enablePoolImmigration(Bank gatewayBank) {
		// a modest buffer over the promoted workforce — enough to always have a peasant
		// to promote, small enough not to overload the food supply (see the constants)
		int workforce = (laborers == null) ? 0 : laborers.length;
		int reserveTarget = Math.max(MIN_IMMIGRATION_RESERVE,
				(int) Math.round(workforce * IMMIGRATION_RESERVE_FRACTION));
		colony.addStepAction(() -> {
			if (retinue.size() >= reserveTarget)
				return; // reserve full — no inflow needed this step
			gatewayBank.injectExternalFunds(econ().externalInflowPerStep());
			while (retinue.size() < reserveTarget
					&& gatewayBank.getEquity() >= econ().immigrationThreshold()) {
				gatewayBank.extractExternalEquity(econ().immigrationThreshold());
				retinue.addImmigrant();
			}
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
	/**
	 * Found the standard colony on <b>its own</b> economy — the firm savings and the laborers'
	 * necessity stock taken from the colony's {@code (era, race)} cell rather than named by the
	 * caller.
	 * <p>
	 * This is what nearly every caller wants, and the explicit-lambda form below existed only because
	 * the numbers used to live on the run config: every scenario wrote the same
	 * {@code i -> cfg.eFirm().savings()} to pass a value back to the harness that the harness already
	 * had. With the economy on the colony, a run seating several races gets each colony founded on its
	 * own numbers here, which the caller-supplied lambdas could not express — they close over one
	 * config for every colony.
	 *
	 * @return the ruler's gold bank
	 */
	/**
	 * Adjust this colony's {@linkplain Settlement#getEconomy() economy} before founding — the explicit
	 * way to run on numbers other than its race's.
	 * <p>
	 * This replaced overriding economy fields on {@link SimulationConfig}: those lived on the RUN, so
	 * in a session seating several races one caller's tax rate or pool size silently applied to every
	 * colony regardless of who founded it. Tuning the colony says which colony you meant.
	 * <p>
	 * Must be called <b>before</b> founding — the founding path reads these numbers to size the pool,
	 * the firms and the aristocracy.
	 *
	 * @param tuning applied to the colony's current economy; the result replaces it
	 * @return this harness, for chaining
	 */
	public SimulationHarness tuneEconomy(
			java.util.function.UnaryOperator<com.civstudio.era.Era.Economy> tuning) {
		colony.setEconomy(tuning.apply(econ()));
		return this;
	}

	public Bank foundStandardColony() {
		return foundStandardColony(i -> econ().eFirm().savings(), i -> econ().nFirm().savings(),
				i -> DEFAULT_LABORER_NSTOCK);
	}

	public Bank foundStandardColony(IntToDoubleFunction eFirmSavings,
			IntToDoubleFunction nFirmSavings, IntToDoubleFunction laborerNStock) {
		// a geographic colony that opts in (cfg.foundAtCamp) is founded LOW — a Captain-led
		// foraging camp that climbs the tier ladder and boots this whole ruler economy when it
		// reaches SMALLHOLDING (docs/settlement-tier-ladder-plan.md Phase D). The analytical/dev
		// probes leave foundAtCamp false and found mature (below), byte-identical to before.
		if (cfg.foundAtCamp())
			return foundCamp(eFirmSavings, nFirmSavings, laborerNStock);
		createMarkets();
		Bank copper = getCopperBank();
		createFirms(copper, i -> copper, eFirmSavings, nFirmSavings,
				cfg.numEFirms(), foundingNFirmCount());
		createDefaultStrategicSector(copper);
		Bank gold = createDefaultRuler();
		createDefaultGranary(copper);
		createDefaultRetinue();
		foundLaborersFromRetinue(i -> copper, laborerNStock);
		createDefaultChildrenFirm();
		enableExternalInflow(copper);
		installExplorerProvisioning();
		return gold;
	}

	/**
	 * Found the colony as a <b>Captain-led camp</b> at {@link SettlementTier#CAMP} — a foraging
	 * band, not yet a ruler economy (docs/settlement-tier-ladder-plan.md Phase D). It seeds the
	 * peasant pool (the camp's foragers — its workforce) in {@linkplain Retinue#camp() camp mode}
	 * and a {@link Captain} to lead it, and registers a tier-advance callback that <b>boots the
	 * full ruler economy</b> ({@link #bootRulerEconomy}) once the camp grows to
	 * {@link SettlementTier#SMALLHOLDING}. Below Smallholding there are no markets, firms, granary,
	 * banks beyond copper, or ruler — the band lives off the pool's foraged larder.
	 *
	 * @return the copper bank (the only bank a camp has; the gold treasury is minted at the boot)
	 */
	private Bank foundCamp(IntToDoubleFunction eFirmSavings,
			IntToDoubleFunction nFirmSavings, IntToDoubleFunction laborerNStock) {
		colony.setTier(SettlementTier.CAMP);
		Bank copper = getCopperBank();
		// Phase G (forage-as-improvement): give the camp a forage plot to work — its food yield
		// scales the forage, so rich ground climbs and poor ground starves the band into departing —
		// and the HUNTING_CAMP it builds on it over time. A province-less colony claims none and
		// forages the flat fallback.
		colony.setUpCampForage(colony.getSession() == null ? null
				: colony.getSession().getTerrainRegistry().improvement("IMPROVEMENT_HUNTING_CAMP"));
		// the pool — the camp's foragers (its workforce), in camp (settled-foraging) mode. No
		// builder is created (that is a ruler-economy concern, wired at the boot).
		retinue = new Retinue(econ().retinueSize(), copper, colony, retinueConfig);
		retinue.camp();
		colony.addAgent(retinue);
		// the captain — a freshly-drawn band leader. It holds no treasury at camp (the founding
		// gold is minted into the ruler at the boot, exactly as a mature founding mints it at t0),
		// so the camp economy is moneyless.
		Captain captain = new Captain(0, copper, colony);
		captain.setFollowing(retinue);
		colony.addAgent(captain);
		// boot the ruler economy when the camp climbs to SMALLHOLDING — deferred to end of step,
		// where the heavy agent swap (Captain -> Ruler, camp pool -> settled reserve, firms/banks
		// chartered) is safe (mirrors the RankLadder reform timing).
		colony.setOnTierAdvance(newTier -> {
			// CAMP..HAMLET -> SMALLHOLDING crosses CARAVAN -> VILLAGE: boot the ruler economy
			// (Captain -> Ruler). TOWN -> METROPOLIS crosses VILLAGE -> CITY: reform Ruler -> Mayor
			// (R2). Both deferred to end of step (the heavy agent swaps are unsafe mid-step). The
			// callback stays live past the boot so the later METROPOLIS crossing still fires.
			if (newTier == SettlementTier.SMALLHOLDING)
				colony.scheduleEndOfStepAction(
						() -> bootRulerEconomy(eFirmSavings, nFirmSavings, laborerNStock));
			else if (newTier == SettlementTier.METROPOLIS)
				colony.scheduleEndOfStepAction(this::reformRulerToMayor);
		});
		// the symmetric head descent (R4): METROPOLIS -> TOWN crosses CITY -> VILLAGE, so a shrinking
		// metropolis reforms its Mayor back into a Ruler. (The un-boot below SMALLHOLDING is deferred —
		// booted colonies floor their starvation-descent at SMALLHOLDING, see Settlement.grow().)
		colony.setOnTierDescent(newTier -> {
			if (newTier == SettlementTier.TOWN)
				colony.scheduleEndOfStepAction(this::reformMayorToRuler);
		});
		return copper;
	}

	/**
	 * Boot the <b>full ruler economy</b> onto a camp that has grown to {@link
	 * SettlementTier#SMALLHOLDING} (the promotion trigger, docs/settlement-tier-ladder-plan.md
	 * Phase D3). This is the deferred remainder of {@link #foundStandardColony}: it mirrors {@link
	 * #reFoundStandardColony} treating the camp as a settling band — the {@link Captain} is the
	 * band's leader (reformed into the {@link com.civstudio.agent.ruler.Ruler}) and the camp pool
	 * is its following (reformed into the settled relief reserve). Runs at end of step.
	 */
	private void bootRulerEconomy(IntToDoubleFunction eFirmSavings,
			IntToDoubleFunction nFirmSavings, IntToDoubleFunction laborerNStock) {
		createMarkets();
		Bank copper = getCopperBank();
		// found the settled economy's firms genesis-style (developed plots granted for free, as at a
		// fresh founding) even though the colony has started — the builder does not exist yet.
		colony.setGenesisFounding(true);
		createFirms(copper, i -> copper, eFirmSavings, nFirmSavings,
				cfg.numEFirms(), foundingNFirmCount());
		colony.setGenesisFounding(false);
		createDefaultStrategicSector(copper);
		// reform the captain into the ruler: its head leads the settlement, the founding treasury
		// is minted (as a mature founding does — the camp captain held none), and the captain is
		// retired (its account closed and person-of-interest cleared by scheduleRemoveAgent).
		Captain captain = findCaptain();
		Member leader = captain.getHead();
		colony.scheduleRemoveAgent(captain);
		createRulerFromLeader(leader, CurrencyType.GOLD.toCopper(DEFAULT_RULER_GOLD));
		createDefaultGranary(copper);
		// reform the camp pool into the settled reserve: a fresh Retinue (with the builder it
		// staffs) adopts the camp foragers and their larder, exactly like a band re-founding.
		Retinue campPool = retinue;
		createRetinueFromPool(campPool, copper);
		colony.scheduleRemoveAgent(campPool);
		foundLaborersFromRetinue(i -> copper, laborerNStock);
		createDefaultChildrenFirm();
		enableExternalInflow(copper);
		installExplorerProvisioning();
		// the economy is booted; the tier-advance callback stays live — a further climb to
		// METROPOLIS reforms the Ruler into a Mayor (R2), so it must keep firing.
		log.info(colony.getName() + " booted its ruler economy on " + colony.getDate()
				+ " (grew from camp to Smallholding)");
		// let a camp-founding scenario wire its economy-coupled printers now that the ruler,
		// firms, banks and markets exist (they did not at the camp founding — see foundCamp)
		if (onEconomyBooted != null)
			onEconomyBooted.run();
	}

	/**
	 * Register a hook run <b>once the ruler economy has booted</b> — the end of the
	 * {@link SettlementTier#SMALLHOLDING} crossing for a {@linkplain SimulationConfig#foundAtCamp()
	 * camp-founded} colony (a no-op for a mature founding, which has no boot). A camp-founding
	 * scenario uses it to wire the printers that reference the ruler/firms/granary/markets — none
	 * of which exist at the camp founding. Fires at most once.
	 *
	 * @param hook
	 *            run at the end of the ruler-economy boot
	 */
	public void setOnEconomyBooted(Runnable hook) {
		this.onEconomyBooted = hook;
	}

	// the colony's captain (the camp's head household); exactly one exists at the boot.
	private Captain findCaptain() {
		for (com.civstudio.agent.Agent a : colony.getAgents())
			if (a instanceof Captain c && c.isAlive())
				return c;
		throw new IllegalStateException("no Captain to reform at the ruler-economy boot");
	}

	// install the seasonal explorer levy (docs/explorer-caravan.md) as a colony step action.
	// The DEFAULT behaviour for a district-bearing settlement (TOWN and up, with a pool to draft
	// from) — it musters winter foraging expeditions; a single-centre SMALLHOLDING does not.
	private void installExplorerProvisioning() {
		if (colony.hasDistricts() && retinue != null) {
			explorerProvisioner = new ExplorerProvisioner(colony, retinue);
			explorerProvisioner.setReward(mobility()); // the renewal loop on a live return
			colony.addStepAction(explorerProvisioner);
		}
	}

	/**
	 * A monthly snapshot of the colony's explorer-expedition activity (the renewal loop) for the
	 * {@link com.civstudio.io.printer.ExpeditionsPrinter}: bands out now / mustered / returned, plus
	 * the reward split (households founded, returnees ennobled to lead, returns an abler noble led).
	 * {@link ExpeditionStats#NONE} for a colony that musters none (a Village, or a bare sim).
	 *
	 * @return the current expedition tallies
	 */
	public ExpeditionStats expeditionStats() {
		if (explorerProvisioner == null)
			return ExpeditionStats.NONE;
		return new ExpeditionStats(colony.getExcursions().size(),
				explorerProvisioner.getMustered(), mobility().getExpeditionReturns(),
				mobility().getExpeditionFounded(), mobility().getExpeditionEnnobled(),
				mobility().getExpeditionNobleLed());
	}

	/**
	 * The colony's expedition-return reward handler — the renewal loop (a returned explorer peasant
	 * founds its own household; see {@link SocialMobility} / {@code docs/explorer-caravan.md}).
	 * Package-visible so a test can wire a directly-driven band to it.
	 *
	 * @return the reward handler
	 */
	com.civstudio.agent.ExpeditionReturn expeditionRewardHandler() {
		return mobility();
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
	public Bank reFoundStandardColony(SettlerCaravan band, IntToDoubleFunction eFirmSavings,
			IntToDoubleFunction nFirmSavings, IntToDoubleFunction laborerNStock) {
		createMarkets();
		Bank copper = getCopperBank();
		createFirms(copper, i -> copper, eFirmSavings, nFirmSavings,
				cfg.numEFirms(), foundingNFirmCount());
		createDefaultStrategicSector(copper);
		// resume the band's carried tech tree (overriding the fresh warm-start the
		// strategic sector just installed), re-applying its researched techs' effects
		if (band.getResearch() != null)
			colony.setResearch(ResearchState.restore(
					colony.getSession().getTechTree(colony.getFoundingRace()), colony,
					band.getResearch(), cfg.researchCostScale()));
		Bank gold = createRulerFromLeader(band.getLeader(), band.getHoard());
		createDefaultGranary(copper);
		createRetinueFromBand(band, copper);
		foundLaborersFromRetinue(i -> copper, laborerNStock);
		createDefaultChildrenFirm();
		enableExternalInflow(copper);
		installExplorerProvisioning();
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
		// the explorer-expedition renewal loop (docs/explorer-caravan.md): bands out/mustered/
		// returned + the return-reward split (households founded, returnees ennobled, returns an
		// abler noble led). All-zero for a colony that musters none (a Village / a bare sim).
		colony.addPrinter(new ExpeditionsPrinter(prefix + "Expeditions", this::expeditionStats));
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
	 * Register the province plot-inventory printers (a {@linkplain
	 * com.civstudio.geo.Province province}-founded colony only): the periodic {@link
	 * ProvinceInventoryPrinter} (a monthly tally of the colony's claimed plots by
	 * terrain/relief/feature/bonus) and the one-time {@link PlotMapPrinter} (a full
	 * dump of the province's plot field). For a province-less colony both produce
	 * nothing meaningful (no shared field), so they are only wired by the
	 * province-founded scenarios. See {@code docs/province-plots.md}.
	 *
	 * @param prefix prepended to each printer's filename ({@code ""} for the default)
	 */
	public void addPlotInventoryPrinters(String prefix) {
		// the inventory is per-settlement (what this colony holds); always register it
		colony.addPrinter(new ProvinceInventoryPrinter(prefix + "Inventory"));
		// the plot map is the whole barony's (province's) land, not any one
		// settlement's — register it once per province (the first settlement to ask),
		// so a province shared by several settlements dumps its field once
		if (colony.getProvince() != null
				&& colony.getSession().firstPlotMapFor(colony.getProvince()))
			colony.addPrinter(new PlotMapPrinter(prefix + "PlotMap"));
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
		// the child population + civic school (a ruler-bearing colony only); skipped
		// when the colony has no school (see createDefaultChildrenFirm)
		if (childrenFirm != null)
			colony.addPrinter(new ChildrenPrinter(prefix + "Children", childrenFirm));
	}

	/** Run the simulation for the configured number of steps, then clean up. */
	public void run() {
		colony.run(cfg.numStep());
		long fissions = getFissionCount();
		if (fissions > 0)
			log.info("household fissions over the run: " + fissions);
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
