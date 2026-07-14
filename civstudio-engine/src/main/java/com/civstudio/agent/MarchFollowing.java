package com.civstudio.agent;

/**
 * The <b>marching following</b> of a {@link MarchingCaravan} — the slice of a band's people
 * the march machinery needs: a head-count, a carried food larder it eats from and forages
 * into, and a daily consume step. Abstracting it lets a band march over either a full
 * {@link Retinue} (the settler/dissolution band's transferred labour reserve — its people
 * carried out of a vanished colony) or a lean {@link DraftBand} (an {@link ExplorerCaravan}'s
 * <b>drafted</b> levy, whose people stay accounted in their home households/pool and are only
 * <em>referenced</em> here — see {@code docs/explorer-caravan.md}).
 * <p>
 * The march reads only these six operations; anything Retinue-specific (promotion, marriage,
 * absorbing disbanded households, market relief) belongs to {@link Retinue} alone and is
 * reached through the covariant {@link SettlerCaravan#getFollowing()} on the bands that carry
 * one.
 */
public interface MarchFollowing {

	/** Advance the following one day: eat the day's ration from the carried larder. */
	void act();

	/** @return the number of people in the following (drives column length and forage/gather) */
	int size();

	/** @return the carried food larder (necessity units) — its countdown to starvation */
	double getLarder();

	/**
	 * Add food to the carried larder — the day's forage, or provisions loaded at muster.
	 *
	 * @param amount necessity units to add
	 */
	void stockLarder(double amount);

	/** @return the food the following actually ate last {@link #act() day} (for the journal) */
	double getLastConsumed();

	/**
	 * Put the following into its detached <b>wandering</b> mode (marketless, larder-fed) for
	 * as long as the band is on the road — called once when the band is formed. A following
	 * that is always wandering (a {@link DraftBand}) treats this as a no-op.
	 */
	void detach();
}
