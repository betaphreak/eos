package com.civstudio.agent;

import java.util.ArrayList;
import java.util.List;

import com.civstudio.agent.laborer.Laborer;
import com.civstudio.bank.Bank;
import com.civstudio.settlement.Settlement;

/**
 * Sheds a colony's <b>landless households</b> as wandering {@link SettlerCaravan settler bands} —
 * the land-scarcity outlet ({@code docs/estate-system.md}; user ruling 2026-07-24). Under the
 * "has a regular building" land model, empty ground is a single-household farm and developed ground
 * is stacked housing; a household that can get <b>neither</b> a farm nor a housing slot (the colony
 * has built over its land and filled its housing) is stranded ({@code Laborer.homePlot == null}).
 * Such families can neither farm nor build a house nor wed, so rather than stagnate they band
 * together and strike out to seek land elsewhere. The landless are, in short, the source of caravans.
 * <p>
 * Registered as a colony step action (like {@link ExplorerProvisioner}), it runs each {@code newDay}
 * and, once at least {@link #DEFAULT_MIN_BAND} landless households have gathered, musters them into a
 * {@link SettlerCaravan#ofEmigrants emigrant band} that leaves the colony for good (registered at the
 * session level like a dissolution band, so it wanders and may re-found a colony where it settles)
 * and removes those households from the colony. A cooldown keeps the colony from trickling out a band
 * a day — the landless wait a little for enough company to travel with.
 */
public final class LandlessProvisioner implements Runnable {

	/** Landless households that must gather before a band forms (a small caravan is still a caravan). */
	public static final int DEFAULT_MIN_BAND = 3;
	/** Days to wait after a band leaves before another may form. */
	public static final int DEFAULT_COOLDOWN_DAYS = 30;

	private final Settlement colony;
	private final Bank bank;
	private final int minBand;
	private final int cooldownDays;

	// days left before another band may form (counts down each step); 0 = ready
	private int cooldown;
	// how many emigrant bands this provisioner has sent out over the run (diagnostics)
	private int departed;

	/** Shed {@code colony}'s landless as settler bands through {@code bank}, with the default cadence. */
	public LandlessProvisioner(Settlement colony, Bank bank) {
		this(colony, bank, DEFAULT_MIN_BAND, DEFAULT_COOLDOWN_DAYS);
	}

	/**
	 * Shed landless households as settler bands with explicit cadence parameters (for calibration).
	 *
	 * @param colony       the colony shedding its landless
	 * @param bank         the copper bank an emigrant band's pool transacts through
	 * @param minBand      landless households required before a band forms
	 * @param cooldownDays days between bands
	 */
	public LandlessProvisioner(Settlement colony, Bank bank, int minBand, int cooldownDays) {
		this.colony = colony;
		this.bank = bank;
		this.minBand = minBand;
		this.cooldownDays = cooldownDays;
	}

	@Override
	public void run() {
		if (cooldown > 0) {
			cooldown--;
			return;
		}
		// a wandering band lives at the session level; a session-less colony (a bare analytical sim)
		// cannot shed one, so its landless simply stay (they were harmless there anyway)
		if (colony.getSession() == null)
			return;
		// gather the colony's landless workforce households (no ground of their own)
		List<Laborer> landless = new ArrayList<>();
		for (Agent a : colony.getAgents())
			if (a.isAlive() && a instanceof Laborer l && l.isWorkforce() && l.getHomePlot() == null)
				landless.add(l);
		if (landless.size() < minBand)
			return;

		// muster them into a wandering settler band that leaves for good, and remove the households
		SettlerCaravan band = SettlerCaravan.ofEmigrants(colony, landless, bank);
		colony.getSession().addCaravan(band);
		for (Laborer l : landless)
			colony.scheduleRemoveAgent(l);
		departed++;
		cooldown = cooldownDays;
	}

	/** @return how many emigrant settler bands this provisioner has sent out over the run. */
	public int getDeparted() {
		return departed;
	}
}
