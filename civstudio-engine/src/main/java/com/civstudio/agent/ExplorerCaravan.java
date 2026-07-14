package com.civstudio.agent;

import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;

import com.civstudio.geo.WorldMap;
import com.civstudio.settlement.GameSession;
import com.civstudio.settlement.Settlement;
import com.civstudio.util.Rng;

/**
 * An <b>explorer</b> band: a foraging <b>levy</b> a colony musters under food pressure, which
 * marches out to gather provisions and returns to feed the settlement — the C2C
 * {@code UNITAI_EXPLORE} flavor, repurposed as the food-import expedition of
 * {@code docs/explorer-caravan.md}.
 * <p>
 * Unlike the settler band, an explorer does not own its people: its {@link DraftBand following}
 * is a <b>drafted levy</b> whose members stay accounted in their home households/pool (flagged
 * {@link Member#isDrafted() drafted}, so the colony neither works nor feeds them) and are only
 * referenced. The caravan feeds them from its carried larder, foraging as it marches — and
 * because its whole purpose is to bring food home, it forages at a <b>net-positive</b> cap
 * ({@link #forageCapFraction()} &gt; 1), unlike a decaying wandering band.
 * <p>
 * <b>The round trip.</b> The band {@link Phase#OUTBOUND marches out}, foraging; when its haul is
 * worth carrying — a full larder, or a time/low-larder cap — it {@link Phase#RETURNING turns
 * home}, and on arrival <b>deposits its surplus food into the colony's granary</b> (the strategic
 * store that feeds the starving pool and releases into the necessity market in scarcity) and
 * <b>undrafts</b> its people, who return to work, market and marriage. The paid cash-out to the
 * draftees' households and the re-entry into the wedding market (decisions 14, 19) are a later
 * cut; Phase 2 lands the food loop.
 */
public final class ExplorerCaravan extends MarchingCaravan {

	// the expedition turns home once its larder holds this many necessity units per head — a
	// haul worth carrying back (a placeholder, calibrated in the trigger phase)
	private static final double HAUL_TARGET_PER_HEAD = 30.0;

	// ...or after this many days out (so a band that never finds food still comes home)...
	private static final int MAX_DAYS_OUT = 120;

	// ...or once the larder falls below this many days of rations (turn home before starving)
	private static final int MIN_LARDER_DAYS = 20;

	// an explorer forages to NET-GROW its larder (bring food home), so its cap is above 1 —
	// unlike a wandering band, whose sub-1 cap only slows its larder's decline
	private static final double EXPLORER_FORAGE_CAP = 3.0;

	/** Where the band is in its round trip. */
	private enum Phase {
		/** marching out, foraging */
		OUTBOUND,
		/** heading back to the home settlement, its haul aboard */
		RETURNING,
		/** home: food deposited, people undrafted — the expedition is over */
		DONE
	}

	// the settlement that mustered the band and it returns to
	private final Settlement home;
	private final int homeProvinceId;
	// the band's following, narrowed to the draft band for undrafting / draining on return
	private final DraftBand draftBand;

	// round-trip limits (defaults from the constants above; tunable for calibration/tests via
	// setTripLimits)
	private double haulTargetPerHead = HAUL_TARGET_PER_HEAD;
	private int maxDaysOut = MAX_DAYS_OUT;
	private int minLarderDays = MIN_LARDER_DAYS;

	// the date the levy should start heading home, so it arrives by mid-autumn — set at muster
	// from the home colony's season (docs/explorer-caravan.md): a band mustered in winter forages
	// through spring and summer and turns home in late summer to be back before the cold, when it
	// rejoins the settlement and works until it can go out again the next winter. Null = no
	// seasonal deadline (the caravan then turns home on haul / days-out / low-larder only).
	private LocalDate returnStartDate;

	private Phase phase = Phase.OUTBOUND;
	private int daysOut;

	private ExplorerCaravan(Member leader, DraftBand band, Settlement home, GameSession session) {
		super(leader, band, 0, home.getProvince().id(), session);
		this.home = home;
		this.homeProvinceId = home.getProvince().id();
		this.draftBand = band;
		// the explorer forages for its haul, so it pitches a camp each night (which enables
		// the forage/gather window) as it marches
		setCampingEnabled(true);
	}

	/**
	 * <b>Muster</b> an explorer levy from a home colony's people: flag each draftee, take the
	 * ablest as the band's leader, and set out with the given provisions. The {@code draftees}
	 * must be living working-age members already selected by the caller (the trigger — a mix of
	 * pool peasants and unmarried household adults; see {@code docs/explorer-caravan.md}); this
	 * only forms the band around them.
	 *
	 * @param home     the mustering colony (province-founded), which the band returns to
	 * @param draftees the people to draft (each is flagged {@link Member#setDrafted drafted})
	 * @param larder   the provisions loaded at muster (necessity units)
	 * @return the mustered explorer band, at the home province, marching out
	 */
	public static ExplorerCaravan muster(Settlement home, List<Member> draftees, double larder) {
		if (draftees.isEmpty())
			throw new IllegalArgumentException("an explorer levy needs at least one draftee");
		if (home.getProvince() == null || home.getSession() == null)
			throw new IllegalStateException(
					"an explorer musters from a province-founded colony in a session");
		Member leader = draftees.get(0);
		for (Member m : draftees) {
			m.setDrafted(true);
			if (m.skills().overallLevel() > leader.skills().overallLevel())
				leader = m; // the ablest leads
		}
		DraftBand band = new DraftBand(draftees, larder);
		return new ExplorerCaravan(leader, band, home, home.getSession());
	}

