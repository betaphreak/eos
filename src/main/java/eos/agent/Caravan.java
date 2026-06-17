package eos.agent;

import eos.good.RationSize;
import lombok.Getter;

/**
 * A <b>wandering band</b> — the mobile state of a household and its following while
 * <b>not settled</b>: before it founds a settlement, or after one collapses (see
 * {@code docs/caravan.md}). A {@code Caravan} is the {@link Rank#CARAVAN} rung made
 * concrete: a leader (the Captain) commanding a {@link Retinue following}, carrying a
 * treasury and a <b>position</b> on the map, with no settlement around it.
 * <p>
 * The {@link Retinue} is the band's persistent core (the same following-asset whether
 * settled or wandering); the {@code Caravan} adds what only a <em>mobile</em> band
 * needs — its {@link #getLatitude() position} and the lean {@link #WANDERING_RATION}
 * its following eats from the carried larder while it cannot restock on a market.
 * <p>
 * This is a minimal scaffold for the feature: it holds the following and the position.
 * The {@code CARAVAN}-rank Captain household, the carried (bankless) hoard, and the
 * detach-on-collapse / settle-on-founding flow that binds the {@link Retinue} to and
 * from a {@code Settlement} arrive in later phases (see {@code docs/caravan.md}).
 */
public class Caravan {

	/**
	 * The daily ration a wandering band eats from its carried larder while not
	 * settled — {@link RationSize#SNACK}, leaner even than poor relief, because a band
	 * on the move cannot restock on a market.
	 */
	public static final RationSize WANDERING_RATION = RationSize.SNACK;

	// the band's following: its unranked people and their larder, detached from any
	// settlement (the same Retinue that is a settled colony's labour reserve)
	@Getter
	private final Retinue following;

	// the band's geographic position in decimal degrees (north / east positive),
	// mirroring a Settlement's; mutable because a caravan moves
	@Getter
	private double latitude;
	@Getter
	private double longitude;

	/**
	 * Create a wandering band at the given position.
	 *
	 * @param following
	 *            the band's following (its people and carried larder)
	 * @param latitude
	 *            the band's latitude in decimal degrees (north positive)
	 * @param longitude
	 *            the band's longitude in decimal degrees (east positive)
	 */
	public Caravan(Retinue following, double latitude, double longitude) {
		this.following = following;
		this.latitude = latitude;
		this.longitude = longitude;
		// a band on the move eats from its larder, marketless — put the following into
		// its wandering mode (the {@link #WANDERING_RATION}) for as long as it is a caravan
		following.detach();
	}

	/**
	 * Move the band to a new position (the seam the future caravan-trade geography
	 * will drive).
	 *
	 * @param latitude
	 *            the new latitude in decimal degrees (north positive)
	 * @param longitude
	 *            the new longitude in decimal degrees (east positive)
	 */
	public void moveTo(double latitude, double longitude) {
		this.latitude = latitude;
		this.longitude = longitude;
	}
}
