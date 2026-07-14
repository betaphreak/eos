package com.civstudio.simulation;

import java.time.DayOfWeek;
import java.util.List;
import java.util.function.Supplier;

import com.civstudio.agent.Agent;
import com.civstudio.agent.ExpeditionReturn;
import com.civstudio.agent.Granary;
import com.civstudio.agent.Household;
import com.civstudio.agent.Member;
import com.civstudio.agent.Rank;
import com.civstudio.agent.RankLadder;
import com.civstudio.agent.Retinue;
import com.civstudio.agent.firm.StrategicFirm;
import com.civstudio.agent.laborer.Laborer;
import com.civstudio.agent.laborer.LaborerConfig;
import com.civstudio.agent.noble.Noble;
import com.civstudio.agent.noble.NobleConfig;
import com.civstudio.bank.Bank;
import com.civstudio.name.Person;
import com.civstudio.race.Race;
import com.civstudio.settlement.Settlement;
import com.civstudio.skill.Skill;

/**
 * The colony's <b>social-mobility runtime</b>: the promotion/demotion of
 * households across ranks that runs each step, factored out of {@link
 * SimulationHarness} (which does construction) because this is ongoing
 * behaviour, not founding. It owns the {@link RankLadder} and the three step
 * actions a ruler-bearing colony runs — {@code topUpAristocracy} (ennoble the
 * ablest laborer while the aristocracy is short), {@code demoteRuinedNobles}
 * (reform an insolvent noble back to a laborer), and {@code formNewHouseholds}
 * (household fission, so in-colony births grow the household count). The {@link
 * DynamicFirmProvisioner} also uses it to find/raise a firm's owner.
 * <p>
 * All the actual rank changes are deferred to end of step (the household's
 * offers must clear before its account can move), exactly as before the
 * extraction; the logic is verbatim.
 */
class SocialMobility implements ExpeditionReturn {

	// a noble insolvent (a net debtor) for this many consecutive days is "ruined"
	// and demoted back to a laborer (see demoteRuinedNobles). A one-year grace (as
	// for MIN_FIRM_LIFETIME_DAYS) lets a cash-poor noble — e.g. one just ennobled
	// from an indebted laborer — earn its way back into the black before then.
	// A placeholder pending calibration.
	private static final int NOBLE_INSOLVENCY_GRACE_DAYS = 365;

	// household fission: the food a grown child carries out of its parent's larder when
	// it leaves to found its own household
	private static final double FISSION_NECESSITY_DOWRY =
			SimulationHarness.REPLACEMENT_NECESSITY_STOCK;

	private final Settlement colony;
	private final SimulationConfig cfg;
	private final NobleConfig nobleConfig;
	private final Supplier<Bank> silverBank;
	private final Supplier<Bank> copperBank;
	// the colony's peasant pool (a supplier, since it is founded after this runtime is built) —
	// the source a returned explorer draftee is released from when it founds its own household
	private final Supplier<Retinue> pool;

	// the social-mobility engine for this colony (promotion/demotion across ranks),
	// built lazily on first use with the realized ranks' factories registered (see
	// rankLadder()). Today only ennoblement (HOUSEHOLD -> HOLDING) uses it.
	private RankLadder rankLadder;

	// a tally of household fissions over the run
	private long fissionCount;

	SocialMobility(Settlement colony, SimulationConfig cfg, NobleConfig nobleConfig,
			Supplier<Bank> silverBank, Supplier<Bank> copperBank, Supplier<Retinue> pool) {
		this.colony = colony;
		this.cfg = cfg;
		this.nobleConfig = nobleConfig;
		this.silverBank = silverBank;
		this.copperBank = copperBank;
		this.pool = pool;
	}

	/**
	 * Register the standard social-mobility step actions on the colony (called from
	 * the ruler install, so every ruler-bearing colony runs them): the ennoblement
	 * top-up (only where an export sector exists to staff), the ruin demotion, and
	 * household fission.
	 */
	void install() {
		// a colony with an export sector staffs it by ennoblement: while it has fewer
		// than cfg.targetNobles() living nobles, the ruler raises the ablest laborer
		// into a silver-banking noble (the ruler works the strategic firm meanwhile —
		// see Ruler.act — so it is never unstaffed)
		if (colony.getMarket(StrategicFirm.LABOR_MARKET) != null)
			colony.addStepAction(this::topUpAristocracy);

		// the converse of ennoblement: a noble ruined (insolvent past a grace period)
		// is demoted back to a laborer. Registered for every ruler-bearing colony,
		// since nobles can arise even without an export sector (the no-owner charter
		// fallback); a no-op while every noble is solvent.
		colony.addStepAction(this::demoteRuinedNobles);

		// household fission: a grown, colony-born child leaves to found its own laborer
		// household, so in-colony births grow the household count (not just size) — the
		// renewal path the finite peasant pool cannot provide. A no-op until births
		// produce a working-age child (see formNewHouseholds).
		colony.addStepAction(this::formNewHouseholds);
	}

