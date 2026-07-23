package com.civstudio.settlement;

import java.util.ArrayList;
import java.util.List;

import com.civstudio.agent.Household;
import com.civstudio.agent.Member;
import com.civstudio.agent.laborer.Laborer;
import com.civstudio.bank.Bank;
import com.civstudio.calendar.DayType;
import com.civstudio.market.LaborMarket;
import com.civstudio.skill.Skill;

/**
 * The <b>build economy</b> of a {@link Settlement} (docs/build-queue-plan.md B1) — the
 * hammer/commerce side of the plot-working economy, sibling of {@link FoodEconomy}:
 * a landed laborer household that <b>stays home</b> for the day works its shared home
 * plot's {@code production} and {@code commerce} yields ({@link Plot#yields()} indices
 * 1–2) instead of selling labor at the center. Present on the colony only when
 * {@code SimulationConfig.buildEconomy} is on (a flag-off colony has {@code null} here
 * and is byte-identical).
 * <p>
 * The flows, per plot-day household:
 * <ul>
 * <li><b>Hammers</b> — {@code production × dayFactor ÷ load}, scaled by the household's
 * {@link Skill#CONSTRUCTION} proficiency. With no build project of its own (housing is
 * B3) the whole yield is <b>donated</b> to the colony's hammer sink — the ruler's future
 * main queue (B4); unqueued donations are use-it-or-lose-it, so the sink only counts
 * them.</li>
 * <li><b>Commerce</b> — {@code commerce × dayFactor ÷ load}, scaled by
 * {@link Skill#COMMERCE}, <b>minted at the household's copper bank</b> straight into its
 * account (a bare {@link Bank#credit} with no counterparty — the monetary parallel of
 * plot food; the inflation printer watches the money-supply growth).</li>
 * </ul>
 * {@code dayFactor} carries the <b>full-parity</b> scalers of market labor: the rest-day
 * gate (a non-{@link DayType#WORKDAY} plot day yields nothing — in fact the choice only
 * arises on workdays), the {@link LaborMarket#daylightFactor daylight factor} and the
 * Malthusian {@code ÷ load} split of the shared plot; working the plot trains the two
 * skills exactly as firm labor trains its employer's. The <b>unhired fallback</b>
 * ({@link #applyUnhiredFallback}) runs after the labor market clears: a household that
 * chose the market but was left unhired still works its plot that day, so no day is
 * wasted. The occupation <i>choice</i> itself (reservation wage, optimism prior,
 * hysteresis) lives on {@link Laborer}; this class prices the plot side and executes
 * plot days.
 */
public class BuildEconomy {

	/**
	 * The hysteresis band of the occupation choice: a household switches occupation only
	 * when the plot-vs-wage comparison beats the incumbent by this margin in either
	 * direction (the dynamic-firm-provisioner pattern — no daily flip-flop, no
	 * oscillating market-clearing spiral). UNCALIBRATED; promoted to a config record when
	 * calibration begins.
	 */
	public static final double HYSTERESIS_BAND = 0.25;

	// experience gained per plot-day in each worked skill — parity with the labor
	// market's XP_PER_LABOR (one "labor performed" per day)
	private static final double XP_PER_PLOT_DAY = 1;

	private final Settlement colony;
	private final PlotField plotField;

	// households that chose the market today (posted labor), for the unhired fallback
	private final List<Laborer> marketChoosers = new ArrayList<>();

	// current-print-period counters (reset by the printer) and cumulative totals
	private double periodHammers, periodCommerce;
	private int periodPlotDays, periodMarketDays, periodFallbackDays;
	private double totalHammersDonated, totalCommerceMinted;

	BuildEconomy(Settlement colony, PlotField plotField) {
		this.colony = colony;
		this.plotField = plotField;
	}

