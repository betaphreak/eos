package com.civstudio.server.render;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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
import com.civstudio.server.web.UnitBundle;
import com.civstudio.settlement.Plot;
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
	 * @param sessionId  the session id
	 * @param seed       the session seed
	 * @param scenario   the founding scenario id
	 * @param clockState what the clock is doing (CREATED / RUNNING / PAUSED / STOPPED)
	 * @param outcome    the contest result (LIVE / WON / LOST / ABANDONED) — see {@code
	 *                   docs/session-management.md}
	 * @param endReason  why the run ended (display text), or {@code null} unless it ended itself — see
	 *                   {@code docs/game-over.md}
	 * @param tick       the authoritative tick (in-game days elapsed)
	 * @param date      the session's in-game date — its own clock ({@code HostedSession.date()}),
	 *                  not a poll of the colonies: a session whose colonies are all dead still
	 *                  knows what day it is. May be {@code null} for a caller with no clock, which
	 *                  reports an empty date exactly as an unknown one always did
	 * @param colonies  the session's colonies
	 * @param map       the world map (for caravan province names), or {@code null}
	 * @param caravans  the session's wandering bands
	 * @param log       the event-log lines since the previous frame (the live log bar's feed)
	 * @return the render snapshot
	 */
	public static SessionSnapshot of(String sessionId, long seed, String scenario,
			String clockState, String outcome, String endReason, long tick, LocalDate date,
			List<Settlement> colonies, WorldMap map, List<Caravan> caravans, List<LogLine> log) {
		List<ColonyView> colonyViews = new ArrayList<>(colonies.size());
		for (Settlement c : colonies)
			colonyViews.add(colonyView(c));
		List<CaravanView> caravanViews = new ArrayList<>(caravans.size());
		// routed plots the bands have pioneered (deduped by position) — the live per-plot route data
		// the web draw layer stamps (gap B, docs/route-rendering.md)
		Map<Long, RoutePlotView> routed = new LinkedHashMap<>();
		// the session's wandering bands (migration/dissolution), then each colony's own
		// outstanding explorer levies — colony-owned excursions, ticked by their home colony
		// (docs/explorer-caravan.md), so they are not in the session's caravan list
		for (Caravan band : caravans) {
			caravanViews.add(caravanView(band, map));
			collectRoutePlots(band, routed);
		}
		for (Settlement c : colonies)
			for (Caravan excursion : c.getExcursions()) {
				caravanViews.add(caravanView(excursion, map));
				collectRoutePlots(excursion, routed);
			}
		return new SessionSnapshot(sessionId, seed, scenario, clockState, outcome, endReason, tick,
				date == null ? "" : date.toString(), colonyViews, caravanViews, log,
				new ArrayList<>(routed.values()));
	}

	// collect a band's trailed plots into `out`, keyed by packed (x,y) so a plot crossed by more than
	// one band (or twice) is sent once. Only marching bands pioneer trails; a plot whose route was
	// cleared or that has no raster position is skipped.
	private static void collectRoutePlots(Caravan band, Map<Long, RoutePlotView> out) {
		if (!(band instanceof MarchingCaravan m))
			return;
		for (com.civstudio.settlement.Plot p : m.trailedPlots()) {
			com.civstudio.geo.RouteType rt = p.routeType();
			if (rt == null || p.x() < 0 || p.y() < 0)
				continue;
			out.put((((long) p.x()) << 32) | (p.y() & 0xffffffffL),
					new RoutePlotView(p.x(), p.y(), rt.type()));
		}
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
		// the colony's known techs (pre-known baseline + researched) — drives the web tech tree's
		// unlocked styling; sorted for a stable projection, empty when research is disabled
		var research = c.getResearch();
		List<String> knownTechs = research == null ? List.of()
				: research.getKnown().stream().sorted().toList();
		// the tech currently being researched + fractional progress (the read-side of the ruler's
		// monthly research pick) — the web shows it live on the Technology segment
		String researchingTech = null;
		double researchProgress = 0;
		if (research != null && research.getFocus() != null) {
			researchingTech = research.getFocus().type();
			double cost = research.effectiveCost();
			researchProgress = cost > 0 ? Math.min(1.0, research.getProgress() / cost) : 0;
		}
		// the district state the web view stamps: the starting district count (province
		// development), the culture (→ art-style set), and the plots carrying buildings
		// (sparse — auto-build puts everything at the center in the first cut). See
		// docs/district-buildout.md Phase D3.
		String culture = c.getProvince() == null ? null : c.getProvince().culture();
		List<DistrictView> districts = districtViews(c);
		// the province the colony sits in. The view already ships lat/lon, but a client cannot turn
		// those back into a province without inverting the map projection — so a rail that wants to
		// show "this province holds the live colony" needs the id said outright.
		int provinceId = c.getProvince() == null ? 0 : c.getProvince().id();
		// the city-center plot — the water-first plot the colony founded on. The lat/lon above are
		// the PROVINCE's anchor, which can sit a plot or two off the real centre, so the district
		// view needs the centre said outright rather than inferred from them.
		Plot center = c.getCityCenter();
		Integer centerX = center == null ? null : center.x();
		Integer centerY = center == null ? null : center.y();
		return new ColonyView(c.getName(), c.isAlive(), date.toString(), population,
				children, nobles, firms, pool, c.getInflation(), necessity, enjoyment,
				c.getPlotCount(), c.getMaxPlots(), c.getLatitude(), c.getLongitude(),
				bankProfitTax, nobleIncomeTax, advisorViews(c), knownTechs,
				c.getStartingDistrictCount(), culture, districts, researchingTech, researchProgress,
				c.getTier() == null ? null : c.getTier().name(), provinceId, centerX, centerY);
	}

	// project the colony's district plots that carry buildings — the placed-building state
	// the web district view stamps (bare ids; the client joins /api/buildings). Only
	// non-empty plots are sent (sparse); the plot's index is its position in the district
	// map (0 = village center). See docs/district-buildout.md Phase D3.
	private static List<DistrictView> districtViews(Settlement c) {
		List<com.civstudio.settlement.Plot> plots = c.getDistrictPlots();
		List<DistrictView> views = new ArrayList<>();
		for (int i = 0; i < plots.size(); i++) {
			List<com.civstudio.settlement.Building> buildings = plots.get(i).buildings();
			if (buildings.isEmpty())
				continue;
			views.add(new DistrictView(i,
					buildings.stream().map(com.civstudio.settlement.Building::id).toList()));
		}
		return views;
	}

	// project the colony's privy council: the court member seated in each filled advisor
	// role (see AdvisorRoster). Unfilled roles are omitted — the frontend greys them.
	private static List<AdvisorView> advisorViews(Settlement c) {
		// the colony's Anbennar culture (from its founding province) — the finer portrait selector;
		// null for a colony founded at bare coordinates (the analytical sims have no province)
		String culture = c.getProvince() == null ? null : c.getProvince().culture();
		List<AdvisorView> views = new ArrayList<>();
		for (AdvisorRole role : AdvisorRole.values()) {
			Household seat = c.getAdvisorRoster().holder(role);
			if (seat == null)
				continue;
			Member head = seat.getHead();
			views.add(new AdvisorView(role.id(), seat.getID(), head.fullName(),
					head.race().id(), head.gender().name().toLowerCase(Locale.ROOT), culture));
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
		String unitId = null, unitName = null, signatureSkill = null;
		int[] unitIcon = null;
		int leaderSkill = 0;
		if (band instanceof MarchingCaravan m) {
			// the march reads only the MarchFollowing slice (size + larder); a Retinue and a
			// DraftBand both satisfy it (docs/explorer-caravan.md)
			var following = m.getFollowing();
			bandSize = following.size();
			larder = following.getLarder();
			settled = m.hasArrived();
			// the role signature skill + leader level read out for any marching band; the embodied
			// unit's identity/art (docs/c2c-unit-import.md §1a) only when the colony fielded one
			signatureSkill = m.signatureSkill().name();
			leaderSkill = m.leaderSkillLevel();
			unitId = m.getUnitId();
			if (unitId != null) {
				unitName = m.getUnitName();
				unitIcon = UnitBundle.iconRect(unitId);
			}
		}
		return new CaravanView(leaderName, leaderName, band.getLatitude(),
				band.getLongitude(), onGraph ? band.getProvinceId() : -1, province, onGraph,
				settled, bandSize, larder, band.getHoard(), band.role().name(),
				unitId, unitName, unitIcon, signatureSkill, leaderSkill);
	}
}
