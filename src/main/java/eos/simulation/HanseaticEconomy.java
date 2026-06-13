package eos.simulation;

import java.util.List;

import eos.agent.firm.StrategicFirmConfig;
import eos.agent.noble.Noble;
import eos.agent.noble.NobleConfig;
import eos.bank.Bank;
import eos.bank.CurrencyType;
import eos.io.SimLog;
import eos.io.printer.NoblesPrinter;
import eos.io.printer.PersonsOfInterestPrinter;
import eos.io.printer.StrategicPrinter;
import eos.settlement.GameSession;
import eos.settlement.Settlement;

/**
 * Simulation (two settlements in one game session): the worked example of several
 * colonies coexisting in a single {@link GameSession}. Two neighbouring Hanseatic
 * settlements near Lübeck are built from the same session and run independently:
 * <ul>
 * <li><b>Lübeck Altstadt</b> — 53.86730°N, 10.68744°E</li>
 * <li><b>Bad Schwartau</b> — 53.91992°N, 10.69753°E</li>
 * </ul>
 * Each colony gets its <b>own</b> economic random stream (seeded from the session
 * seed and the colony's index) and its own markets, banks and agents, but the two
 * <b>share</b> the session's {@link eos.name.NameRegistry} and {@link
 * eos.mortality.Demography} — so dynasty surnames are unique across <em>both</em>
 * settlements. They do not trade with each other; the point is that independent
 * colonies can run in one session reproducibly.
 * <p>
 * Both colonies have the same composition and the default tiered banking
 * ({@link SimulationHarness#getCopperBank()}): {@value #NUM_LABORERS} laborers,
 * {@value #NUM_EFIRMS} enjoyment firms, {@value #NUM_NFIRMS} necessity firms, one
 * capital firm and one {@link eos.agent.firm.StrategicFirm export firm}. The export
 * firm is worked by <b>nobles</b> (its labor pool is the aristocracy), so each
 * colony also has a small noble class of {@value #NUM_NOBLES} households who staff
 * the export sector and bank in silver. (The spec named no nobles; this is the
 * minimum needed to make the strategic sector function — tune {@link #NUM_NOBLES}
 * or drop the strategic firm to remove it.)
 * <p>
 * Each noble opens with 10 silver and <b>stockpiles a necessity reserve</b> of
 * {@value #NECESSITY_RESERVE_DAYS} days of the whole population's consumption
 * (collectively) — a reserve for a later feature to draw on. Building that reserve
 * needs spare necessity output, supplied here by splitting the consumer firms with
 * <b>fewer enjoyment than necessity firms</b> ({@link #NUM_EFIRMS} &lt; {@link
 * #NUM_NFIRMS}): that gives the necessity sector the headroom to absorb the
 * reserve buying without a price runaway, so the colony holds at the minimum
 * stable scale.
 * <p>
 * The two colonies write to the same {@code output/} directory, so each colony's
 * CSVs are prefixed with its name ({@code Lubeck-}/{@code Schwartau-}). Because
 * {@link SimLog} is process-global it tracks one colony's date at a time; it is
 * pointed at each colony in turn as that colony runs.
 */
public class HanseaticEconomy {

	/** Seed for the shared game session, so the whole run is reproducible. */
	static final long SEED = 7654321L;

	/**
	 * Laborer households per settlement — the minimum stable closed scale (k=4; see
	 * {@link ScaleSweep}). The consumer firms are split with <b>fewer enjoyment than
	 * necessity firms</b> ({@link #NUM_EFIRMS} &lt; {@link #NUM_NFIRMS}) so the
	 * necessity sector has the production headroom to absorb the nobles' reserve
	 * buying (see {@link #NECESSITY_RESERVE_DAYS}) without a price spike — which lets
	 * the colony stay at this minimum scale rather than having to be enlarged.
	 */
	static final int NUM_LABORERS = 180;

	/** Enjoyment firms per settlement (fewer than necessity firms). */
	static final int NUM_EFIRMS = 4;

	/** Necessity firms per settlement (more, for necessity-reserve headroom). */
	static final int NUM_NFIRMS = 6;

	/**
	 * Noble households per settlement that staff the export sector (the strategic
	 * firm employs nobles, so it needs some). Not named in the spec. The strategic
	 * firm pays a fixed wage budget regardless of output, so when its nobles are
	 * unproductive it injects money without earning exports — inflationary; enough
	 * nobles ({@link StrategicEconomy}'s count) keeps the per-colony skill draw
	 * reliable so both settlements' export sectors stay productive.
	 */
	static final int NUM_NOBLES = 5;

	/** Each noble's opening fortune, in <b>silver</b> (held in copper internally). */
	static final double NOBLE_INITIAL_SILVER = 10;

	/**
	 * Days of population necessity the nobles collectively stockpile as a reserve.
	 * Each noble aims for {@value #NECESSITY_RESERVE_DAYS} × (laborers/nobles)
	 * units, so the nobles together hold {@value #NECESSITY_RESERVE_DAYS} days of
	 * the whole population's necessity consumption — set up for a later feature
	 * that draws on that reserve.
	 */
	static final double NECESSITY_RESERVE_DAYS = 7;

