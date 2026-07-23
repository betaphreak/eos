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
		// donate: all hammers flow to the colony sink until B3 gives households projects
		totalHammersDonated += hammers;
		periodHammers += hammers;
		// mint: commerce coin appears in the household's account with no counterparty
		if (commerce > 0)
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
		for (Laborer l : marketChoosers)
			if (l.isAlive() && !labor.wasHiredLastClear(l.getID())) {
				workPlotDay(l);
				periodFallbackDays++;
				// workPlotDay counted it as a plot day too; keep the two disjoint
				periodPlotDays--;
			}
		marketChoosers.clear();
	}

	// one yield channel's per-household share: the plot's raw yield × the daylight
	// factor × the household's proficiency in the working skill ÷ the plot's Malthusian
	// load (the same equal-split as home-plot food). The skill factor is the household's
	// mean adult level in the skill, through the same productivity curve firm labor uses.
	private double yieldShare(Laborer laborer, Plot plot, int yieldIndex, Skill skill) {
		int load = plotField.homePlotLoad(plot);
		if (load <= 0)
			return 0;
		double raw = Math.max(0, plot.yields()[yieldIndex]);
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