	/** Number of household fissions over the run so far. */
	long getFissionCount() {
		return fissionCount;
	}

	/**
	 * Ennoble the colony's ablest laborer household so it can own a firm chartered
	 * when no noble yet exists: the laborer with the highest head {@link
	 * Skill#SOCIAL} (the youngest breaking a tie) is <b>elevated to a {@link
	 * Noble}</b> banking in <b>silver</b>, adopting its head and members and carrying
	 * its (copper) balances over into a fresh silver account; the old copper account
	 * is then closed, so the colony's money is conserved. The laborer leaves the
	 * workforce and the new noble joins the step loop at end of step.
	 * <p>
	 * Called only as a deferred end-of-step action (see the charter path), so the
	 * laborer's buy offers have already cleared and its account is safe to move.
	 *
	 * @return the new noble, or {@code null} if the colony has no laborer to raise
	 */
	Noble ennobleBestLaborer() {
		Laborer best = null;
		for (Agent a : colony.getAgents())
			if (a instanceof Laborer lab && lab.isAlive()
					&& (best == null || moreEnnoblable(lab, best)))
				best = lab;
		if (best == null)
			return null;
		// reform the chosen laborer up the rank ladder: HOUSEHOLD -> HOLDING (the
		// reserved CARAVAN rung in between has no factory, so it is skipped). The
		// HOLDING factory re-banks it in silver, carries its balances and members
		// over, and the ladder closes the old account and swaps the agent — money
		// conserved, the laborer's surname staying in use with the noble that adopted
		// its head. Safe here: this runs only as a deferred end-of-step action (the
		// laborer's offers have cleared), exactly as before.
		return (Noble) rankLadder().promote(best);
	}

	/**
	 * Demote <tt>household</tt> one realized rank down the {@link RankLadder} — e.g.
	 * a ruined {@link Noble} ({@link Rank#HOLDING}) reformed back into a
	 * copper-banking {@link Laborer} ({@link Rank#HOUSEHOLD}), adopting its head and
	 * members and carrying its (copper) balances over so the colony's money is
	 * conserved. This is the capability the rank ladder unlocks; <b>no automatic
	 * trigger fires it yet</b> (a bankruptcy/attainder rule is future work — see
	 * {@code docs/rank-ladder.md}). Like ennoblement it must run from an
	 * <em>end-of-step</em> context (the household's offers must have cleared).
	 *
	 * @param household
	 *            the household to demote
	 * @return the reformed (lower-ranked) household, or {@code null} if there is no
	 *         realized rank below it
	 */
	Household demote(Household household) {
		return rankLadder().demote(household);
	}

	/**
	 * The colony's {@link RankLadder}, built lazily with the realized ranks'
	 * factories. Two ranks are realized:
	 * <ul>
	 * <li>{@link Rank#HOLDING} — a silver-banking {@link Noble} (ennoblement, a
	 * laborer reformed upward, adopting its head, members and balances);</li>
	 * <li>{@link Rank#HOUSEHOLD} — a copper-banking {@link Laborer} (demotion, a
	 * noble reformed downward, mirroring the pool-promotion construction).</li>
	 * </ul>
	 * The unrealized {@code CARAVAN} rung between them has no factory, so promoting a
	 * {@code HOUSEHOLD} laborer skips it and lands on {@code HOLDING}, and demoting a
	 * {@code HOLDING} noble skips it and lands on {@code HOUSEHOLD}.
	 *
	 * @return the colony's rank ladder
	 */
	private RankLadder rankLadder() {
		if (rankLadder == null) {
			rankLadder = new RankLadder(colony);
			// HOLDING: a laborer ennobled into a silver-banking noble
			rankLadder.register(Rank.HOLDING, (estate, c) -> {
				Member head = estate.head();
				Noble noble = new Noble(head, estate.checking(), estate.savings(),
						nobleConfig, silverBank.get(), c);
				// carry any further members (e.g. a spouse) across
				for (Member m : estate.members())
					if (m != head)
						noble.addMember(m);
				return noble;
			});
			// HOUSEHOLD: a noble demoted into a copper-banking laborer, built like a
			// pool-promoted laborer (same init template) but adopting the carried
			// balances rather than a fresh ruler-funded endowment
			rankLadder.register(Rank.HOUSEHOLD, (estate, c) -> {
				Member head = estate.head();
				Laborer laborer = new Laborer(head, cfg.laborer().e(),
						SimulationHarness.REPLACEMENT_NECESSITY_STOCK, estate.checking(),
						estate.savings(), cfg.laborer().savingsRate(),
						LaborerConfig.DEFAULT, copperBank.get(), c);
				for (Member m : estate.members())
					if (m != head)
						laborer.addMember(m);
				return laborer;
			});
		}
		return rankLadder;
	}

