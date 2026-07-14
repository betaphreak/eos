package com.civstudio.agent;

import java.util.List;

import com.civstudio.settlement.Settlement;

/**
 * Musters {@link ExplorerCaravan explorer levies} under <b>food pressure</b> — the trigger of
 * {@code docs/explorer-caravan.md}. Registered as a colony step action (like the ruler's dynamic
 * firm provisioning), it runs each {@code newDay} and, when the peasant pool's larder is running
 * low (or it starved last step), <b>drafts one band at a time</b> out of the pool's least-skilled
 * adults and sends it out to forage: fewer mouths at the colony's table now, and imported food on
 * its return.
 * <p>
 * The cadence is deliberate — <b>zero to start</b>, then <b>one muster</b> per firing with a
 * <b>cooldown</b> before the next (hysteresis), and a cap on how many bands may be out at once.
 * Provisioning follows decision 13: half the band's opening larder is drawn from the crown's
 * {@link Granary} (the ruler's store), half is the draftees' own share drawn from the pool larder
 * (food they would otherwise have eaten there).
 * <p>
 * Off by default — a colony only musters explorers when this is installed
 * ({@code SimulationHarness.setExplorerProvisioning}), so a headless run without it is unchanged.
 */
public final class ExplorerProvisioner implements Runnable {

	/** Draftees taken per muster (a placeholder, calibrated per {@code docs/explorer-caravan.md} §10). */
	public static final int DEFAULT_DRAFT_BATCH = 20;
	/** Days to wait after a muster before another may leave (hysteresis). */
	public static final int DEFAULT_COOLDOWN_DAYS = 30;
	/** The most explorer bands that may be out at once. */
	public static final int DEFAULT_MAX_CONCURRENT = 3;
	/** Days of food to provision each draftee with at muster. */
	public static final double DEFAULT_PROVISION_DAYS = 60;
	/** Muster once the pool holds fewer than this many days of food per peasant. */
	public static final double DEFAULT_TRIGGER_LARDER_DAYS = 30;
	// ...or once the pool has drained below this fraction of its high-water size. On the
	// default colony the pool drains by promotion/aging (its larder stays healthy), so the
	// larder signal alone is empirically silent (docs/food-balance.md); this catches the drain.
	private static final double POOL_SHRINK_FRACTION = 0.85;

	private final Settlement colony;
	private final Retinue pool;
	private final int draftBatch;
	private final int cooldownDays;
	private final int maxConcurrent;
	private final double provisionDays;
	private final double triggerLarderDays;

	// days left before another muster may fire (counts down each step); 0 = ready
	private int cooldown;
	// the pool's high-water size, for the drain (shrinkage) signal
	private int peakPoolSize;
	// how many levies this provisioner has mustered over the run (diagnostics)
	private int mustered;

	/** Provision an explorer levy for {@code colony} from {@code pool}, with the default cadence. */
	public ExplorerProvisioner(Settlement colony, Retinue pool) {
		this(colony, pool, DEFAULT_DRAFT_BATCH, DEFAULT_COOLDOWN_DAYS, DEFAULT_MAX_CONCURRENT,
				DEFAULT_PROVISION_DAYS, DEFAULT_TRIGGER_LARDER_DAYS);
	}

	/**
	 * Provision an explorer levy for {@code colony} from {@code pool} with explicit cadence
	 * parameters (for calibration).
	 *
	 * @param colony            the mustering colony
	 * @param pool              its peasant pool (the draft source and the pressure signal)
	 * @param draftBatch        draftees per muster
	 * @param cooldownDays      days between musters
	 * @param maxConcurrent     the most bands out at once
	 * @param provisionDays     days of food to provision each draftee with
	 * @param triggerLarderDays muster once the pool holds fewer than this many days of food
	 */
	public ExplorerProvisioner(Settlement colony, Retinue pool, int draftBatch, int cooldownDays,
			int maxConcurrent, double provisionDays, double triggerLarderDays) {
		this.colony = colony;
		this.pool = pool;
		this.draftBatch = draftBatch;
		this.cooldownDays = cooldownDays;
		this.maxConcurrent = maxConcurrent;
		this.provisionDays = provisionDays;
		this.triggerLarderDays = triggerLarderDays;
	}

	@Override
	public void run() {
		if (cooldown > 0) {
			cooldown--;
			return;
		}
		if (pool == null || pool.size() == 0)
			return;
		peakPoolSize = Math.max(peakPoolSize, pool.size());
		// pressure signal: the pool starved last step, its larder is down to a few days' food,
		// or it has drained below a fraction of its high-water size (the drain is the signal
		// that actually fires on the default colony — see docs/food-balance.md)
		double perDay = pool.getRation().perDay();
		double larderDays = perDay > 0 ? pool.getLarder() / (pool.size() * perDay) : 0;
		boolean pressured = pool.getLastStarved() > 0 || larderDays < triggerLarderDays
				|| pool.size() < peakPoolSize * POOL_SHRINK_FRACTION;
		if (!pressured)
			return;
		if (colony.getExcursions().size() >= maxConcurrent)
			return;
		List<Member> draftees = pool.draftableAdults(draftBatch);
		if (draftees.isEmpty())
			return;

		// provision (decision 13): half from the crown's granary, half the draftees' pool share
		double ration = MarchingCaravan.WANDERING_RATION.perDay();
		double target = draftees.size() * ration * provisionDays;
		double fromGranary =
				colony.getGranary() != null ? colony.getGranary().drawStock(target * 0.5) : 0;
		double fromPool = pool.drawPromotionStock(target - fromGranary);
		double larder = fromGranary + fromPool;

		ExplorerCaravan band = ExplorerCaravan.muster(colony, draftees, larder);
		band.setCampingEnabled(true); // forage as it marches — the food it brings home
		colony.addExcursion(band);
		mustered++;
		cooldown = cooldownDays;
	}

	/** @return how many explorer levies this provisioner has mustered over the run */
	public int getMustered() {
		return mustered;
	}
}
