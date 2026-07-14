package com.civstudio.agent;

import java.time.LocalDate;
import java.util.List;

import com.civstudio.settlement.Settlement;

/**
 * Musters {@link ExplorerCaravan explorer levies} <b>seasonally</b> — the winter rule of {@code
 * docs/explorer-caravan.md}. Registered as a colony step action (like the ruler's dynamic firm
 * provisioning), it runs each {@code newDay} and, <b>in winter when food is scarce</b>, drafts a
 * band out of the pool's least-skilled adults and sends it to forage. The levy aims to be home by
 * <b>mid-autumn</b> — it forages through spring and summer, turns back in late summer, then
 * rejoins the settlement and works through the cold; come the next winter, its people can be
 * drafted out again. Fewer mouths at the table over the lean season, and imported food on return.
 * <p>
 * The cadence is <b>zero to start</b>, then <b>one muster</b> per firing with a <b>cooldown</b>
 * before the next (so a winter fields a few bands, not one per day), capped at {@link
 * #maxConcurrent} out at once. Provisioning follows decision 13: half the opening larder from the
 * crown's {@link Granary}, half the draftees' own share from the pool larder.
 * <p>
 * Off by default — a colony only musters explorers when this is installed ({@code
 * SimulationHarness.setExplorerProvisioning}), so a headless run without it is unchanged.
 */
public final class ExplorerProvisioner implements Runnable {

	/** Draftees taken per muster (a placeholder, calibrated per {@code docs/explorer-caravan.md} §10). */
	public static final int DEFAULT_DRAFT_BATCH = 20;
	/** Days to wait after a muster before another may leave (so a winter fields a few, not many). */
	public static final int DEFAULT_COOLDOWN_DAYS = 30;
	/** The most explorer bands that may be out at once. */
	public static final int DEFAULT_MAX_CONCURRENT = 3;
	/** Days of food to provision each draftee with at muster. */
	public static final double DEFAULT_PROVISION_DAYS = 60;

	// the month a levy starts its march home (northern late summer / southern late summer), so it
	// arrives by mid-autumn; and the winter muster window, by hemisphere
	private static final int NORTHERN_RETURN_MONTH = 8;
	private static final int SOUTHERN_RETURN_MONTH = 2;

	private final Settlement colony;
	private final Retinue pool;
	private final int draftBatch;
	private final int cooldownDays;
	private final int maxConcurrent;
	private final double provisionDays;

	// days left before another muster may fire (counts down each step); 0 = ready
	private int cooldown;
	// how many levies this provisioner has mustered over the run (diagnostics)
	private int mustered;

	/** Provision an explorer levy for {@code colony} from {@code pool}, with the default cadence. */
	public ExplorerProvisioner(Settlement colony, Retinue pool) {
		this(colony, pool, DEFAULT_DRAFT_BATCH, DEFAULT_COOLDOWN_DAYS, DEFAULT_MAX_CONCURRENT,
				DEFAULT_PROVISION_DAYS);
	}

	/**
	 * Provision an explorer levy with explicit cadence parameters (for calibration).
	 *
	 * @param colony        the mustering colony
	 * @param pool          its peasant pool (the draft source)
	 * @param draftBatch    draftees per muster
	 * @param cooldownDays  days between musters
	 * @param maxConcurrent the most bands out at once
	 * @param provisionDays days of food to provision each draftee with
	 */
	public ExplorerProvisioner(Settlement colony, Retinue pool, int draftBatch, int cooldownDays,
			int maxConcurrent, double provisionDays) {
		this.colony = colony;
		this.pool = pool;
		this.draftBatch = draftBatch;
		this.cooldownDays = cooldownDays;
		this.maxConcurrent = maxConcurrent;
		this.provisionDays = provisionDays;
	}

	@Override
	public void run() {
		if (cooldown > 0) {
			cooldown--;
			return;
		}
		if (pool == null || pool.size() == 0)
			return;
		LocalDate today = colony.getDate();
		// muster in winter — the lean season, when sending foragers out relieves the colony's
		// table and they can gather through spring and summer before returning by mid-autumn.
		// (The necessity price is a poor "scarce" gate — it stays flat until the death-throes
		// spike, firing too late; winter itself is the signal — see docs/food-balance.md.)
		if (!isWinter(today, colony.getLatitude()))
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
		band.setReturnBy(returnStart(today, colony.getLatitude())); // home by mid-autumn
		band.setCampingEnabled(true); // forage as it marches — the food it brings home
		colony.addExcursion(band);
		mustered++;
		cooldown = cooldownDays;
	}

	// winter at the colony's latitude — the food-scarce season a levy is mustered in (northern
	// Dec-Feb, southern Jun-Aug)
	private static boolean isWinter(LocalDate date, double latitude) {
		int m = date.getMonthValue();
		return latitude >= 0 ? (m == 12 || m == 1 || m == 2) : (m >= 6 && m <= 8);
	}

	// the date a levy mustered now should start its march home, so it is back by mid-autumn: the
	// next occurrence of the hemisphere's late-summer "turn home" month after the muster date
	private static LocalDate returnStart(LocalDate from, double latitude) {
		int month = latitude >= 0 ? NORTHERN_RETURN_MONTH : SOUTHERN_RETURN_MONTH;
		LocalDate d = from.withMonth(month).withDayOfMonth(1);
		return d.isAfter(from) ? d : d.plusYears(1);
	}

	/** @return how many explorer levies this provisioner has mustered over the run */
	public int getMustered() {
		return mustered;
	}
}