	/**
	 * The coin value of a plot day for {@code laborer} — the commerce its household
	 * would mint working its home plot today ({@code commerce × daylight ×
	 * skill ÷ load}). The reservation-wage comparand of the occupation choice
	 * (hammers are deliberately uncounted: they are the donation byproduct, not
	 * household income). {@code 0} on a rest day or for a landless household.
	 *
	 * @param laborer the household weighing the choice
	 * @return the plot day's expected commerce coin
	 */
	public double plotCommerceValue(Laborer laborer) {
		Plot plot = laborer.getHomePlot();
		if (plot == null || colony.getDayType() != DayType.WORKDAY)
			return 0;
		return yieldShare(laborer, plot, 2, Skill.COMMERCE);
	}

	/**
	 * Execute a <b>plot day</b> for {@code laborer}: mint its commerce share into its
	 * account, donate its hammer share to the colony sink (no household projects until
	 * B3), and train the working adults in {@link Skill#CONSTRUCTION} and
	 * {@link Skill#COMMERCE}. A no-op on a rest day (parity: firms don't operate,
	 * plots aren't worked) or for a landless household.
	 *
	 * @param laborer the household working its plot today
	 */
	public void workPlotDay(Laborer laborer) {
		Plot plot = laborer.getHomePlot();
		if (plot == null || colony.getDayType() != DayType.WORKDAY)
			return;
		double hammers = yieldShare(laborer, plot, 1, Skill.CONSTRUCTION);
		double commerce = yieldShare(laborer, plot, 2, Skill.COMMERCE);
		// hammers pay the household's housing project first (B3); the leftover — overflow,
		// or everything once currently housed — donates to the colony sink
		double leftover = laborer.applyHammersToProject(hammers, this);
		totalHammersDonated += leftover;
		spendDonation(leftover); // the ruler's center queue (B4)
		periodHammers += hammers;
		// mint: commerce coin appears in the household's account with no counterparty.
		// Guarded on the account existing — a household whose account is closed (mid-step
		// death, an estate already settled) must not NPE the bank (default-flip finding)
		if (commerce > 0 && laborer.getBank().getAcct(laborer.getID()) != null)
			laborer.getBank().credit(laborer.getID(), commerce, Bank.SECIC);
		totalCommerceMinted += commerce;
		periodCommerce += commerce;
		periodPlotDays++;
		// working the plot trains the two skills, exactly as firm labor trains its
		// employer's (every working adult learns; drafted members are away)
		for (Member m : laborer.getMembers())
			if (m.isAdult(colony.getDate()) && !m.isDrafted()) {
				m.skills().learn(Skill.CONSTRUCTION, XP_PER_PLOT_DAY);
				m.skills().learn(Skill.COMMERCE, XP_PER_PLOT_DAY);
			}
	}

	/**
	 * Register a household that chose the <b>market</b> today (posted its labor offer),
	 * so the unhired fallback can catch it after clearing.
	 *
	 * @param laborer the household that went to market
	 */
	public void registerMarketChooser(Laborer laborer) {
		marketChoosers.add(laborer);
		periodMarketDays++;
	}

	/**
	 * The <b>unhired fallback</b> (docs/build-queue-plan.md B1): called by
	 * {@link Settlement#newDay} right after the markets clear — every household that
	 * offered labor today but was left unhired by the wage-budget allocation works its
	 * plot instead, so a rejected market day still yields plot output. (Its wage memory
	 * reads 0 next {@code act()}, pushing it toward the plot — the labor-surplus
	 * signal.)
	 *
	 * @param labor the colony's labor market (the allocation of record)
	 */
	void applyUnhiredFallback(LaborMarket labor) {
		// only on workdays: a rest-day plot day yields nothing (workPlotDay no-ops), and
		// counting it as a fallback made the disjoint plot-day counter go NEGATIVE
		// (calibration finding 2026-07-23 — a display bug, not a flow bug)
		if (colony.getDayType() == DayType.WORKDAY)
			for (Laborer l : marketChoosers)
				if (l.isAlive() && !labor.wasHiredLastClear(l.getID())) {
					workPlotDay(l);
					periodFallbackDays++;
					// workPlotDay counted it as a plot day too; keep the two disjoint
					periodPlotDays--;
				}
		marketChoosers.clear();
		enqueueEliteCommissions();
	}

