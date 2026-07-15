package com.civstudio.settlement;

import com.civstudio.agent.Agent;
import com.civstudio.agent.firm.ConsumerGoodFirm;
import com.civstudio.agent.laborer.Laborer;
import com.civstudio.geo.Improvement;
import com.civstudio.good.RationSize;

import lombok.extern.java.Log;

/**
 * The <b>food / subsistence subsystem</b> of a {@link Settlement}: the colony's <b>food box</b> (the
 * banked net-food surplus that drives tier growth) and the two modes of the one <b>plot-working
 * economy</b> that fill it (see {@code docs/plot-working-plan.md} P4). Extracted from {@code Settlement}
 * to keep that class focused on the step loop, agent/market registry and tier lifecycle — everything
 * here is subsistence, not the step machinery.
 * <p>
 * A colony feeds itself off the land in one of two modes, both reading {@link Plot#yields()}{@code
 * [FOOD]}:
 * <ul>
 * <li>a sub-{@link SettlementTier#SMALLHOLDING SMALLHOLDING} <b>camp</b> forages its site — its pooled
 * peasants ({@link #campForagers()}) work a single {@link #campPlot forage plot} collectively
 * (<b>labour-scaled</b>: {@link #campForageYield(int)} {@code = foragers × rate × plotFood}), building a
 * {@code HUNTING_CAMP} on it over time ({@link #advanceCampForageBuild()});</li>
 * <li>a <b>settled</b> colony's landed households each farm a <b>shared</b> home plot
 * (<b>land-scaled</b>: {@link #homePlotFoodYield(Plot)} {@code = plotFood × rate ÷ load} — the P2
 * Malthusian split), on top of which the market farm sector ({@code NFirm}s) produces the surplus/trade
 * food.</li>
 * </ul>
 * Both feed one {@link #dailyFoodSurplus() food balance} (Σ plot food + firm output − consumption, with
 * {@link #applyFoodWastage(double, double) wastage}) and one {@link #foodBox}. The tier state machine
 * ({@code Settlement.grow()}) drives the box through {@link #advanceDay()}, {@link #getFoodBox()} and
 * {@link #spendForGrowth(int)}. Food is created outside the market (no counterparty), like the camp
 * forage — it never touches the bank/market accounting.
 * <p>
 * The two public <b>tuning levers</b> that other packages reference stay on {@code Settlement} as the
 * facade: {@link Settlement#CAMP_RATION} (the lean band ration) and {@link Settlement#HOUSEHOLD_PLOT_RATE}
 * (the home-plot self-sufficiency rate).
 */
@Log
class FoodEconomy {

	// food-box calibration knobs, ported from C2C (memory c2c-city-growth-mechanics):
	// - FOOD_KEPT_FRACTION: the granary retention — growing a rung keeps at least this fraction of the
	//   rung's cost in the box (C2C's getFoodKept).
	// - WASTAGE_*: a simplified port of C2C's CvCity::foodWastage — surplus above (fraction ×
	//   consumption) suffers diminishing returns, so a huge daily surplus cannot all be banked.
	//   GROWTH_FACTOR 0.05 matches C2C's default.
	private static final double FOOD_KEPT_FRACTION = 0.25;
	private static final double WASTAGE_START_CONSUMPTION_FRACTION = 1.0;
	private static final double WASTAGE_GROWTH_FACTOR = 0.05;

	// Camp economy (sub-SMALLHOLDING tiers). Phase G — forage-as-improvement: the forage scales with the
	// SITE's real food yield (foragers × campForagePerForager × campPlotFood), so rich ground climbs and
	// poor ground starves the band into departing. campForagePerForager is per-forager-per-unit-of-plot-
	// food (settable so a test can force starvation); its default is tuned so typical ground (~1.4 food)
	// yields ~0.14/forager. DEFAULT_CAMP_SITE_FOOD is the fallback plot-food for a province-less camp;
	// CAMP_BUILD_PER_FORAGER is the daily work per forager toward the HUNTING_CAMP. All UNCALIBRATED.
	static final double DEFAULT_CAMP_FORAGE_PER_FORAGER = 0.10;
	private static final double DEFAULT_CAMP_SITE_FOOD = 1.4;
	private static final double CAMP_BUILD_PER_FORAGER = 0.02;

	// the owning colony, for the tier / population / date / agents the food balance reads
	private final Settlement colony;

	// the colony's spatial subsystem, for the home-plot load (the Malthusian divisor) and the camp's
	// bare forage-plot claim
	private final PlotField plotField;

	// the banked net food surplus (Civ4-style) that drives tier growth; may be negative (starvation)
	private double foodBox;

