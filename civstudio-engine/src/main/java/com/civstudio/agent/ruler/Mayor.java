package com.civstudio.agent.ruler;

import com.civstudio.agent.Agent;
import com.civstudio.agent.Estate;
import com.civstudio.agent.Member;
import com.civstudio.agent.Rank;
import com.civstudio.bank.Bank;
import com.civstudio.settlement.Settlement;

/**
 * The <b>mayor</b> of a metropolis — the head household of a settlement at
 * {@link com.civstudio.settlement.SettlementTier#METROPOLIS}, commanding a
 * {@link Rank#CITY}. A <b>light urbanization</b> of the {@link Ruler} (see
 * {@code docs/city-and-league.md}): it runs the very same gold-banking treasury economy
 * (dynamic firm provisioning, the export firm, taxation, poor relief), but leads a
 * permanent urban centre rather than a village. It is <b>not</b> a distinct economy —
 * everything a ruler does, a mayor does; only its {@link #rank() rank} and
 * {@link #role() label} differ.
 * <p>
 * A {@link Ruler} is reformed into a {@code Mayor} when its settlement climbs
 * {@code TOWN → METROPOLIS} (the head-rank derivation of
 * {@code docs/rank-ladder-improvements.md} R2, driven from the tier crossing via
 * {@link com.civstudio.agent.RankLadder#reformTo}); the reform carries the treasury 1:1, so the
 * colony's money is conserved. The symmetric demotion Mayor&rarr;Ruler on decline is R4.
 */
public class Mayor extends Ruler {

	/**
	 * Reform a {@link Ruler}'s {@link Estate} (carried across the {@code TOWN → METROPOLIS}
	 * crossing) into a mayor: it adopts the ruler's head and members, and opens its gold treasury
	 * with the carried balance (money conserved — the ladder closes the ruler's old account).
	 *
	 * @param estate            the reforming ruler's snapshotted state (head, members, balances)
	 * @param consumptionRate   fraction of the treasury spent on enjoyment each step
	 * @param bankProfitTaxRate fraction of each public bank's distributable profit taxed each step
	 * @param nobleIncomeTaxRate fraction of each noble's income taxed each step
	 * @param goldBank          the gold bank the mayor owns and banks at
	 * @param colony            the colony this mayor heads
	 */
	public Mayor(Estate estate, double consumptionRate, double bankProfitTaxRate,
			double nobleIncomeTaxRate, Bank goldBank, Settlement colony) {
		super(estate.head(), estate.checking() + estate.savings(), consumptionRate,
				bankProfitTaxRate, nobleIncomeTaxRate, goldBank, colony);
		for (Member m : estate.members())
			if (m != estate.head())
				addMember(m);
		setName("Mayor");
	}

	// the heir who succeeds this mayor: a same-dynasty mayor inheriting the treasury (funded out of
	// the gold bank's equity, as for any household succession) — the metropolis endures.
	private Mayor(Mayor predecessor, Settlement colony) {
		super(predecessor, colony);
		setName("Mayor");
	}

	/** A mayor commands a {@link Rank#CITY} — the metropolis it leads. */
	@Override
	public Rank rank() {
		return Rank.CITY;
	}

	/** Role label used in the persons-of-interest roster and death log. */
	@Override
	public String role() {
		return "Mayor";
	}

	/**
	 * The heir who succeeds this mayor: a same-dynasty mayor inheriting the treasury. Also updates
	 * the colony's ruler reference (a {@code Mayor} is a {@link Ruler}), so taxation/relief bill the
	 * heir, not the dead mayor's closed account.
	 */
	@Override
	public Agent successor(Settlement colony) {
		Mayor heir = new Mayor(this, colony);
		colony.setRuler(heir);
		return heir;
	}
}
