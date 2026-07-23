package com.civstudio.simulation;

import java.time.DayOfWeek;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import com.civstudio.agent.Agent;
import com.civstudio.agent.ExpeditionReturn;
import com.civstudio.agent.Granary;
import com.civstudio.agent.Household;
import com.civstudio.agent.RankFactory;
import com.civstudio.agent.Member;
import com.civstudio.agent.Rank;
import com.civstudio.agent.RankLadder;
import com.civstudio.agent.Retinue;
import com.civstudio.agent.firm.StrategicFirm;
import com.civstudio.agent.laborer.Laborer;
import com.civstudio.agent.laborer.LaborerConfig;
import com.civstudio.agent.noble.Noble;
import com.civstudio.agent.noble.NobleConfig;
import com.civstudio.agent.ruler.Ruler;
import com.civstudio.bank.Bank;
import com.civstudio.market.ConsumerGoodMarket;
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

	// renewal-loop tallies over the run (the expedition reward, for the Expeditions printer):
	// bands returned and rewarded; households founded from returnees; returnees ennobled to lead
	// (no abler noble existed); returns an existing abler noble led (no new noble minted)
	private long expeditionReturns;
	private long expeditionFounded;
	private long expeditionEnnobled;
	private long expeditionNobleLed;

	/** The numbers this colony runs on — its own {@code (era, race)} cell, not the run's. */
	private com.civstudio.era.Era.Economy econ() {
		return colony.getEconomy();
	}

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
		// than econ().targetNobles() living nobles, the ruler raises the ablest laborer
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

	/** Bands returned and rewarded over the run (the renewal loop). */
	long getExpeditionReturns() {
		return expeditionReturns;
	}

	/** Households founded from returned explorer peasants over the run. */
	long getExpeditionFounded() {
		return expeditionFounded;
	}

	/** Returnees ennobled to lead (no abler noble existed) over the run. */
	long getExpeditionEnnobled() {
		return expeditionEnnobled;
	}

	/** Returns an existing abler noble led (no new noble minted) over the run. */
	long getExpeditionNobleLed() {
		return expeditionNobleLed;
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
		// reform the chosen laborer up the rank ladder to HOLDING explicitly (R1 —
		// docs/rank-ladder-improvements.md): the HOLDING factory re-banks it in silver,
		// carries its balances and members over, and the ladder closes the old account and
		// swaps the agent — money conserved, the laborer's surname staying in use with the
		// noble that adopted its head. A targeted reform (not the adjacency walk) so it is
		// unaffected when the intermediate CARAVAN rung is realized. Safe here: this runs
		// only as a deferred end-of-step action (the laborer's offers have cleared).
		return (Noble) rankLadder().reformTo(best, Rank.HOLDING);
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
		// a ruined noble (HOLDING) reforms down to a laborer (HOUSEHOLD) — the only demotion
		// wired today. Targeted (not the adjacency walk) so it stays correct when the
		// intermediate CARAVAN rung is realized (R1, docs/rank-ladder-improvements.md).
		return rankLadder().reformTo(household, Rank.HOUSEHOLD);
	}

	/**
	 * Register a {@link RankFactory} for one realized {@link Rank} on this colony's ladder — the
	 * seam the harness uses to add the <b>head</b> factories it owns the parameters for (the gold
	 * treasury, tax rates), e.g. {@link Rank#CITY} building a {@code Mayor} (R2,
	 * {@code docs/rank-ladder-improvements.md}). The laborer&harr;noble factories are registered
	 * internally (see {@link #rankLadder()}).
	 *
	 * @param rank    the rank the factory realizes
	 * @param factory how to reform a household into that rank
	 */
	public void registerRankFactory(Rank rank, RankFactory factory) {
		rankLadder().register(rank, factory);
	}

	/**
	 * Reform <tt>household</tt> into an explicit target {@link Rank} on this colony's ladder — the
	 * head-rank reforms the tier crossings drive (Captain&rarr;Ruler&rarr;Mayor). Delegates to
	 * {@link RankLadder#reformTo}; {@code null} if the target is unrealized. Must run from an
	 * end-of-step context (the household's offers must have cleared).
	 *
	 * @param household the household to reform
	 * @param target    the rank it becomes
	 * @return the reformed household, or {@code null} if the target has no factory
	 */
	public Household reformTo(Household household, Rank target) {
		return rankLadder().reformTo(household, target);
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
				Laborer laborer = new Laborer(head, econ().laborer().e(),
						SimulationHarness.REPLACEMENT_NECESSITY_STOCK, estate.checking(),
						estate.savings(), econ().laborer().savingsRate(),
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
	 * Maintain the aristocracy at {@code econ().targetNobles()} by ennoblement: a step
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
		if (nobles >= econ().targetNobles())
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
		// the housing gate (build economy B3): only a housed parent fissions — the child
		// household starts homeless and becomes the next construction wave
		if (!parent.housedForGate())
			return;
		Granary granary = colony.getGranary();
		if (granary == null || granary.getStock() < FISSION_NECESSITY_DOWRY)
			return; // no strategic store to dower a new household — fission waits
		Member child = parent.emancipateChild(colony.getDate());
		if (child == null)
			return;
		double dowry = granary.drawStock(FISSION_NECESSITY_DOWRY);
		colony.scheduleAddAgent(
				buildFissionHousehold(child, parent.getBank(), dowry, 0));
		fissionCount++;
	}

	// build a new laborer household headed by an emancipated child (or a returned explorer
	// peasant): rename it under a fresh dynasty surname (its parent's is still in use), keeping its
	// given name, gender, skills, race, age and parentage; it opens with the food dowry it carried
	// and initCheckingBal of cash (0 for a fission child — it earns its keep on the labor market;
	// the FX-converted haul share for a returned explorer, see foundReturnedHousehold)
	private Laborer buildFissionHousehold(Member child, Bank bank, double initNQty,
			double initCheckingBal) {
		Race race = child.race();
		String surname = colony.getNames().nextDynastyName(race);
		Member head = new Member(
				new Person(child.person().givenName(), surname, child.gender(),
						child.skills(), race),
				child.getBirthDate(), child.getMother(), child.getFather());
		Laborer household = new Laborer(head, econ().laborer().e(), initNQty,
				initCheckingBal, 0, econ().laborer().savingsRate(), LaborerConfig.DEFAULT,
				bank, colony);
		// a home-plots colony seats the new (fission-born or returned-explorer) household on a
		// plot it farms for subsistence food, landless (null) if the site is full. See
		// docs/plot-working-plan.md P1.
		if (cfg.homePlots())
			household.setHomePlot(colony.claimHomePlot());
		return household;
	}

	/**
	 * The explorer-expedition {@linkplain ExpeditionReturn reward} — the renewal loop of {@code
	 * docs/explorer-caravan.md} (commit 2, the cash reward on top of commit 1's structural core):
	 * <ol>
	 * <li>the band's gathered non-food {@code cargoUnits} are <b>sold as a real Enjoyment supply
	 * dump</b> — the ruler holds the haul and posts it to the enjoyment market, tanking the price
	 * (so laborers afford more enjoyment for a while) and recouping the crown when the offer clears
	 * next step;</li>
	 * <li>the haul's value at today's enjoyment price seeds the returnees; the crown keeps a {@link
	 * SimulationConfig#expeditionTaxRate() tax} cut and the rest is shared out;</li>
	 * <li>the <b>ablest</b> returnee is <b>ennobled</b> into a silver-banking {@link Noble} with its
	 * {@link SimulationConfig#expeditionNobleShare() share} of the taxed-net proceeds;</li>
	 * <li>the <b>other</b> returnees each <b>leave the pool and found a copper-banking {@link
	 * Laborer} household</b> seeded with an equal split of the remainder — each "becomes banked",
	 * re-enters the wedding market and can bear children (the renewal the finite pool cannot
	 * provide).</li>
	 * </ol>
	 * The distribution is deferred to end of step, like fission/ennoblement (the day's market has
	 * already cleared). Money is conserved: each cash seed is drawn out of the crown treasury (which
	 * recoups it via the dumped haul), the ruler bearing the currency-exchange fee on the payout.
	 */
	@Override
	public void rewardReturn(Settlement home, List<Member> returnees, int cargoUnits) {
		double proceeds = dumpHaul(cargoUnits);
		colony.scheduleEndOfStepAction(() -> distributeReturn(returnees, proceeds));
	}

	// Sell `cargoUnits` of gathered non-food cargo as a ruler-supplied Enjoyment supply dump and
	// return the haul's value at today's enjoyment price (the proceeds the returnees are seeded
	// from). The ruler holds the haul and posts a matching sell offer that clears next step (the
	// offer persists across the day the reward fires — clear() only empties sellOffers at its own
	// end). Returns 0 (no cash seed — the returnees fall back to the standard founding stock) when
	// the colony has no ruler / no enjoyment market, or the band gathered nothing.
	private double dumpHaul(int cargoUnits) {
		Ruler ruler = colony.getRuler();
		ConsumerGoodMarket eMkt = (ConsumerGoodMarket) colony.getMarket("Enjoyment");
		if (ruler == null || eMkt == null || cargoUnits <= 0)
			return 0;
		double price = eMkt.getLastMktPrice();
		if (!Double.isFinite(price) || price <= 0)
			price = eMkt.getInitialPrice(); // pre-first-clear fallback
		ruler.getGood("Enjoyment").increase(cargoUnits);
		eMkt.addSellOffer(ruler, cargoUnits);
		return cargoUnits * price;
	}

	// end-of-step: release every surviving returnee from the pool, award the leader's cut of the
	// haul, and found copper-banking laborer households from the returnees — each seeded with its
	// share of the taxed-net proceeds. A returnee no longer in the pool (died on the march / already
	// released) is skipped; a no-op if none survive to release.
	//
	// The <b>leader</b> takes the noble's share: if the colony already has a living noble abler
	// (higher head SOCIAL) than the ablest returnee, that noble is deemed to have led the expedition
	// and is paid the cut — <b>no new noble is minted</b>, so the aristocracy does not balloon (the
	// bug the unconditional-ennoblement first cut caused — 27 nobles vs 11 laborers for seed
	// 7654321). Only when no abler noble exists is the ablest returnee ennobled to lead, bootstrapping
	// the aristocracy early; the rest always found laborer households.
	private void distributeReturn(List<Member> returnees, double proceeds) {
		Retinue r = pool.get();
		List<Member> released = new ArrayList<>();
		for (Member returnee : returnees) {
			returnee.setDrafted(false);
			if (r != null && r.release(returnee))
				released.add(returnee);
		}
		if (released.isEmpty())
			return; // no survivor left the pool — nothing to found

		// the crown taxes a cut of the haul (it keeps it — the ruler is the seller); the rest is
		// shared out, the leader taking the noble's share and the returnees founding households
		double distributable = proceeds * (1 - cfg.expeditionTaxRate());
		double leaderCash = distributable * cfg.expeditionNobleShare();
		Member ablest = ablestBySocial(released);
		Noble leader = ablerNoble(ablest);

		List<Member> founders = new ArrayList<>(released);
		if (leader != null) {
			// an abler noble led the expedition — it takes the leader's cut; every returnee founds a
			// laborer household (no new noble is minted)
			payLeaderShare(leader, leaderCash);
			expeditionNobleLed++;
		} else {
			// no abler noble to lead — ennoble the ablest returnee into the leading noble, seeded
			// with the leader's cut; the rest found households
			colony.scheduleAddAgent(ennobleReturnee(ablest, leaderCash));
			founders.remove(ablest);
			expeditionEnnobled++;
		}

		// split the remainder equally among the founding returnees (if the ablest was ennobled and
		// was the only survivor, the remainder simply stays with the crown)
		double each = founders.isEmpty() ? 0 : (distributable - leaderCash) / founders.size();
		for (Member founder : founders)
			colony.scheduleAddAgent(foundReturnedHousehold(founder, each));
		expeditionReturns++;
		expeditionFounded += founders.size();
	}

	// the living colony noble abler (strictly higher head SOCIAL) than `candidate`, the ablest among
	// them, or null if no noble out-skills the candidate — the leader of a returning expedition when
	// one exists (so an expedition rewards an existing noble rather than always minting a fresh one)
	private Noble ablerNoble(Member candidate) {
		int bar = candidate.skills().level(Skill.SOCIAL);
		Noble best = null;
		for (Agent a : colony.getAgents())
			if (a instanceof Noble n && n.isAlive()) {
				int s = n.getHead().skills().level(Skill.SOCIAL);
				if (s > bar && (best == null
						|| s > best.getHead().skills().level(Skill.SOCIAL)))
					best = n;
			}
		return best;
	}

	// pay the expedition's noble leader its cut of the haul out of the crown treasury (gold ->
	// silver; the ruler bears the FX + txn fee on the payout, the noble the silver FX fee on receipt),
	// recorded as the noble's income. Money is conserved (the crown recoups via the dumped haul).
	private void payLeaderShare(Noble leader, double cash) {
		double paid = drawFromTreasury(cash);
		if (paid > 0)
			leader.getBank().credit(leader.getID(), paid, Bank.PRIIC);
	}

	// found one returned peasant's copper-banking laborer household, seeded with `cash` drawn from
	// the crown treasury (gold -> copper; the ruler bears the FX + transaction fee) plus the
	// standard food stock — it "becomes banked". Reuses the fission founding (a fresh dynasty
	// surname). The caller has already released it from the pool.
	private Laborer foundReturnedHousehold(Member returnee, double cash) {
		double seed = drawFromTreasury(cash);
		return buildFissionHousehold(returnee, copperBank.get(),
				SimulationHarness.REPLACEMENT_NECESSITY_STOCK, seed);
	}

	// ennoble one returned peasant into a silver-banking noble seeded with `cash` drawn from the
	// crown treasury (gold -> silver; the ruler bears the FX + transaction fee), keeping its
	// identity under a fresh dynasty surname (like buildFissionHousehold). The caller has already
	// released it from the pool.
	private Noble ennobleReturnee(Member returnee, double cash) {
		double seed = drawFromTreasury(cash);
		Race race = returnee.race();
		String surname = colony.getNames().nextDynastyName(race);
		Member head = new Member(
				new Person(returnee.person().givenName(), surname, returnee.gender(),
						returnee.skills(), race),
				returnee.getBirthDate(), returnee.getMother(), returnee.getFather());
		return new Noble(head, seed, 0, nobleConfig, silverBank.get(), colony);
	}

	// pay `cash` out of the crown treasury (the ruler's gold bank) so a returnee household can open
	// with it as a fresh endowment — the ruler bears the currency-exchange + transaction fee on the
	// payout (gold -> the recipient's currency), and money is conserved (the ruler recoups the cash
	// via the haul dumped onto the enjoyment market). Returns the amount seeded, 0 when there is no
	// ruler to draw on or nothing to pay.
	private double drawFromTreasury(double cash) {
		Ruler ruler = colony.getRuler();
		if (ruler == null || cash <= 0)
			return 0;
		ruler.getBank().withdraw(ruler.getID(), cash);
		return cash;
	}

	// the ablest of a set of returned peasants by head SOCIAL skill (the ennoblement criterion, as
	// for a laborer — see moreEnnoblable), the younger breaking a tie
	private static Member ablestBySocial(List<Member> members) {
		Member best = null;
		for (Member m : members)
			if (best == null || moreSocial(m, best))
				best = m;
		return best;
	}

	// the more ennoblable of two peasants: higher SOCIAL, the younger (later birth date)
	// breaking a tie
	private static boolean moreSocial(Member candidate, Member incumbent) {
		int ci = candidate.skills().level(Skill.SOCIAL);
		int ii = incumbent.skills().level(Skill.SOCIAL);
		if (ci != ii)
			return ci > ii;
		return candidate.getBirthDate().isAfter(incumbent.getBirthDate());
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