	// B3b: an unhoused Noble/Ruler commissions the BuilderFirm — a building-legged
	// BuildProject at the center, sponsor-billed at cost (the ruler's is a coin wash,
	// a noble's a real noble→crown transfer; the scaffold cap is the price). Target:
	// the BEST available rung (a manor over a hut). One pending commission per
	// household (deduped by scanning the live queue — no extra state to desync).
	private void enqueueEliteCommissions() {
		var plots = colony.getDistrictPlots();
		if (plots.isEmpty())
			return;
		Plot center = plots.get(0);
		for (com.civstudio.agent.Agent a : colony.getAgents()) {
			if (!(a instanceof com.civstudio.agent.noble.Noble
					|| a instanceof com.civstudio.agent.ruler.Ruler))
				continue;
			var elite = (com.civstudio.agent.AbstractHousehold) a;
			if (!elite.isAlive() || elite.housedForGate() || hasPendingCommission(elite))
				continue;
			HousingBuilding rung = HousingCatalog.get().bestAvailable(knownTechs());
			if (rung == null)
				continue;
			plotField.queueCommission(new com.civstudio.settlement.BuildProject(
					center, rung.type(), rung.effectiveCost(), elite));
			eliteCommissions++;
		}
	}

	private boolean hasPendingCommission(com.civstudio.agent.AbstractHousehold owner) {
		for (BuildProject p : plotField.activeProjects())
			if (p.isBuildingCommission() && p.getBuildingOwner() == owner)
				return true;
		return false;
	}

	// cumulative elite commissions enqueued (instrumentation)
	private int eliteCommissions;

	/** Cumulative elite housing commissions enqueued since founding (B3b). */
	public int getEliteCommissions() {
		return eliteCommissions;
	}

	// --- the ruler's center build queue (B4, docs/build-queue-plan.md) ----------------

	/**
	 * Maps a building's catalog cost ({@code iCost}-scale) to donated hammers required.
	 * UNCALIBRATED (the hammer printer instruments the pace).
	 */
	public static final double BUILD_COST_SCALE = 1.0;

	// the active item: catalog id + hammers still owed; null id = queue idle (the brain
	// picks on the next donation). Donated hammers with no active item evaporate
	// (Civ4 use-it-or-lose-it — rarely binds, the brain refills immediately).
	private String activeBuildId;
	private double activeRemaining;
	private int rulerQueued, rulerCompleted; // instrumentation

	// spend a donation on the ruler's queue: pick a target if idle (the brain), then pay
	// the active item; completion raises the ruler-owned building at the center, and the
	// overflow carries into the next pick. Called wherever hammers are donated.
	private void spendDonation(double hammers) {
		while (hammers > 0) {
			if (activeBuildId == null && !pickNextBuilding())
				return; // nothing buildable — the donation evaporates
			double paid = Math.min(hammers, activeRemaining);
			activeRemaining -= paid;
			hammers -= paid;
			if (activeRemaining > 1e-9)
				return;
			completeActive();
		}
	}

	// the B4 brain (the unattended-colony strategy; a player-fed queue arrives in B6):
	// highest score among regular, buildable, non-autoBuild, prereq-known, non-obsolete
	// buildings the center doesn't already have (per-PLOT uniqueness). Score follows the
	// C2C DLL shape: (1 + flavor sum, the flat-personality dot product) time-discounted
	// by 100/(cost+3) — the discount that stops early expensive-stacking. Deterministic
	// (id tie-break), no RNG.
	// player-ordered building ids (B6): consumed ahead of the heuristic, FIFO; fed by
	// the queue_build command on the CommandLog (tick-stamped, so replay reproduces the
	// same picks). Invalid/duplicate/no-longer-buildable ids are dropped at pick time.
	private final List<String> playerOrders = new ArrayList<>();

	/**
	 * Append player-chosen building ids to the ruler's queue (the B6 {@code queue_build}
	 * verb) — consumed ahead of the heuristic, validated at pick time.
	 */
	public void submitBuildOrders(List<String> ids) {
		playerOrders.addAll(ids);
	}