	// the camp's per-forager daily forage yield per unit of plot food (a settable tuning lever)
	private double campForagePerForager = DEFAULT_CAMP_FORAGE_PER_FORAGER;

	// the plot the camp forages (and builds its forage improvement on), or null for a province-less camp
	// / one that could claim none — then campPlotFood falls back to DEFAULT_CAMP_SITE_FOOD. The
	// improvement it is building (a HUNTING_CAMP) and the work accrued toward its buildCost.
	private Plot campPlot;
	private Improvement campForageImprovement;
	private double campBuildProgress;

	FoodEconomy(Settlement colony, PlotField plotField) {
		this.colony = colony;
		this.plotField = plotField;
	}

	// --- Food box (driven by Settlement.grow, the tier state machine) -----------------------------

	/** The current food-box balance (net-surplus units; may be negative). */
	double getFoodBox() {
		return foodBox;
	}

	/** Prime the food box (a test seam / the tier state machine's carry-over). */
	void setFoodBox(double foodBox) {
		this.foodBox = foodBox;
	}

	/**
	 * Advance the food economy one day: put the camp's foragers to work on its forage improvement (so
	 * the day's forage reflects any improvement raised today), then bank the day's net food surplus.
	 * Called first thing in {@code Settlement.grow()}, before the tier state machine reads the box.
	 */
	void advanceDay() {
		advanceCampForageBuild();
		foodBox += dailyFoodSurplus();
	}

	/**
	 * Spend the food box to grow one rung: subtract the rung's {@code cost}, but keep at least {@link
	 * #FOOD_KEPT_FRACTION} of it in the box (the granary carry-over). Called by the tier state machine.
	 *
	 * @param cost the rung's {@link SettlementTier#foodToChange()}
	 */
	void spendForGrowth(int cost) {
		foodBox = Math.max(foodBox - cost, cost * FOOD_KEPT_FRACTION);
	}

	// --- The plot-working food balance ------------------------------------------------------------

	/**
	 * The colony's net food surplus this day (the C2C food balance): the food its land <b>produces</b>
	 * — the one plot-working economy, in either mode — minus the food its residents <b>eat</b>, with a
	 * large surplus subject to {@link #applyFoodWastage(double, double) wastage}. A sub-SMALLHOLDING
	 * camp forages its site (labour-scaled, a lean {@link Settlement#CAMP_RATION} per resident); a
	 * settled colony sums its households' home-plot food plus the market farm sector's output (a worker
	 * {@link RationSize#FINE} ration per resident). {@code getOutput()} can read stale for a firm that
	 * got no labour this step (docs/food-balance.md) — an accepted approximation at this stage.
	 *
	 * @return the day's net food surplus (necessity units; may be negative)
	 */
	double dailyFoodSurplus() {
		boolean settled = colony.getTier().atLeast(SettlementTier.SMALLHOLDING);
		// one plot-working food source at either tier — read the same Plot.yields()[FOOD], scaled by
		// labour (a camp's band on one plot) or by land (settled households on shared home plots)
		double produced = plotFood(settled);
		// settled tiers add the market farm sector's output (a camp has no firms)
		if (settled)
			produced += farmOutput();
		double eaten = colony.totalResidents()
				* (settled ? RationSize.FINE : Settlement.CAMP_RATION).perDay();
		return applyFoodWastage(produced - eaten, eaten);
	}

	// the day's plot-worked food: a camp's forage (its band working the site) or a settled colony's
	// households' home-plot food (each the plot's yield split by the plot's load — summing the shares
	// over a plot's households recovers the plot's whole food, so the box counts each plot once).
	private double plotFood(boolean settled) {
		if (!settled)
			return campForageYield(colony.campForagers());
		double sum = 0;
		for (Agent a : colony.getAgents())
			if (a.isAlive() && a instanceof Laborer l)
				sum += homePlotFoodYield(l.getHomePlot());
		return sum;
	}

	// the market farm sector's output — every living necessity firm's product (the surplus/trade food
	// the landed households do not grow themselves)
	private double farmOutput() {
		double sum = 0;
		for (Agent a : colony.getAgents())
			if (a.isAlive() && a instanceof ConsumerGoodFirm f
					&& "Necessity".equals(f.getProductName()))
				sum += f.getOutput();
		return sum;
	}