	@Override
	public CaravanRole role() {
		return CaravanRole.EXPLORER;
	}

	// an explorer forages to bring food home, so it net-grows its larder on food-rich ground
	@Override
	protected double forageCapFraction() {
		return EXPLORER_FORAGE_CAP;
	}

	@Override
	protected boolean journeyComplete() {
		return phase == Phase.DONE;
	}

	@Override
	protected boolean arrive(LocalDate date, Rng rng) {
		switch (phase) {
			case OUTBOUND -> {
				daysOut++;
				if (shouldTurnHome(date)) {
					setDestination(homeProvinceId); // march the shortest route back
					phase = Phase.RETURNING;
				}
				return false; // either way the band marches today (out, or the first leg home)
			}
			case RETURNING -> {
				if (atDestination()) { // back at the home province
					returnHome();
					phase = Phase.DONE;
					return true; // the expedition is over — no march
				}
				return false;
			}
			default -> {
				return true;
			}
		}
	}

	// turn home when: the season says so (arrive by mid-autumn — the primary, seasonal rule), the
	// haul is a full load, the time cap is hit, or the larder is low enough that the band must
	// head back before it starves
	private boolean shouldTurnHome(LocalDate date) {
		int n = draftBand.size();
		if (n == 0)
			return true;
		// seasonal deadline: start heading home so the band is back by mid-autumn
		if (returnStartDate != null && !date.isBefore(returnStartDate))
			return true;
		double larder = draftBand.getLarder();
		double haulTarget = n * haulTargetPerHead;
		double lowLarder = n * WANDERING_RATION.perDay() * minLarderDays;
		return larder >= haulTarget || daysOut >= maxDaysOut || larder <= lowLarder;
	}

	/**
	 * Set the date the levy should start heading home (so it arrives by mid-autumn) — the
	 * seasonal return deadline, set at muster from the home colony's season (see {@code
	 * docs/explorer-caravan.md}). Overrides the time/haul caps as the primary turn-home rule.
	 *
	 * @param returnStartDate the date to begin the march home
	 */
	public void setReturnBy(LocalDate returnStartDate) {
		this.returnStartDate = returnStartDate;
	}

	/**
	 * Tune the round-trip limits — when the band turns home: a per-head <b>haul target</b>
	 * (larder full), a <b>time cap</b> (days out), and a <b>low-larder</b> floor (days of food
	 * left). Placeholders pending the trigger-phase calibration; also lets a test force a short
	 * trip.
	 *
	 * @param haulTargetPerHead necessity per head that counts as a full haul
	 * @param maxDaysOut        the most days the band stays out before heading home
	 * @param minLarderDays     turn home once fewer than this many days of food remain
	 */
	public void setTripLimits(double haulTargetPerHead, int maxDaysOut, int minLarderDays) {
		this.haulTargetPerHead = haulTargetPerHead;
		this.maxDaysOut = maxDaysOut;
		this.minLarderDays = minLarderDays;
	}

	// home at last: deposit the foraged surplus into the colony's granary (which feeds the
	// starving pool and releases into the necessity market in scarcity) and undraft the levy,
	// so its people return to work, market and marriage
	private void returnHome() {
		double surplus = draftBand.drainLarder();
		if (home.getGranary() != null)
			home.getGranary().importStock(surplus);
		for (Member m : draftBand.draftees())
			m.setDrafted(false);
		releaseCamp();
	}

	// OUTBOUND target: wander toward the nearest land province that is not where the band set
	// out from, so it heads out into the country to forage (opportunistic — it forages what its
	// route crosses; docs/explorer-caravan.md). Directed travel home (RETURNING) does not use this.
	@Override
	protected OptionalInt chooseWanderTarget(Rng rng) {
		WorldMap map = worldMap();
		Set<Integer> visited = new HashSet<>();
		visited.add(getProvinceId());
		Deque<Integer> frontier = new ArrayDeque<>();
		frontier.add(getProvinceId());
		while (!frontier.isEmpty()) {
			List<Integer> nextLayer = new ArrayList<>();
			List<Integer> candidates = new ArrayList<>();
			while (!frontier.isEmpty()) {
				int cur = frontier.poll();
				for (int nb : map.neighbors(cur)) {
					if (!visited.add(nb) || !map.province(nb).isLand())
						continue;
					nextLayer.add(nb);
					if (nb != originProvinceId && nb != getProvinceId())
						candidates.add(nb);
				}
			}
			if (!candidates.isEmpty())
				return OptionalInt.of(candidates.get(rng.uniform(candidates.size())));
			frontier.addAll(nextLayer);
		}
		return OptionalInt.empty();
	}
}
