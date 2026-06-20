package com.civstudio.simulation;

import java.util.List;

import com.civstudio.agent.firm.StrategicFirm;
import com.civstudio.agent.firm.StrategicFirmConfig;
import com.civstudio.agent.noble.NobleConfig;
import com.civstudio.mortality.Demography;
import com.civstudio.bank.Bank;
import com.civstudio.geo.Province;
import com.civstudio.name.NameRegistry;
import com.civstudio.io.SimLog;
import com.civstudio.io.printer.NoblesPrinter;
import com.civstudio.io.printer.PersonsOfInterestPrinter;
import com.civstudio.io.printer.StrategicPrinter;
import com.civstudio.settlement.GameSession;
import com.civstudio.settlement.Settlement;

/**
 * Simulation (two settlements in one game session): the worked example of several
 * colonies coexisting in a single {@link GameSession}. Two <b>neighbouring world-map
 * provinces</b> (adjacent on the province graph, ~55°N — a northern, Hanseatic-like
 * climate) are founded from the same session and run independently:
 * <ul>
 * <li><b>Withacen</b> — {@code province_id} 515, 55.97°N (189 plots)</li>
 * <li><b>Hopespeak</b> — {@code province_id} 519, 54.83°N (255 plots)</li>
 * </ul>
 * The two provinces are direct neighbours, so {@code WorldMap.path} connects them
 * in a single step — the adjacency the future caravan trade will route over (see
 * {@code docs/geography.md}). Each province supplies its colony's
 * latitude/longitude and a plots-derived size cap.
 * Each colony gets its <b>own</b> economic random stream (seeded from the session
 * seed and the colony's index) and its own markets, banks, agents, {@link
 * NameRegistry} surname slice and {@link Demography} — so
 * the two run independently, yet dynasty surnames stay unique across <em>both</em>
 * settlements (their surname slices are disjoint). The two colonies run
 * <b>concurrently, one thread each</b>, in lockstep (see {@link SessionRunner}).
 * They do not trade with each other; the point is that independent colonies can
 * run in one session, in parallel, reproducibly per colony.
 * <p>
 * Both colonies have the same composition and the default tiered banking
 * ({@link SimulationHarness#getCopperBank()}): {@value #NUM_LABORERS} laborers, one
 * enjoyment and one necessity firm at founding (the ruler's dynamic provisioning
 * grows them), one capital firm and one {@link StrategicFirm export
 * firm}. The export firm is worked by <b>nobles</b> (its labor pool is the
 * aristocracy), so each
 * colony also has a small noble class of {@value #NUM_NOBLES} households who staff
 * the export sector and bank in silver. (The spec named no nobles; this is the
 * minimum needed to make the strategic sector function — tune {@link #NUM_NOBLES}
 * or drop the strategic firm to remove it.)
 * <p>
 * The nobles (raised from laborers by ennoblement) <b>stockpile a necessity
 * reserve</b> of
 * {@value #NECESSITY_RESERVE_DAYS} days of the whole population's consumption
 * (collectively) — a reserve for a later feature to draw on. Building that reserve
 * needs spare necessity output; rather than a fixed firm split, each colony now
 * starts with one enjoyment and one necessity firm and the ruler's <b>dynamic firm
 * provisioning</b> grows the necessity sector to whatever the reserve buying and the
 * population demand.
 * <p>
 * The two colonies write to the same {@code output/} directory, so each colony's
 * CSVs are prefixed with its name ({@code Withacen-}/{@code Hopespeak-}). The
 * {@link SimLog} handler is process-global but its date source is per-thread, so
 * each colony's worker thread logs its own in-game date with no cross-talk.
 */
public class HanseaticEconomy {

	/** Seed for the shared game session, so the whole run is reproducible. */
	static final long SEED = 7654321L;

	/**
	 * The two neighbouring provinces the colonies are founded into (adjacent on the
	 * world map, ~55°N — a northern, Hanseatic-like climate). Withacen (189 plots)
	 * and Hopespeak (255 plots) are direct neighbors, so {@code WorldMap.path}
	 * connects them in one step — the substrate caravan trade will route over. See
	 * {@code docs/geography.md}.
	 */
	static final int WITHACEN_PROVINCE_ID = 515;
	static final int HOPESPEAK_PROVINCE_ID = 519;

	/**
	 * Laborer households per settlement — the empirical minimum for this
	 * composition with a safety margin. Once firms could carry their wage budget
	 * through zero-activity rest days, the stable region widened far below the old
	 * scale-sweep k=4 (=180): a sweep of this Hanseatic composition (5
	 * nobles, the strategic export firm, the necessity reserve) stays healthy on
	 * <em>both</em> colony streams down to ~12 laborers, with the necessity market
	 * running away (and the near-cliff "passes" turning degenerate) below ~10. This
	 * sits a clean 2x above that erratic cliff. The necessity headroom the nobles'
	 * reserve buying (see {@link #NECESSITY_RESERVE_DAYS}) needs is now supplied by
	 * the ruler's dynamic firm provisioning rather than a fixed firm split.
	 */
	static final int NUM_LABORERS = 20;

	/**
	 * Noble households per settlement that staff the export sector (the strategic
	 * firm employs nobles, so it needs some). Not named in the spec. The strategic
	 * firm pays a fixed wage budget regardless of output, so when its nobles are
	 * unproductive it injects money without earning exports — inflationary; enough
	 * nobles ({@link SimulationHarness#DEFAULT_NUM_NOBLES}) keeps the per-colony skill
	 * draw reliable so both settlements' export sectors stay productive.
	 */
	static final int NUM_NOBLES = 5;

