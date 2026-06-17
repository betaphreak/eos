package eos.agent;

import java.util.List;

/**
 * The transferable state of a {@link Household} across a {@link Rank} change —
 * who it is and how much money it holds — independent of its concrete type. The
 * {@link RankLadder} snapshots this from a household before reforming it into the
 * type that realizes its new rank, so that identity (the {@link Member people})
 * and money survive the swap.
 * <p>
 * Balances are in <b>copper</b> (the base unit), read from the household's old
 * bank before its account is closed; the new household reopens with the same
 * balances, possibly at a different currency tier — so the colony's money is
 * conserved across the reform (see {@code docs/rank-ladder.md}).
 *
 * @param members
 *            the household's members, head first (the first is the {@link #head()})
 * @param checking
 *            the household's checking balance, in copper
 * @param savings
 *            the household's savings balance, in copper (negative for a loan)
 */
public record Estate(List<Member> members, double checking, double savings) {

	/**
	 * The head of the household being reformed — its first {@linkplain #members()
	 * member}, whose surname names the dynasty. The reformed household adopts it.
	 *
	 * @return the household head
	 */
	public Member head() {
		return members.get(0);
	}
}
