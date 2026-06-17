package eos.agent;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import eos.bank.Bank;
import eos.settlement.Settlement;

/**
 * The realm's social-mobility engine: it reforms a {@link Household} into the
 * type that realizes an adjacent {@link Rank}, conserving the household's identity
 * (its {@link Member people}) and money across the type change. One uniform path
 * for promotion (a step up the ladder) and demotion (a step down).
 * <p>
 * A {@link RankFactory} is registered per rank that has a concrete household type;
 * a rank with no factory is an <b>unrealized</b> rung (e.g. {@code RETINUE},
 * {@code CITY}…{@code HEGEMONY}), which {@link #promote}/{@link #demote} skip past
 * to the next realized rank in the direction of travel. Off the end of the ladder
 * (or with no realized rank beyond), they reform nothing and return {@code null}.
 * <p>
 * <b>Timing.</b> A reform reads and closes the household's bank account and swaps
 * the agent, so — like the ennoblement it generalizes — it must run from an
 * <em>end-of-step</em> context (after the household's buy/sell offers for the step
 * have cleared and its account is safe to move), e.g. inside a {@link
 * Settlement#scheduleEndOfStepAction}. The new household is added immediately (so a
 * later reform the same step sees it); the old one is removed at end of step (its
 * account already closed, so the removal's estate settle is a no-op).
 * <p>
 * See {@code docs/rank-ladder.md}.
 */
public class RankLadder {

	private final Settlement colony;
	private final Map<Rank, RankFactory> factories = new EnumMap<>(Rank.class);

	/**
	 * Create a ladder for <tt>colony</tt>. Register the realized ranks' factories
	 * with {@link #register} before promoting/demoting.
	 *
	 * @param colony
	 *            the colony whose households this ladder reforms
	 */
	public RankLadder(Settlement colony) {
		this.colony = colony;
	}

	/**
	 * Register the factory that builds the household type realizing <tt>rank</tt>.
	 * A rank with no registered factory is treated as an unrealized rung and skipped.
	 *
	 * @param rank
	 *            the rank the factory realizes
	 * @param factory
	 *            how to reform a household into that rank
	 */
	public void register(Rank rank, RankFactory factory) {
		factories.put(rank, factory);
	}

	/**
	 * Promote <tt>household</tt> one realized rank up the ladder: reform it into the
	 * type of the next higher rank that has a registered factory (skipping unrealized
	 * rungs). Returns the reformed household, or {@code null} if there is no realized
	 * rank above it.
	 *
	 * @param household
	 *            the household to promote (must be safe to move — see class note)
	 * @return the reformed (higher-ranked) household, or {@code null}
	 */
	public Household promote(Household household) {
		return reform(household, nextRealized(household.rank(), true));
	}

	/**
	 * Demote <tt>household</tt> one realized rank down the ladder: reform it into the
	 * type of the next lower rank that has a registered factory (skipping unrealized
	 * rungs). Returns the reformed household, or {@code null} if there is no realized
	 * rank below it.
	 *
	 * @param household
	 *            the household to demote (must be safe to move — see class note)
	 * @return the reformed (lower-ranked) household, or {@code null}
	 */
	public Household demote(Household household) {
		return reform(household, nextRealized(household.rank(), false));
	}

	// the nearest rank in the given direction (up if `up`, else down) that has a
	// registered factory, skipping unrealized rungs; empty if there is none.
	private Optional<Rank> nextRealized(Rank from, boolean up) {
		Optional<Rank> r = up ? from.promoted() : from.demoted();
		while (r.isPresent() && !factories.containsKey(r.get()))
			r = up ? r.get().promoted() : r.get().demoted();
		return r;
	}

	// snapshot the household's (copper) balances, build the reformed household
	// adopting them via the target rank's factory, then close the old account (its
	// balances now live in the new one — money conserved) and swap the agent in.
	private Household reform(Household household, Optional<Rank> target) {
		if (target.isEmpty())
			return null;
		RankFactory factory = factories.get(target.get());
		Bank oldBank = household.getBank();
		double checking = oldBank.getChecking(household.getID());
		double savings = oldBank.getSavings(household.getID());
		Estate estate =
				new Estate(List.copyOf(household.getMembers()), checking, savings);
		Household reformed = factory.reform(estate, colony);
		oldBank.closeAcct(household.getID());
		colony.addAgent((Agent) reformed);
		colony.scheduleRemoveAgent((Agent) household);
		return reformed;
	}
}
