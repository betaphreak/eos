package com.civstudio.settlement;

import java.util.IdentityHashMap;
import java.util.Map;

import com.civstudio.agent.Agent;
import com.civstudio.agent.Household;
import com.civstudio.bank.Account;
import com.civstudio.good.RationSize;
import com.civstudio.market.ConsumerGoodMarket;
import com.civstudio.market.Demand;

/**
 * The <b>village-larder subsystem</b> (city-of-hamlets V2, {@code docs/city-of-hamlets-plan.md}): the
 * per-hamlet {@link Larder} food pools that organize a city's food per <b>village</b> rather than per
 * household, and the daily <b>provisioning</b> that keeps them stocked. Present only when {@code
 * SimulationConfig.villageLarder} is on ({@link Settlement#enableVillageLarders()}); a flag-off colony
 * never builds one and stays byte-identical (its {@link Settlement#getVillageLarders()} is {@code
 * null}, and food keeps running through the per-household necessity stock).
 * <p>
 * <b>The provisioned floor.</b> A village's peasant households eat from its larder for free (the lord
 * feeds his vassals); the larder is filled by their home-plot subsistence and topped up by the
 * village's <b>leader</b> (its fief-holder {@link com.civstudio.agent.noble.Noble}, or the {@link
 * com.civstudio.agent.ruler.Ruler} for Crown demesne) buying the deficit on the shared Necessity
 * market. The leader <em>pays</em> — so the village is the market's food participant and its demand
 * drives price discovery — but the food is delivered into the larder, via the market's
 * delivery-target seam ({@link ConsumerGoodMarket#addBuyOffer(Agent, com.civstudio.good.Good,
 * Demand)}). A leader buys only what its purse affords, so a poor lord under-provisions (its village
 * goes hungry — the failure the survival tests gate) rather than borrowing without bound.
 */
public final class VillageLarders {

	// the founding food endowment stocked into a larder when it is first created, so the village's
	// first day of eating — before the leader's first import settles at that day's market clear — is
	// fed. A one-time founding buffer (the analogue of a colony's founding food). UNCALIBRATED.
	private static final double FOUNDING_STOCK = 100.0;

	// the larder floor the leader tops up to, in DAYS of the village's daily ration need — a buffer so
	// a single day's timing or a thin market does not starve the village. UNCALIBRATED.
	private static final int FLOOR_DAYS = 5;

	// the owning colony, for the hamlet grouping, market and leaders the provisioning reads
	private final Settlement colony;

	// one larder per hamlet, keyed by its seat plot identity (IdentityHashMap: two distinct plots
	// with equal fields never share a larder)
	private final Map<Plot, Larder> larders = new IdentityHashMap<>();

	VillageLarders(Settlement colony) {
		this.colony = colony;
	}

	/**
	 * The larder for a hamlet whose seat is {@code seat}, created (and pre-stocked with the founding
	 * buffer) on first use so every hamlet the colony projects has exactly one.
	 *
	 * @param seat the hamlet's seat plot
	 * @return the hamlet's larder (never {@code null})
	 */
	Larder larderFor(Plot seat) {
		return larders.computeIfAbsent(seat, k -> {
			Larder l = new Larder();
			l.stock(FOUNDING_STOCK); // founding endowment so day-1 eating does not starve
			return l;
		});
	}

	/**
	 * The larder for a hamlet whose seat is {@code seat}, or {@code null} if none has been created yet
	 * — a non-creating lookup.
	 *
	 * @param seat the hamlet's seat plot
	 * @return the existing larder, or {@code null}
	 */
	Larder larderIfPresent(Plot seat) {
		return larders.get(seat);
	}

	/** How many hamlet larders exist (a test/report seam). */
	int count() {
		return larders.size();
	}

	/**
	 * <b>Provision the village larders</b> — for each hamlet, its leader posts a buy offer for the
	 * larder's food deficit (the {@link #FLOOR_DAYS}-day floor minus what is left after the day's
	 * eating), funded by the leader's account and delivered into the larder. Called by {@link
	 * Settlement#newDay()} in the act phase, after households have eaten and before the market clears,
	 * so the fill lands for the next day. A price-sensitive, purse-capped demand: a leader buys up to
	 * the deficit but spends no more than it holds.
	 */
	void provision() {
		if (!(colony.getMarket("Necessity") instanceof ConsumerGoodMarket nMkt))
			return;
		for (Hamlet ham : colony.hamlets()) {
			Larder larder = larderFor(ham.seat());
			double deficit = FLOOR_DAYS * dailyNeed(ham) - larder.available();
			if (deficit <= 0)
				continue;
			// the village's leader pays: its fief-holder noble (resolved by id), or the Crown for a
			// demesne village. getHouseholdById returns a LIVING household and special-cases the ruler,
			// so a dead or missing leader simply drops out.
			Household leaderH = ham.leaderId() == null
					? colony.getRuler()
					: colony.getHouseholdById(ham.leaderId());
			if (leaderH == null)
				continue;
			// getHouseholdById already returns only a LIVING household; the ruler (demesne path) may
			// not be, so re-check on the Agent (a Noble/Ruler is always an Agent)
			Agent leader = (Agent) leaderH;
			if (!leader.isAlive())
				continue;
			Account acct = leader.getBank().getAcct(leader.getID());
			double budget = Math.max(0, acct.getChecking() + acct.getSavings());
			// buy up to the deficit, spending at most the leader's purse: a poor lord under-provisions
			// (its village goes hungry) rather than borrowing without bound
			Demand d = price -> price <= 0 ? deficit : Math.min(deficit, budget / price);
			nMkt.addBuyOffer(leader, larder.good(), d);
		}
	}

	// the village's daily food need: a worker ration per resident person (an over-estimate — children
	// eat a smaller ration — so the floor carries a little slack). Reads the hamlet's households.
	private double dailyNeed(Hamlet ham) {
		double need = 0;
		for (var h : ham.households())
			need += h.getMemberCount() * RationSize.FINE.perDay();
		return need;
	}
}
