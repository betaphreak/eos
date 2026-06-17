package eos.agent;

import eos.settlement.Settlement;

/**
 * Builds the {@link Household} type that realizes one {@link Rank}, adopting an
 * existing household's {@link Estate} (its members and money). Registered with a
 * {@link RankLadder} per rank; the ladder calls it when promoting or demoting a
 * household <em>to</em> that rank.
 * <p>
 * The factory owns its rank's particulars — which bank tier the reformed
 * household banks at, and any funding rule — so the ladder itself never branches
 * on rank. A rank with no registered factory is an <b>unrealized</b> rung (no
 * agent type fills it yet, e.g. {@code CARAVAN} or {@code CITY}); the ladder skips
 * past such rungs to the next realized rank (see {@code docs/rank-ladder.md}).
 */
@FunctionalInterface
public interface RankFactory {

	/**
	 * Reform <tt>estate</tt> into a household of this factory's rank, banking at the
	 * factory's chosen tier and adopting the estate's head and members. The estate's
	 * balances seed the new account, so money is conserved across the reform.
	 *
	 * @param estate
	 *            the snapshotted state of the household being reformed
	 * @param colony
	 *            the colony the reformed household belongs to
	 * @return the reformed household
	 */
	Household reform(Estate estate, Settlement colony);
}