	/** Clear all pending player orders (the cancel verb). */
	public void clearBuildOrders() {
		playerOrders.clear();
	}

	// whether the heuristic brain may auto-pick when no player order is pending (B6):
	// true for unattended colonies (the default); a player-SEATED session sets false so
	// the queue can genuinely run empty — which is what raises the pause-and-choose
	// interrupt server-side. While paused no ticks run, so nothing evaporates.
	private boolean heuristicEnabled = true;

	/** Enable/disable the heuristic auto-pick (the seated-session interrupt seam, B6). */
	public void setHeuristicEnabled(boolean enabled) {
		this.heuristicEnabled = enabled;
	}

	/**
	 * Whether the center queue is <b>awaiting a player choice</b> (the B6 interrupt
	 * predicate): idle with no pending orders <b>and</b> the heuristic off (a seated
	 * session) <b>and</b> there is actually something to build — an unattended colony's
	 * idle-between-donations never counts, and a colony with nothing buildable idles
	 * rather than freezing the session behind an empty modal (it would present no choice).
	 */
	public boolean queueAwaitingChoice() {
		return !heuristicEnabled && activeBuildId == null && playerOrders.isEmpty()
				&& hasCenterCandidate();
	}

	// whether any regular building could start at the center today — the cheap short-circuit
	// behind queueAwaitingChoice (avoids buildableCandidates()'s allocate-and-sort per tick).
	private boolean hasCenterCandidate() {
		var plots = colony.getDistrictPlots();
		if (plots.isEmpty())
			return false;
		Plot center = plots.get(0);
		var known = knownTechs();
		for (BuildingInfo b : BuildingCatalog.get().all())
			if (centerCandidate(b, center, known))
				return true;
		return false;
	}

	/** Pending player-ordered ids (read-only view, for the snapshot/rail). */
	public List<String> getPendingOrders() {
		return java.util.Collections.unmodifiableList(playerOrders);
	}

	/** The active center-queue item id, or {@code null} while idle (snapshot). */
	public String getActiveBuildId() {
		return activeBuildId;
	}

	/** Hammers still owed on the active item (snapshot). */
	public double getActiveRemaining() {
		return activeRemaining;
	}

	/**
	 * The buildable candidates for the what-to-build-next modal (B6): every regular the
	 * center could start today, sorted by the brain's score descending (id tie-break).
	 */
	public List<BuildingInfo> buildableCandidates() {
		var plots = colony.getDistrictPlots();
		if (plots.isEmpty())
			return List.of();
		Plot center = plots.get(0);
		var known = knownTechs();
		List<BuildingInfo> out = new ArrayList<>();
		for (BuildingInfo b : BuildingCatalog.get().all())
			if (centerCandidate(b, center, known))
				out.add(b);
		out.sort((x, y) -> {
			int c = Double.compare(score(y), score(x));
			return c != 0 ? c : x.id().compareTo(y.id());
		});
		return out;
	}

	// the shared center-queue candidate filter (the brain + the modal list)
	private boolean centerCandidate(BuildingInfo b, Plot center, java.util.Set<String> known) {
		return b.buildable() && !Boolean.TRUE.equals(b.autoBuild()) && b.kind() == null
				&& b.prereqTech() != null && known.contains(b.prereqTech())
				&& (b.obsoleteTech() == null || !known.contains(b.obsoleteTech()))
				&& !center.hasBuilding(b.id());
	}

	private static double score(BuildingInfo b) {
		return (1 + b.flavorSum()) * 100.0 / (b.effectiveCost() + 3);
	}

