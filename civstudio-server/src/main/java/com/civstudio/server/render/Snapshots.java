package com.civstudio.server.render;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.civstudio.advisor.AdvisorRole;
import com.civstudio.agent.Agent;
import com.civstudio.agent.Caravan;
import com.civstudio.agent.Household;
import com.civstudio.agent.MarchingCaravan;
import com.civstudio.agent.Member;
import com.civstudio.agent.Retinue;
import com.civstudio.agent.firm.Firm;
import com.civstudio.geo.WorldMap;
import com.civstudio.market.ConsumerGoodMarket;
import com.civstudio.settlement.Settlement;

/**
 * Assembles a {@link SessionSnapshot} — a read-only render projection — from a session's
 * live objects. Every read here is a plain getter over settled state; the assembler is
 * only ever called <b>on the session thread between ticks</b> (from {@code
 * HostedSession.emit}), never during {@code newDay}, so it sees no mid-step mutation and
 * never touches the live graph a client could reach. See {@code docs/client-server.md}.
 */
public final class Snapshots {

	private Snapshots() {
	}

	/**
	 * Project a hosted session's current state into a snapshot.
	 *
	 * @param sessionId the session id
	 * @param seed      the session seed
	 * @param scenario  the founding scenario id
	 * @param state     the host's control state (RUNNING / PAUSED / STOPPED)
	 * @param tick      the authoritative tick (in-game days elapsed)
	 * @param colonies  the session's colonies
	 * @param map       the world map (for caravan province names), or {@code null}
	 * @param caravans  the session's wandering bands
	 * @param log       the event-log lines since the previous frame (the live log bar's feed)
	 * @return the render snapshot
	 */
	public static SessionSnapshot of(String sessionId, long seed, String scenario,
			String state, long tick, List<Settlement> colonies, WorldMap map,
			List<Caravan> caravans, List<LogLine> log) {
		List<ColonyView> colonyViews = new ArrayList<>(colonies.size());
		LocalDate date = null;
		for (Settlement c : colonies) {
			// the session's date is the furthest-advanced colony's (a dead one froze early)
			if (date == null || c.getDate().isAfter(date))
				date = c.getDate();
			colonyViews.add(colonyView(c));
		}
		List<CaravanView> caravanViews = new ArrayList<>(caravans.size());
		for (Caravan band : caravans)
			caravanViews.add(caravanView(band, map));
		return new SessionSnapshot(sessionId, seed, scenario, state, tick,
				date == null ? "" : date.toString(), colonyViews, caravanViews, log);
	}

	// project one colony's aggregates — the same tally the annual digest and CSV printers
	// take (see Settlement.logAnnualDigest), read between ticks so the population is settled
	private static ColonyView colonyView(Settlement c) {
		LocalDate date = c.getDate();
		int population = 0, children = 0, nobles = 0, firms = 0, pool = 0;
		for (Agent a : c.getAgents()) {
			if (!a.isAlive())
				continue;
			if (a instanceof Retinue r) {
				pool += r.size();
				children += r.childCount(date);
			} else if (a instanceof Firm) {
				firms++;
			} else if (a instanceof Household h) {
				if (h.isWorkforce()) {
					population++;
					for (Member m : h.getMembers())
						if (!m.isAdult(date))
							children++;
				} else if ("Noble".equals(h.role())) {
					nobles++;
				}
			}
		}
		double necessity = marketPrice(c, "Necessity");
		double enjoyment = marketPrice(c, "Enjoyment");
		// the ruler's current tax levers (0 when the colony has no ruler) — the read-side of
		// the Phase-B tax command, so a client sees policy changes land
		com.civstudio.agent.ruler.Ruler ruler = c.getRuler();
		double bankProfitTax = ruler == null ? 0 : ruler.getBankProfitTaxRate();
		double nobleIncomeTax = ruler == null ? 0 : ruler.getNobleIncomeTaxRate();
		return new ColonyView(c.getName(), c.isAlive(), date.toString(), population,
				children, nobles, firms, pool, c.getInflation(), necessity, enjoyment,
				c.getPlotCount(), c.getMaxPlots(), c.getLatitude(), c.getLongitude(),
				bankProfitTax, nobleIncomeTax, advisorViews(c));
	}

	// project the colony's privy council: the court member seated in each filled advisor
	// role (see AdvisorRoster). Unfilled roles are omitted — the frontend greys them.
	private static List<AdvisorView> advisorViews(Settlement c) {
		List<AdvisorView> views = new ArrayList<>();
		for (AdvisorRole role : AdvisorRole.values()) {
			Household seat = c.getAdvisorRoster().holder(role);
			if (seat == null)
				continue;
			Member head = seat.getHead();
			views.add(new AdvisorView(role.id(), seat.getID(), head.fullName(),
					head.race().id(), head.gender().name().toLowerCase(Locale.ROOT)));
		}
		return views;
	}

	// the last clearing price of a colony's consumer-good market by good name (0 if absent)
	private static double marketPrice(Settlement c, String good) {
		for (ConsumerGoodMarket m : c.getConsumerGoodMarkets())
			if (good.equals(m.getGood()))
				return m.getLastMktPrice();
		return 0;
	}

	// project one band's position + vitals; the province name needs the world map (the
	// band carries only an id), so a null map degrades the label to the raw id
	private static CaravanView caravanView(Caravan band, WorldMap map) {
		Member leader = band.getLeader();
		String leaderName = leader == null ? "?" : leader.fullName();
		boolean onGraph = band.onGraph();
		String province;
		if (!onGraph)
			province = "(off graph)";
		else if (map != null)
			province = map.province(band.getProvinceId()).name();
		else
			province = "#" + band.getProvinceId();
		int bandSize = 0;
		double larder = 0;
		boolean settled = false;
		if (band instanceof MarchingCaravan m) {
			Retinue following = m.getFollowing();
			bandSize = following.size();
			larder = following.getLarder();
			settled = m.hasArrived();
		}
		return new CaravanView(leaderName, leaderName, band.getLatitude(),
				band.getLongitude(), onGraph ? band.getProvinceId() : -1, province, onGraph,
				settled, bandSize, larder, band.getHoard(), band.role().name());
	}
}
