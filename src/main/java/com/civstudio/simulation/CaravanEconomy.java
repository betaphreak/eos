package com.civstudio.simulation;

import java.util.ArrayList;
import java.util.List;

import com.civstudio.agent.MigrantCaravan;
import com.civstudio.agent.Member;
import com.civstudio.agent.Retinue;
import com.civstudio.bank.Bank;
import com.civstudio.bank.BankConfig;
import com.civstudio.bank.CurrencyType;
import com.civstudio.geo.Province;
import com.civstudio.geo.WorldMap;
import com.civstudio.good.Good;
import com.civstudio.name.NameRegistry;
import com.civstudio.io.SimLog;
import com.civstudio.name.Person;
import com.civstudio.settlement.GameSession;
import com.civstudio.settlement.Settlement;
import com.civstudio.util.Rng;

/**
 * Simulation (three caravans on the world map, in one game session): a
 * <b>Caravan-first</b> run that exercises the whole rise-and-fall cycle with the
 * province graph underneath it (see {@code docs/caravan.md},
 * {@code docs/caravan-trade.md}). Three wandering bands set out with <b>different
 * endowments</b> — a carried hoard of {@code 50}, {@code 100} and {@code 200} gold,
 * and lean/middling/ample larders — each anchored to a <b>starting province</b> on
 * the {@link WorldMap}. Each band then <b>wanders the graph</b> (one hop per day, on
 * the session band RNG, eating its carried larder) to the nearest viable site and
 * <b>re-founds a settlement into that province</b> — so the new colony inherits the
 * province's climate and its {@code plots} cap on size, not just bare coordinates.
 * Each colony then runs until its peasant reserve drains and it <b>collapses back into
 * a Caravan</b> (the {@code HOLDING → CARAVAN} dissolution): the survivors take to the
 * road again, their dynasty surnames returning to the pool.
 * <p>
 * Unlike the bounded sims this one is <b>not capped by a horizon</b>: each colony is
 * run until it dissolves, and the run ends once <b>all three bands have reformed</b>.
 * The three colonies share the session's {@link NameRegistry} and {@link
 * Demography} (so dynasty surnames are unique across all of them) but each has its own
 * economic stream, markets, banks and agents; they are built and run in turn (like
 * {@link TwinSettlementEconomy}), each writing name-prefixed CSVs.
 * <p>
 * Because a band's {@link Retinue following} must live on <em>some</em> colony, the
 * three bands are first mustered on a throwaway colony (never run) that only mints
 * their people; each band then wanders off and re-founds a real colony of its own.
 */
public class CaravanEconomy {

	/** Seed for the shared game session, so the whole run is reproducible. */
	static final long SEED = 1234567L;

	/** People in each band's following (the pool the labor force is promoted from). */
	static final int FOLLOWERS = 900;

	// a runaway guard on the wander: a band reaches the nearest viable site in a few
	// hops, well under this, so this only stops a pathological band from looping forever
	private static final int MAX_WANDER_DAYS = 365 * 5;

	/** One band's starting endowment (where it settles is found by wandering). */
	private record Band(String name, double gold, double larder) {
	}

	// a band sets out with ten times the food of a steady-state larder, matching the
	// generous opening cushion a freshly-founded colony gets (see Retinue.STARTING_FOOD_MULTIPLIER)
	private static final double STARTING_FOOD_MULTIPLIER = 10.0;

	// the three bands: ascending hoard (50/100/200 gold) paired with ascending food
	// (lean/middling/ample larders), each carrying ten times the steady-state larder
	private static final Band[] BANDS = {
			new Band("Aurelia", 50, FOLLOWERS * 13.0 * STARTING_FOOD_MULTIPLIER),
			new Band("Belmonte", 100, FOLLOWERS * 22.0 * STARTING_FOOD_MULTIPLIER),
			new Band("Cortona", 200, FOLLOWERS * 35.0 * STARTING_FOOD_MULTIPLIER),
	};

	/**
	 * Muster three bands, have each wander to a viable site, re-found and run to
	 * collapse, and return the first ({@code Aurelia}) harness; all three colonies are
	 * fully built and run, and end reformed as Caravans (registered with the session).
	 *
	 * @return the Aurelia harness (Belmonte and Cortona are also built and run)
	 */
	public static SimulationHarness run() {
		GameSession session = new GameSession(SEED);
		SimulationConfig cfg = SimulationConfig.DEFAULT; // closed → the colonies collapse
		WorldMap map = session.getWorldMap();

		// the bands set out from distinct viable starting provinces, each with a viable
		// neighbour to wander to (so the settle decision always succeeds)
		List<Integer> starts = pickStartProvinces(map, BANDS.length);

		// 1) muster the three bands on a throwaway colony (it is never run; it only
		// hosts the bands' followings while they are mustered)
		Settlement muster = session.newSettlement("muster", cfg.startDate(),
				cfg.meanInitAgeYears(), cfg.targetNStock(), cfg.meanSkillMale(),
				cfg.meanSkillFemale(), 0, 0);
		SimLog.init(muster);
		Bank musterBank = new Bank(BankConfig.DEFAULT, muster);
		MigrantCaravan[] caravans = new MigrantCaravan[BANDS.length];
		for (int i = 0; i < BANDS.length; i++)
			caravans[i] = musterBand(BANDS[i], starts.get(i), musterBank, muster);

		// 2) each band wanders to a viable site, re-founds a colony there, and runs
		// until it dissolves back into a Caravan; the run ends once all three reform.
		// The march journal records each band's daily daylight-bounded march (its HH:mm
		// order of march and the provinces/camp it crosses — see docs/caravan-march.md)
		com.civstudio.io.printer.CaravanMarchPrinter journal =
				new com.civstudio.io.printer.CaravanMarchPrinter("output/" + session.getSeed());
		for (MigrantCaravan c : caravans)
			c.setCampingEnabled(true);
		SimulationHarness first = null;
		for (int i = 0; i < BANDS.length; i++) {
			wander(caravans[i], cfg.startDate(), session.getBandRng(), journal);
			SimulationHarness h = settleAndRun(session, cfg, BANDS[i], caravans[i]);
			if (first == null)
				first = h;
		}
		journal.close();
		System.out.println("All " + session.getCaravans().size()
				+ " bands have reformed as Caravans.");
		return first;
	}

