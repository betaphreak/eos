package com.civstudio.simulation;

import com.civstudio.mortality.Demography;
import com.civstudio.agent.Caravan;
import com.civstudio.agent.Member;
import com.civstudio.agent.Retinue;
import com.civstudio.bank.Bank;
import com.civstudio.bank.BankConfig;
import com.civstudio.bank.CurrencyType;
import com.civstudio.good.Good;
import com.civstudio.name.NameRegistry;
import com.civstudio.io.SimLog;
import com.civstudio.name.Person;
import com.civstudio.settlement.GameSession;
import com.civstudio.settlement.Settlement;

/**
 * Simulation (three caravans in one game session): a <b>Caravan-first</b> run that
 * exercises the whole rise-and-fall cycle (see {@code docs/caravan.md}). Three
 * wandering bands set out with <b>different endowments</b> — a carried hoard of
 * {@code 50}, {@code 100} and {@code 200} gold, and lean/middling/ample larders —
 * and each <b>re-founds a settlement</b> at its own location (London, Paris, Rome).
 * Each colony then runs until its peasant reserve drains and it <b>collapses back
 * into a Caravan</b> (the {@code HOLDING → CARAVAN} dissolution): the survivors take
 * to the road again, their dynasty surnames returning to the pool.
 * <p>
 * Unlike the bounded sims this one is <b>not capped by a horizon</b>: each colony is
 * run until it dissolves, and the run ends once <b>all three bands have reformed</b>.
 * The three colonies share the session's {@link NameRegistry} and {@link
 * Demography} (so dynasty surnames are unique across all of them) but
 * each has its own economic stream, markets, banks and agents; they are built and run
 * in turn (like {@link HanseaticEconomy}), each writing name-prefixed CSVs.
 * <p>
 * Because a band's {@link Retinue following} must live on <em>some</em> colony, the
 * three bands are first mustered on a throwaway colony (never run) that only mints
 * their people; each band then re-founds a real colony of its own.
 */
public class CaravanEconomy {

	/** Seed for the shared game session, so the whole run is reproducible. */
	static final long SEED = 1234567L;

	/** People in each band's following (the pool the labor force is promoted from). */
	static final int FOLLOWERS = 900;

	/** One band's starting endowment and where it settles. */
	private record Band(String name, double gold, double larder, double lat,
			double lon) {
	}

	// the three bands: ascending hoard (50/100/200 gold) paired with ascending food
	// (lean/middling/ample larders), each founding at a different latitude
	private static final Band[] BANDS = {
			new Band("Aurelia", 50, FOLLOWERS * 13.0, 51.5074, -0.1278), // London
			new Band("Belmonte", 100, FOLLOWERS * 22.0, 48.8566, 2.3522), // Paris
			new Band("Cortona", 200, FOLLOWERS * 35.0, 41.9028, 12.4964), // Rome
	};

	// a runaway guard, not a real horizon: each colony drains and dissolves in a few
	// years, well under this, so the run truly ends at collapse, not at a time cap
	private static final int MAX_STEPS = 365 * 200;

	/**
	 * Muster three bands, have each re-found and run to collapse, and return the first
	 * ({@code Aurelia}) harness; all three colonies are fully built and run, and end
	 * reformed as Caravans (registered with the session).
	 *
	 * @return the Aurelia harness (Belmonte and Cortona are also built and run)
	 */
	public static SimulationHarness run() {
		GameSession session = new GameSession(SEED);
		SimulationConfig cfg = SimulationConfig.DEFAULT; // closed → the colonies collapse

		// 1) muster the three bands on a throwaway colony (it is never run; it only
		// hosts the bands' followings while they are mustered)
		Settlement muster = session.newSettlement("muster", cfg.startDate(),
				cfg.meanInitAgeYears(), cfg.targetNStock(), cfg.meanSkillMale(),
				cfg.meanSkillFemale(), 0, 0);
		SimLog.init(muster);
		Bank musterBank = new Bank(BankConfig.DEFAULT, muster);
		Caravan[] caravans = new Caravan[BANDS.length];
		for (int i = 0; i < BANDS.length; i++)
			caravans[i] = musterBand(BANDS[i], musterBank, muster);

		// 2) each band re-founds a colony of its own and runs until it dissolves back
		// into a Caravan; the run ends once all three have reformed
		SimulationHarness first = null;
		for (int i = 0; i < BANDS.length; i++) {
			SimulationHarness h = settleAndRun(session, cfg, BANDS[i], caravans[i]);
			if (first == null)
				first = h;
		}
		System.out.println("All " + session.getCaravans().size()
				+ " bands have reformed as Caravans.");
		return first;
	}

	// build one Caravan with the band's own hoard, larder and following
	private static Caravan musterBand(Band b, Bank bank, Settlement muster) {
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
		return new Caravan(leader, following, CurrencyType.GOLD.toCopper(b.gold()),
				b.lat(), b.lon());
	}

	// re-found a colony from the band, wire its printers, and run it until it dissolves
	private static SimulationHarness settleAndRun(GameSession session,
			SimulationConfig cfg, Band b, Caravan band) {
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

		// run unbounded: stops early when the colony's workforce drains and it crosses
		// the HOLDING → CARAVAN hinge (the survivors depart as a new Caravan)
		colony.run(MAX_STEPS);
		colony.cleanUpPrinters();
		return h;
	}

	public static void main(String[] args) {
		run();
	}
}