	/**
	 * Maintain the aristocracy at {@code cfg.targetNobles()} by ennoblement: a step
	 * action (registered for colonies with an export sector) that, once a week,
	 * raises the ablest laborer into a noble while the colony has too few. Weekly so
	 * the class forms gradually over the first weeks (the ruler staffs the export
	 * firm meanwhile). The actual ennoblement is deferred to end of step (the
	 * laborer's offers must clear before its account moves).
	 */
	private void topUpAristocracy() {
		if (colony.getDate().getDayOfWeek() != DayOfWeek.MONDAY)
			return;
		long nobles = colony.getAgents().stream()
				.filter(a -> a instanceof Noble n && n.isAlive()).count();
		if (nobles >= cfg.targetNobles())
			return;
		boolean hasLaborer = colony.getAgents().stream()
				.anyMatch(a -> a instanceof Laborer l && l.isAlive());
		if (hasLaborer)
			colony.scheduleEndOfStepAction(this::ennobleBestLaborer);
	}

	/**
	 * <b>Household fission</b> (a step action for every ruler-bearing colony). Once a
	 * week, each laborer household with more than its head is given a chance to
	 * emancipate a grown, colony-born child into a <b>new</b> laborer household — the
	 * mechanism that lets in-colony births grow the household <i>count</i> (the number
	 * the colony's survival floor measures), not just household size. The actual split
	 * is deferred to end of step (after the day's labor/market clearing, so the child's
	 * last day of labor credits its parent cleanly), exactly as ennoblement is. A no-op
	 * for a colony with no eligible children (e.g. before the first birth matures).
	 */
	private void formNewHouseholds() {
		if (colony.getDate().getDayOfWeek() != DayOfWeek.MONDAY)
			return;
		for (Agent a : colony.getAgents())
			if (a instanceof Laborer parent && parent.isAlive()
					&& parent.getMemberCount() > 1)
				colony.scheduleEndOfStepAction(() -> tryFission(parent));
	}

	// emancipate one grown child from the parent into its own household (deferred to end
	// of step): the dowry is GRANARY-funded (docs/granary.md §5.3), so the split is gated
	// on the colony's strategic store being able to dower the new household rather than on
	// the parent's larder (which is typically empty exactly when the child matures — the
	// measured second gate that kept fission from firing). Draw the dowry from the granary,
	// release the child, build a new single-head laborer household seeded with that dowry,
	// and queue its add. A no-op if the parent died this step, the granary cannot fund the
	// dowry, or there is no eligible child.
	private void tryFission(Laborer parent) {
		if (!parent.isAlive())
			return;
		Granary granary = colony.getGranary();
		if (granary == null || granary.getStock() < FISSION_NECESSITY_DOWRY)
			return; // no strategic store to dower a new household — fission waits
		Member child = parent.emancipateChild(colony.getDate());
		if (child == null)
			return;
		double dowry = granary.drawStock(FISSION_NECESSITY_DOWRY);
		colony.scheduleAddAgent(
				buildFissionHousehold(child, parent.getBank(), dowry));
		fissionCount++;
	}

	// build a new laborer household headed by an emancipated child: rename it under a
	// fresh dynasty surname (its parent's is still in use), keeping its given name,
	// gender, skills, race, age and parentage; it opens with only the food dowry it
	// carried (no cash — it earns its keep on the labor market like any laborer)
	private Laborer buildFissionHousehold(Member child, Bank bank, double initNQty) {
		Race race = child.race();
		String surname = colony.getNames().nextDynastyName(race);
		Member head = new Member(
				new Person(child.person().givenName(), surname, child.gender(),
						child.skills(), race),
				child.getBirthDate(), child.getMother(), child.getFather());
		return new Laborer(head, cfg.laborer().e(), initNQty, 0, 0,
				cfg.laborer().savingsRate(), LaborerConfig.DEFAULT, bank, colony);
	}