	// the first n settleable provinces that each have at least one viable settleable
	// neighbour — so a band starting there can wander one hop and settle. Deterministic
	// (settleableProvinces() is in load order).
	private static List<Integer> pickStartProvinces(WorldMap map, int n) {
		List<Integer> starts = new ArrayList<>();
		for (Province p : map.settleableProvinces()) {
			if (!isViable(p))
				continue;
			for (int nb : p.neighbors()) {
				if (nb != p.id() && isViable(map.province(nb))) {
					starts.add(p.id());
					break;
				}
			}
			if (starts.size() == n)
				break;
		}
		if (starts.size() < n)
			throw new IllegalStateException(
					"the map has fewer than " + n + " viable starting provinces");
		return starts;
	}

	// whether a province can be founded into: settleable land with at least the
	// minimum founding footprint of plots
	private static boolean isViable(Province p) {
		return p.isSettleable() && p.plots() >= Settlement.MIN_FOUNDING_PLOTS;
	}

	// build one Caravan with the band's own hoard, larder and following, anchored at its
	// starting province on the graph
	private static MigrantCaravan musterBand(Band b, int startProvinceId, Bank bank,
			Settlement muster) {
		Retinue following = new Retinue(FOLLOWERS, bank, muster);
		// the int constructor sized the larder to FOLLOWERS·BUFFER_DAYS; set it to the
		// band's own food (lean/middling/ample)
		Good larder = following.getGood("Necessity");
		double have = larder.getQuantity();
		if (b.larder() > have)
			larder.increase(b.larder() - have);
		else
			larder.decrease(have - b.larder());
		// pull the ablest peasant as the band's leader, giving it a dynasty surname (a
		// leader is a household head, not a surname-less peasant) — it becomes the ruler
		// when the band settles, and leads the band again when the colony collapses
		Member raw = following.promoteHighestSkilled();
		Member leader = new Member(new Person(raw.person().givenName(),
				muster.getNames().nextDynastyName(raw.race()), raw.gender(),
				raw.skills(), raw.race()),
				raw.getBirthDate());
		return new MigrantCaravan(leader, following, CurrencyType.GOLD.toCopper(b.gold()),
				startProvinceId, muster.getSession());
	}

	// wander the band over the province graph until it reaches a viable site and marks
	// itself ready to settle (one hop per day, eating its carried larder)
	private static void wander(MigrantCaravan band, java.time.LocalDate date, Rng rng,
			com.civstudio.io.printer.CaravanMarchPrinter journal) {
		int days = 0;
		while (!band.isReadyToSettle() && days < MAX_WANDER_DAYS) {
			var report = band.tick(date.plusDays(days), rng);
			if (report != null)
				journal.record(report);
			days++;
		}
		if (!band.isReadyToSettle())
			throw new IllegalStateException(
					"a band failed to reach a viable site within " + MAX_WANDER_DAYS
							+ " days");
	}

	// re-found a colony from the band into the province it settled in, wire its
	// printers, and run it until it dissolves
	private static SimulationHarness settleAndRun(GameSession session,
			SimulationConfig cfg, Band b, MigrantCaravan band) {
		Settlement colony = session.newSettlement(band, b.name(), cfg.startDate(),
				cfg.meanInitAgeYears(), cfg.targetNStock(), cfg.meanSkillMale(),
				cfg.meanSkillFemale());
		// point the global log at the colony currently running (its records carry the
		// right settlement and date)
		SimLog.init(colony);
		SimulationHarness h = new SimulationHarness(cfg, colony);
		String prefix = b.name() + "-";
		h.reFoundStandardColony(band, i -> cfg.eFirm().savings(),
				i -> cfg.nFirm().savings(), i -> 15);
		h.addCommonPrinters(prefix);
		h.addRetinuePrinter(prefix + "Retinue");
		h.addBanksPrinter(prefix + "Banks");
		h.addStrategicSectorPrinters(prefix, h.getCopperBank());

		// run unbounded by any end date: stops only when the colony's workforce drains
		// and it crosses the HOLDING → CARAVAN hinge (the survivors depart as a new Caravan)
		colony.run();
		colony.cleanUpPrinters();
		return h;
	}

	public static void main(String[] args) {
		run();
	}
}