	private boolean pickNextBuilding() {
		var plots = colony.getDistrictPlots();
		if (plots.isEmpty())
			return false;
		Plot center = plots.get(0);
		var known = knownTechs();
		// player orders first (B6): FIFO, dropping ids that no longer qualify
		while (!playerOrders.isEmpty()) {
			String id = playerOrders.remove(0);
			BuildingInfo b = BuildingCatalog.get().byId(id);
			if (b != null && centerCandidate(b, center, known)) {
				activeBuildId = b.id();
				activeRemaining = b.effectiveCost() * BUILD_COST_SCALE;
				rulerQueued++;
				com.civstudio.io.SimLog.event(com.civstudio.agent.Rank.VILLAGE,
						java.util.logging.Level.FINE, "the crown began building "
								+ (b.name() != null ? b.name() : b.id())
								+ " at the center (by decree)");
				return true;
			}
		}
		if (!heuristicEnabled)
			return false; // seated session: the empty queue raises the B6 interrupt instead
		BuildingInfo best = null;
		double bestScore = 0;
		for (BuildingInfo b : BuildingCatalog.get().all()) {
			if (!b.buildable() || Boolean.TRUE.equals(b.autoBuild()) || b.kind() != null)
				continue;
			if (b.prereqTech() == null || !known.contains(b.prereqTech()))
				continue;
			if (b.obsoleteTech() != null && known.contains(b.obsoleteTech()))
				continue;
			if (center.hasBuilding(b.id()))
				continue;
			double score = (1 + b.flavorSum()) * 100.0 / (b.effectiveCost() + 3);
			if (best == null || score > bestScore
					|| (score == bestScore && b.id().compareTo(best.id()) < 0)) {
				best = b;
				bestScore = score;
			}
		}
		if (best == null)
			return false;
		activeBuildId = best.id();
		activeRemaining = best.effectiveCost() * BUILD_COST_SCALE;
		rulerQueued++;
		com.civstudio.io.SimLog.event(com.civstudio.agent.Rank.VILLAGE,
				java.util.logging.Level.FINE, "the crown began building "
						+ (best.name() != null ? best.name() : best.id())
						+ " at the center");
		return true;
	}

	private void completeActive() {
		var plots = colony.getDistrictPlots();
		var ruler = colony.getRuler();
		if (!plots.isEmpty())
			plots.get(0).addBuilding(new Building(activeBuildId,
					ruler != null && ruler.isAlive() ? ruler.getID() : null));
		rulerCompleted++;
		com.civstudio.io.SimLog.event(com.civstudio.agent.Rank.VILLAGE,
				java.util.logging.Level.INFO,
				"the crown completed " + activeBuildId + " at the center");
		activeBuildId = null;
		activeRemaining = 0;
	}

	/**
	 * The <b>household building brain</b> (B5): the best regular building for a housed
	 * household's own plot — same scoring as the ruler's brain, on the home plot, under
	 * per-plot uniqueness and the <b>2-regulars-per-owner-per-plot</b> limit. The limit
	 * counts only deliberate, costed regular constructions (user ruling 2026-07-23):
	 * housing and the emergent families (autobuild vernacular, costless state/property
	 * buildings) are exempt and stack freely — nobody *chose* to build them.
	 * {@code null} when at the limit or nothing qualifies (hammers then donate).
	 */
	public BuildingInfo pickHouseholdBuilding(Laborer laborer) {
		Plot plot = laborer.getHomePlot();
		if (plot == null)
			return null;
		int ownedRegulars = 0;
		for (Building b : plot.buildings())
			if (b.ownerId() != null && b.ownerId() == laborer.getID() && !b.isHousing())
				ownedRegulars++;
		if (ownedRegulars >= 2)
			return null;
		var known = knownTechs();
		BuildingInfo best = null;
		double bestScore = 0;
		for (BuildingInfo b : BuildingCatalog.get().all()) {
			if (!b.buildable() || Boolean.TRUE.equals(b.autoBuild()) || b.kind() != null)
				continue;
			if (b.prereqTech() == null || !known.contains(b.prereqTech()))
				continue;
			if (b.obsoleteTech() != null && known.contains(b.obsoleteTech()))
				continue;
			if (plot.hasBuilding(b.id()))
				continue;
			double score = (1 + b.flavorSum()) * 100.0 / (b.effectiveCost() + 3);
			if (best == null || score > bestScore
					|| (score == bestScore && b.id().compareTo(best.id()) < 0)) {
				best = b;
				bestScore = score;
			}
		}
		return best;
	}