	/**
	 * Noble parameters for this colony: the canonical defaults, but the nobles
	 * stockpile the necessity reserve above.
	 */
	static final NobleConfig NOBLE_CONFIG = NobleConfig.DEFAULT.toBuilder()
			.necessityReserveDays(NECESSITY_RESERVE_DAYS).build();

	/**
	 * Build and run both settlements, returning the first ({@code Lübeck}) harness
	 * as the convention hook; both colonies are fully built and run.
	 *
	 * @return the Lübeck Altstadt harness (Bad Schwartau is also built and run)
	 */
	public static SimulationHarness run() {
		GameSession session = new GameSession(SEED);

		// the two colonies share the session's name pool and demography but each has
		// its own economic random stream and its own location
		SimulationConfig base = SimulationConfig.DEFAULT.toBuilder()
				.numLaborers(NUM_LABORERS)
				.numEFirms(NUM_EFIRMS)
				.numNFirms(NUM_NFIRMS)
				.build();
		SimulationConfig lubeckCfg = base.toBuilder()
				.settlementName("Lübeck Altstadt")
				.latitude(53.86730).longitude(10.68744).build();
		SimulationConfig schwartauCfg = base.toBuilder()
				.settlementName("Bad Schwartau")
				.latitude(53.91992).longitude(10.69753).build();

		Settlement lubeck = session.newSettlement(lubeckCfg.settlementName(),
				lubeckCfg.startDate(), lubeckCfg.meanInitAgeYears(),
				lubeckCfg.targetNStock(), lubeckCfg.meanSkill(),
				lubeckCfg.latitude(), lubeckCfg.longitude());
		Settlement schwartau = session.newSettlement(schwartauCfg.settlementName(),
				schwartauCfg.startDate(), schwartauCfg.meanInitAgeYears(),
				schwartauCfg.targetNStock(), schwartauCfg.meanSkill(),
				schwartauCfg.latitude(), schwartauCfg.longitude());

		// route logging through the in-game date before any agent is constructed
		SimLog.init(lubeck);

		SimulationHarness hLubeck = build(lubeckCfg, lubeck, "Lubeck-");
		SimulationHarness hSchwartau = build(schwartauCfg, schwartau, "Schwartau-");

		// run each colony to completion in turn, pointing the global log at the one
		// currently running so its records carry the right date
		hLubeck.run();
		SimLog.init(schwartau);
		hSchwartau.run();

		return hLubeck;
	}

	/**
	 * Populate one settlement: the default tiered banking (commoners in copper,
	 * nobles in silver), the consumer/capital/export firms, the noble export
	 * workforce, the laborers, and the (name-prefixed) printers. Mirrors the
	 * construction order of {@link StrategicEconomy}. The colony is not yet run.
	 *
	 * @param cfg
	 *            this settlement's configuration (with its own latitude/longitude)
	 * @param colony
	 *            the colony to populate (already created from the session)
	 * @param prefix
	 *            the per-settlement CSV filename prefix (e.g. {@code "Lubeck-"})
	 * @return the populated harness
	 */
	private static SimulationHarness build(SimulationConfig cfg, Settlement colony,
			String prefix) {
		SimulationHarness h = new SimulationHarness(cfg, colony);
		h.createMarkets();

		// default tiered banking: commoners (laborers + firms, incl. the export
		// firm) in copper, the nobles in silver
		Bank copper = h.getCopperBank();
		Bank silver = h.getSilverBank();

		// the noble-only labor market must exist before the export firm and the
		// nobles are created (both look it up)
		h.createNobleLaborMarket();
		h.createFirms(copper, i -> copper,
				i -> cfg.eFirm().savings(), i -> cfg.nFirm().savings());
		h.createStrategicFirm(copper, StrategicFirmConfig.DEFAULT);

		// the nobles who work the export sector (they own no firms or banks here),
		// opening with 10 silver and stockpiling a necessity reserve (NOBLE_CONFIG)
		for (int n = 0; n < NUM_NOBLES; n++)
			colony.addAgent(new Noble(0,
					CurrencyType.SILVER.toCopper(NOBLE_INITIAL_SILVER),
					List.of(), List.of(), NOBLE_CONFIG, silver, colony));
		// a same-dynasty successor is produced by the colony's built-in household-
		// succession policy (see Noble.successor), which reuses each noble's own
		// NobleConfig — so the stockpiling reserve carries across generations

		// clear the noble labor market once so the export firm has workers in step 0
		h.primeNobleLabor();

		h.createLaborers(i -> copper, i -> 15, i -> cfg.laborer().savings());
		h.enableExternalInflow(copper);

		// every settlement has a ruler, banking in gold (created last)
		Bank gold = h.createDefaultRuler();

		h.addCommonPrinters(prefix);
		h.addBankPrinter(prefix + "Copper", copper);
		h.addBankPrinter(prefix + "Silver", silver);
		h.addBankPrinter(prefix + "Gold", gold);
		colony.addPrinter(new StrategicPrinter(prefix + "Strategic",
				h.getStrategicFirm(), copper));
		colony.addPrinter(new NoblesPrinter(prefix + "Nobles"));
		colony.addPrinter(new PersonsOfInterestPrinter(prefix + "PersonsOfInterest"));
		return h;
	}

	public static void main(String[] args) {
		run();
	}
}
