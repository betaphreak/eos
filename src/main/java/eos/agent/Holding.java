package eos.agent;

/**
 * A productive asset a household owns and draws a dividend from — a
 * {@link eos.agent.firm.Firm firm} or a {@link eos.bank.Bank bank} today. A
 * {@link eos.agent.noble.Noble} (and, later, other holders) keeps a single list
 * of these and skims a share of each one's profit each step, so the dividend
 * pathway need not branch on the concrete kind of asset.
 * <p>
 * A holding is <b>distinct from an {@link Estate}</b>: an {@code Estate} is a
 * household's <em>liquid</em> identity (its members and account balances) carried
 * across a rank change, whereas a holding is the <em>productive property</em> it
 * owns. A rank reform carries the {@code Estate}; the holdings move separately
 * (see {@code Noble.transferHoldingsTo}). See {@code docs/village-founding.md}.
 */
public interface Holding {

	/**
	 * The profit available to distribute to the owner this step, in copper — never
	 * negative. A firm reports {@code max(0, profit)} (and 0 once dissolved, so a
	 * dead firm pays no dividend); a bank reports its retained, distributable profit.
	 *
	 * @return the distributable profit (&ge; 0)
	 */
	double distributableProfit();

	/**
	 * Pay <tt>amount</tt> out of this holding — the counterpart the owner then
	 * credits to itself. A firm moves it out of its account; a bank skims it from
	 * its retained equity. The owner is responsible for crediting the matching sum.
	 *
	 * @param amount
	 *            the dividend to disburse (expected {@code > 0})
	 */
	void disburse(double amount);
}