	// cumulative household regular buildings completed (instrumentation, B5)
	private int householdBuilt;

	public void noteHouseholdBuilt() {
		householdBuilt++;
	}

	/** Completed household-owned regular buildings since founding (B5). */
	public int getHouseholdBuilt() {
		return householdBuilt;
	}

	/** Buildings the ruler's queue has started / completed since founding (B4). */
	public int getRulerQueued() {
		return rulerQueued;
	}

	/** Completed center buildings of the ruler's queue (B4). */
	public int getRulerCompleted() {
		return rulerCompleted;
	}

	/**
	 * The <b>hammer floor</b>: a plot day yields at least this much raw production even
	 * on zero-production ground (grassland) — hut-raising isn't terrain-bound; any land
	 * gives mud and sticks. Without it, households seated on 0-production plots are
	 * PERMANENTLY homeless and the wedding gate collapses the birth rate (measured
	 * calibration 2026-07-23: child-months −82% vs baseline). Commerce has no floor
	 * (trade genuinely needs rivers/roads). UNCALIBRATED beyond that finding.
	 */
	public static final double HAMMER_FLOOR = 0.5;

	// one yield channel's per-household share: the plot's raw yield × the daylight
	// factor × the household's proficiency in the working skill ÷ the plot's Malthusian
	// load (the same equal-split as home-plot food). The skill factor is the household's
	// mean adult level in the skill, through the same productivity curve firm labor uses.
	private double yieldShare(Laborer laborer, Plot plot, int yieldIndex, Skill skill) {
		int load = plotField.homePlotLoad(plot);
		if (load <= 0)
			return 0;
		double raw = Math.max(0, plot.yields()[yieldIndex]);
		if (yieldIndex == 1)
			raw = Math.max(HAMMER_FLOOR, raw);
		return raw * LaborMarket.daylightFactor(colony)
				* Household.productivityOf(meanAdultLevel(laborer, skill)) / load;
	}

	// the household's (rounded) mean adult level in a skill — the plot-working analogue
	// of LaborMarket.relevantLevel; drafted members are away and don't count
	private static int meanAdultLevel(Laborer laborer, Skill skill) {
		int sum = 0, n = 0;
		for (Member m : laborer.getMembers())
			if (m.isAdult(laborer.getColony().getDate()) && !m.isDrafted()) {
				sum += m.skills().level(skill);
				n++;
			}
		return n == 0 ? 0 : Math.round((float) sum / n);
	}

	/**
	 * The colony's known tech ids (for obsolescence checks), never {@code null} — a
	 * pre-research colony knows nothing.
	 */
	public java.util.Set<String> knownTechs() {
		return colony.getResearch() != null ? colony.getResearch().getKnown()
				: java.util.Set.of();
	}

	/**
	 * The cheapest housing rung the colony can build today (the B3 self-build target),
	 * or {@code null} when none is available yet.
	 */
	public HousingBuilding cheapestAvailableHousing() {
		return HousingCatalog.get().cheapestAvailable(knownTechs());
	}

	// --- instrumentation (the B1 hammer printer + the calibration tests) --------------

	/** Cumulative hammers donated to the colony sink since founding. */
	public double getTotalHammersDonated() {
		return totalHammersDonated;
	}

	/** Cumulative commerce coin minted by plot-working since founding. */
	public double getTotalCommerceMinted() {
		return totalCommerceMinted;
	}

	/** One print period's counters (see {@link #samplePeriod}). */
	public record Period(double hammers, double commerce, int plotDays, int marketDays,
			int fallbackDays) {
	}

	/**
	 * Read and <b>reset</b> the current print period's counters — called by the hammer
	 * printer once per print (monthly), so each row is that month's flow.
	 *
	 * @return the period just ended
	 */
	public Period samplePeriod() {
		Period p = new Period(periodHammers, periodCommerce, periodPlotDays,
				periodMarketDays, periodFallbackDays);
		periodHammers = 0;
		periodCommerce = 0;
		periodPlotDays = 0;
		periodMarketDays = 0;
		periodFallbackDays = 0;
		return p;
	}
}