	// diminishing returns on a large daily surplus (a simplified port of C2C's CvCity::foodWastage):
	// surplus up to (start = WASTAGE_START_CONSUMPTION_FRACTION × consumption) banks in full; beyond
	// that the excess saturates toward an asymptote (1/WASTAGE_GROWTH_FACTOR). A deficit (≤ 0) is
	// returned unchanged (never "wasted"). Package-private for direct unit testing.
	static double applyFoodWastage(double surplus, double consumption) {
		if (surplus <= 0)
			return surplus;
		double start = WASTAGE_START_CONSUMPTION_FRACTION * consumption;
		if (surplus <= start)
			return surplus;
		double excess = surplus - start;
		return start + excess / (1.0 + WASTAGE_GROWTH_FACTOR * excess);
	}

	// --- Home-plot food (settled mode) ------------------------------------------------------------

	/**
	 * The daily <b>subsistence food</b> a landed household draws from its home plot — the plot's food
	 * yield ({@link Plot#yields()} index 0) times {@link Settlement#HOUSEHOLD_PLOT_RATE}, <b>split
	 * equally</b> among the households sharing it ({@code ÷} its {@link PlotField#homePlotLoad(Plot)
	 * load} — the P2 Malthusian dilution). Dropped straight into the household's larder, outside the
	 * market. {@code 0} for a landless household (a {@code null} or non-home plot).
	 *
	 * @param homePlot the household's home plot, or {@code null} if it is landless
	 * @return the day's per-household home-plot food, or 0 if landless
	 */
	double homePlotFoodYield(Plot homePlot) {
		if (homePlot == null)
			return 0;
		int load = plotField.homePlotLoad(homePlot);
		if (load <= 0)
			return 0;
		return Math.max(0, homePlot.yields()[0]) * Settlement.HOUSEHOLD_PLOT_RATE / load;
	}

	// --- Camp forage (camp mode) ------------------------------------------------------------------

	/**
	 * The daily <b>forage yield</b> of a {@link SettlementTier#CAMP camp}'s workforce — the food its
	 * {@code foragers} pooled peasants bring in by working the site: {@code foragers ×
	 * campForagePerForager × }{@link #campPlotFood()}. Read by the food box and by the pool's camp
	 * provisioning (which stocks it into the larder), so the larder and the box move together.
	 *
	 * @param foragers the number of foraging peasants (the camp's workforce)
	 * @return the day's foraged food (necessity units)
	 */
	double campForageYield(int foragers) {
		return foragers * campForagePerForager * campPlotFood();
	}

	// the food yield of the camp's forage plot (index 0 of Plot.yields()), or the DEFAULT_CAMP_SITE_FOOD
	// fallback when the camp has no real plot. Floored at 0.
	double campPlotFood() {
		if (campPlot == null)
			return DEFAULT_CAMP_SITE_FOOD;
		return Math.max(0, campPlot.yields()[0]);
	}

	/**
	 * Wire up a camp's forage plot (Phase G): claim one bare plot from the site for the band to forage
	 * and, over time, build {@code forageImprovement} (a {@code HUNTING_CAMP}) on. A province-less
	 * colony (or one that can claim no plot) leaves it null and forages the flat fallback.
	 *
	 * @param forageImprovement the forage improvement the camp builds, or {@code null} for bare land
	 */
	void setUpCampForage(Improvement forageImprovement) {
		this.campForageImprovement = forageImprovement;
		this.campPlot = plotField.claimBarePlot();
	}

	// advance the camp's forage-improvement build: each day the foragers put work toward the
	// HUNTING_CAMP's buildCost; once met, raise it on the plot WITHOUT clearing (terrain + feature
	// yields keep stacking) — durable, so a later colony inherits the developed ground. A no-op past
	// CAMP tier, with no plot/improvement, or once already built.
	private void advanceCampForageBuild() {
		if (colony.getTier().atLeast(SettlementTier.SMALLHOLDING) || campPlot == null
				|| campForageImprovement == null || campPlot.improvement() != null)
			return;
		campBuildProgress += colony.campForagers() * CAMP_BUILD_PER_FORAGER;
		if (campBuildProgress >= campForageImprovement.buildCost()) {
			campPlot.raiseImprovement(campForageImprovement, false);
			log.info(colony.name() + " raised a " + campForageImprovement.type()
					+ " on its forage plot on " + colony.getDate() + " (site food now "
					+ String.format("%.1f", campPlotFood()) + ")");
		}
	}

	/**
	 * Set this camp's per-forager daily forage yield — the camp economy's tuning lever. Below {@link
	 * Settlement#CAMP_RATION} the camp starves its band and departs as a wandering caravan; above it
	 * the camp net-grows and climbs.
	 *
	 * @param perForager the daily forage yield per foraging peasant (per unit of plot food)
	 */
	void setCampForagePerForager(double perForager) {
		this.campForagePerForager = perForager;
	}
}