	/**
	 * Fraction of each colony's seeded pool ({@code 2 * }{@link #NUM_LABORERS}
	 * peasants) the ruler promotes into laborer households on day 0. Both colonies replace dead
	 * laborers by promotion (the ruler elevates the ablest peasant, not a
	 * same-dynasty heir), so with only this finite reserve and no inflow the labor
	 * force declines once it drains and the colony spirals to collapse — this run
	 * exists to watch how long that takes at the minimum stable scale.
	 */
	static final double PROMOTION_RATIO = 0.8;

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
	 * Build and run both settlements, returning the first ({@code Withacen}) harness
	 * as the convention hook; both colonies are fully built and run.
	 *
	 * @return the Withacen harness (Hopespeak is also built and run)
	 */
	public static SimulationHarness run() {
		GameSession session = new GameSession(SEED);

		// the two colonies are founded into two neighbouring world-map provinces
		// (Withacen and Hopespeak; adjacent, ~55°N) — each supplies its colony's
		// latitude/longitude and a plots-derived size cap. They share the session's
		// name pool and demography but each has its own economic random stream.
		Province withacenProvince = session.getWorldMap().province(WITHACEN_PROVINCE_ID);
		Province hopespeakProvince = session.getWorldMap().province(HOPESPEAK_PROVINCE_ID);
		SimulationConfig base = SimulationConfig.DEFAULT.toBuilder()
				.retinueSize(2 * NUM_LABORERS)
				.promotionRatio(PROMOTION_RATIO)
				.targetNobles(NUM_NOBLES)
				.build();
		SimulationConfig withacenCfg = base.toBuilder()
				.settlementName(withacenProvince.name()).build();
		SimulationConfig hopespeakCfg = base.toBuilder()
				.settlementName(hopespeakProvince.name()).build();

		Settlement withacen = session.newSettlement(withacenCfg.settlementName(),
				withacenCfg.startDate(), withacenCfg.meanInitAgeYears(),
				withacenCfg.targetNStock(), withacenCfg.meanSkillMale(),
				withacenCfg.meanSkillFemale(), withacenProvince);
		Settlement hopespeak = session.newSettlement(hopespeakCfg.settlementName(),
				hopespeakCfg.startDate(), hopespeakCfg.meanInitAgeYears(),
				hopespeakCfg.targetNStock(), hopespeakCfg.meanSkillMale(),
				hopespeakCfg.meanSkillFemale(), hopespeakProvince);

		// install the log handler and bind this thread to Withacen before building it
		SimLog.init(withacen);
		SimulationHarness hWithacen = build(withacenCfg, withacen, "Withacen-");
		// bind to Hopespeak while building it, so its construction-time records
		// carry the right colony
		SimLog.bind(hopespeak);
		SimulationHarness hHopespeak = build(hopespeakCfg, hopespeak, "Hopespeak-");

		// run both colonies concurrently — one thread each — in lockstep: every
		// colony advances one in-game day, then they rendezvous before the next. Each
		// colony's worker thread binds its own colony to the log (see SessionRunner).
		SessionRunner.runConcurrently(List.of(hWithacen, hHopespeak));

		return hWithacen;
	}

	/**
	 * Populate one settlement: the default tiered banking (commoners in copper,
	 * nobles in silver), the consumer/capital/export firms, the noble export
	 * workforce, the laborers, and the (name-prefixed) printers. Mirrors the
	 * construction order of the other strategic-sector sims. The colony is not yet run.
	 *
	 * @param cfg
	 *            this settlement's configuration (with its own latitude/longitude)
	 * @param colony
	 *            the colony to populate (already created from the session)
	 * @param prefix
	 *            the per-settlement CSV filename prefix (e.g. {@code "Withacen-"})
	 * @return the populated harness
	 */
	private static SimulationHarness build(SimulationConfig cfg, Settlement colony,
			String prefix) {
		SimulationHarness h = new SimulationHarness(cfg, colony);
		h.createMarkets();

		// default tiered banking: commoners (laborers + firms, incl. the export
		// firm) in copper, the nobles in silver
		Bank copper = h.getCopperBank();
		// create the silver tier now (before the ruler's gold) so the banks order
		// copper, silver, gold; the export aristocracy raised by ennoblement re-banks
		// into it. The nobles are raised later, so nothing assigns it here.
		h.getSilverBank();

		// the noble-only labor market must exist before the export firm and the
		// nobles are created (both look it up)
		h.createNobleLaborMarket();
		h.createFirms(copper, i -> copper,
				i -> cfg.eFirm().savings(), i -> cfg.nFirm().savings());
		h.createStrategicFirm(copper, StrategicFirmConfig.DEFAULT);

		// the nobles who work the export sector are raised from the laborers by
		// ennoblement (up to NUM_NOBLES — see createDefaultRuler / topUpAristocracy);
		// they stockpile a necessity reserve (NOBLE_CONFIG), and a same-dynasty
		// successor inherits that config, so the reserve carries across generations.
		// The ruler works the strategic firm meanwhile, so it is never unstaffed.
		h.setNobleConfig(NOBLE_CONFIG);

		// clear the (still empty) noble labor market once before the run
		h.primeNobleLabor();

		// the ruler (founding cash) and the pool precede the labor force, which the
		// ruler founds and replaces by promotion from the pool
		h.createDefaultRuler();
		h.createDefaultRetinue();
		h.foundLaborersFromRetinue(i -> copper, i -> 15);
		h.enableExternalInflow(copper);

		h.addCommonPrinters(prefix);
		h.addRetinuePrinter(prefix + "Retinue");
		h.addBanksPrinter(prefix + "Banks");
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
