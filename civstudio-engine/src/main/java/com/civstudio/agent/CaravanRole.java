package com.civstudio.agent;

import com.civstudio.agent.march.MarchFlavor;

/**
 * The <b>role</b> of a {@link Caravan} — what a band is <em>for</em>, mirroring a
 * Caveman2Cosmos land unit's {@code <DefaultUnitAI>} family (the discriminator C2C uses to
 * pick a unit's behaviour). Each role fields a march {@linkplain #marchFlavor() column} and
 * is realized by a concrete {@link MarchingCaravan} subclass whose {@link
 * MarchingCaravan#arrive(java.time.LocalDate, com.civstudio.util.Rng) arrival mission}
 * carries out the role.
 * <p>
 * The four land roles map onto the C2C {@code UnitAI} taxonomy (from the
 * {@code caveman2cosmos/Caveman2Cosmos} unit XML):
 * <ul>
 * <li>{@link #SETTLER} — {@code UNITAI_SETTLE} (e.g. {@code UNIT_BAND}, {@code UNIT_TRIBE},
 * {@code UNIT_SETTLER}); founds a colony ({@code MISSION_FOUND}).</li>
 * <li>{@link #WORKER} — {@code UNITAI_WORKER} (e.g. {@code UNIT_WORKER}); builds map
 * infrastructure — routes and tile improvements ({@code MISSION_BUILD} → {@code
 * CIV4BuildInfos.xml}).</li>
 * <li>{@link #EXPLORER} — {@code UNITAI_EXPLORE} (e.g. {@code UNIT_SCOUT},
 * {@code UNIT_EXPLORER}); scouts the map and identifies resources.</li>
 * <li>{@link #MILITARY} — the combat AIs ({@code UNITAI_ATTACK}, {@code UNITAI_ATTACK_CITY},
 * {@code UNITAI_CITY_DEFENSE}, …); moves to project force.</li>
 * </ul>
 * The C2C sea/air variants ({@code *_SEA}/{@code *_AIR}) are out of scope — this model is
 * land-only — and the C2C {@code UNITAI_MERCHANT} role is reserved for the planned trade
 * caravan (see {@code docs/caravan-trade.md}).
 */
public enum CaravanRole {

	/** Found a new colony (C2C {@code UNITAI_SETTLE}). */
	SETTLER(MarchFlavor.SETTLER),
	/** Build map infrastructure — routes and improvements (C2C {@code UNITAI_WORKER}). */
	WORKER(MarchFlavor.SETTLER),
	/** Scout the map and identify resources (C2C {@code UNITAI_EXPLORE}). */
	EXPLORER(MarchFlavor.SETTLER),
	/** Move armies to project force (C2C combat AIs — {@code UNITAI_ATTACK}, …). */
	MILITARY(MarchFlavor.MILITARY);

	private final MarchFlavor marchFlavor;

	CaravanRole(MarchFlavor marchFlavor) {
		this.marchFlavor = marchFlavor;
	}

	/**
	 * The band's <b>order of march</b> for this role — a lean admin column
	 * ({@link MarchFlavor#SETTLER}) for the civilian roles (settler/worker/explorer), the
	 * full order ({@link MarchFlavor#MILITARY}) for a military band (see {@code
	 * docs/caravan-march.md} §5).
	 *
	 * @return the march flavor this role fields
	 */
	public MarchFlavor marchFlavor() {
		return marchFlavor;
	}
}