	/**
	 * The explorer-expedition {@linkplain ExpeditionReturn reward} (commit 1 of the renewal loop,
	 * {@code docs/explorer-caravan.md}): each surviving returned peasant <b>leaves the pool and
	 * founds its own copper-banking {@link Laborer} household</b> — it "becomes banked", re-enters
	 * the wedding market and can bear children (the renewal the finite pool cannot provide).
	 * Deferred to end of step, like fission/ennoblement (the day's market has already cleared).
	 * <p>
	 * <i>The cash seed — selling the gathered cargo as a supply dump on the Enjoyment market, the
	 * ruler's tax, and ennobling the ablest returnee — is commit 2; here the new households open on
	 * the standard founding stock (no cash), so {@code cargoUnits} is unused until then.</i>
	 */
	@Override
	public void rewardReturn(Settlement home, List<Member> returnees, int cargoUnits) {
		for (Member returnee : returnees)
			colony.scheduleEndOfStepAction(() -> foundReturnedHousehold(returnee));
	}

	// end-of-step: a returned peasant leaves the pool and founds its own single-head laborer
	// household (it "becomes banked"), then undrafts. A no-op if the pool is gone or the peasant is
	// no longer in it (died on the march / already released). Reuses the fission founding (a fresh
	// dynasty surname, the standard opening stock); the cash seed from the cargo sale is commit 2.
	private void foundReturnedHousehold(Member returnee) {
		returnee.setDrafted(false);
		Retinue r = pool.get();
		if (r == null || !r.release(returnee))
			return; // not in the pool (died / already released) — nothing to found
		colony.scheduleAddAgent(buildFissionHousehold(returnee, copperBank.get(),
				SimulationHarness.REPLACEMENT_NECESSITY_STOCK));
	}

	/**
	 * Demote every <b>ruined</b> noble — one insolvent (a net debtor) for at least
	 * {@value #NOBLE_INSOLVENCY_GRACE_DAYS} consecutive days — back to a laborer, the
	 * converse of ennoblement. A step action registered for every ruler-bearing colony;
	 * the actual demotion is deferred to end of step (the noble's offers must clear
	 * before its account moves), exactly as ennoblement is.
	 */
	private void demoteRuinedNobles() {
		for (Agent a : colony.getAgents())
			if (a instanceof Noble n && n.isAlive() && n
					.getConsecutiveInsolventDays() >= NOBLE_INSOLVENCY_GRACE_DAYS)
				colony.scheduleEndOfStepAction(() -> demoteRuinedNoble(n));
	}

	// demote one ruined noble (deferred to end of step): hand its holdings to another
	// living noble first (a laborer owns none), then reform it down the rank ladder
	// HOLDING -> HOUSEHOLD (skipping the unrealized CARAVAN rung). If it is the
	// colony's only noble its firms go unowned until the next charter's no-owner
	// fallback re-ennobles an owner. Re-checks the trigger in case state changed
	// between the schedule and end of step.
	private void demoteRuinedNoble(Noble ruined) {
		if (!ruined.isAlive() || ruined
				.getConsecutiveInsolventDays() < NOBLE_INSOLVENCY_GRACE_DAYS)
			return;
		Noble heir = leastLoadedNobleExcept(ruined);
		if (heir != null)
			ruined.transferPropertyTo(heir);
		demote(ruined);
	}

	// the living noble currently owning the fewest firms, or null if none
	Noble leastLoadedNoble() {
		Noble best = null;
		for (Agent a : colony.getAgents())
			if (a instanceof Noble noble && noble.isAlive() && (best == null
					|| noble.getFirmCount() < best.getFirmCount()))
				best = noble;
		return best;
	}

	// the living noble other than `excluded` owning the fewest firms, or null if none
	private Noble leastLoadedNobleExcept(Noble excluded) {
		Noble best = null;
		for (Agent a : colony.getAgents())
			if (a instanceof Noble n && n != excluded && n.isAlive() && (best == null
					|| n.getFirmCount() < best.getFirmCount()))
				best = n;
		return best;
	}

	// the more ennoblable of two laborers: higher head SOCIAL, the younger
	// (smaller age) breaking a tie
	private static boolean moreEnnoblable(Laborer candidate, Laborer incumbent) {
		int ci = candidate.getHead().skills().level(Skill.SOCIAL);
		int ii = incumbent.getHead().skills().level(Skill.SOCIAL);
		if (ci != ii)
			return ci > ii;
		return candidate.getAgeYears() < incumbent.getAgeYears();
	}
}
