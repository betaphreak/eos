package eos.agent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import eos.agent.firm.BuilderFirm;
import eos.agent.ruler.Ruler;
import eos.bank.Bank;
import eos.good.Good;
import eos.good.Necessity;
import eos.good.RationSize;
import eos.market.ConsumerGoodMarket;
import eos.market.Demand;
import eos.market.LaborMarket;
import eos.mortality.Demography;
import eos.name.Gender;
import eos.name.Person;
import eos.settlement.Settlement;
import eos.skill.SkillTracker;
import lombok.Getter;
import lombok.extern.java.Log;

/**
 * The <b>pool of peasants</b>: a reserve of people who have a household no longer
 * (or not yet) — they carry skills and an age but hold no wage, own nothing, and
 * never consume enjoyment. Each is a {@link Member} (the same name-and-skills unit
 * a household holds), so the pool reuses the per-member ageing/mortality machinery.
 * <p>
 * The pool is the settlement's poor relief: the {@link Ruler} feeds it. Each step
 * the pool eats one {@link Necessity} per peasant, buying its food on the necessity
 * market like any consumer; the cost is <b>billed to the Ruler</b>, who overdraws
 * (borrows) to cover it (see {@link #billRuler()}). Peasants therefore starve only
 * when the market itself cannot supply them, never from the Ruler running short.
 * While pooled a peasant ages, can die of old age, and its skills decay — so with
 * no inflow the pool simply <b>drains</b> over time (promotion out of the pool is a
 * later phase; see {@code docs/peasant-pool.md}).
 * <p>
 * The pool is seeded at founding (its members drawn on the demographic and naming
 * RNGs, never the economic stream) and is surname-less while pooled — a dynasty
 * surname is drawn only when a peasant is promoted into a household.
 */
@Log
public class PeasantPool extends Agent {

	// days of food the pool keeps in its larder per peasant — a proper personal
	// larder. The larder is sized at BUFFER_DAYS per pooled peasant; when a peasant
	// is promoted into a laborer household it takes this much necessity with it (see
	// drawPromotionStock), so the food is conserved rather than created.
	private static final int BUFFER_DAYS = 15;

	// peasants on relief eat a reduced ration, not a working laborer's full
	// (LAVISH) unit a day: the standing reserve is relief, not a wage. Keeping the
	// reserve's consumption modest is part of what lets a colony feed a reserve on
	// top of its labor force without the extra mouths starving the workforce out of
	// the food market (the other part is enough necessity firms to supply it).
	private static final RationSize RELIEF_RATION = RationSize.SIMPLE;

	// relief food the pool buys per peasant per step, expressed as a money budget so
	// the pool's necessity demand is price-sensitive (quantity = budget/price), like
	// a laborer's. Unlike a laborer the pool has no guaranteed minimum, so as food
	// grows scarce (and its price climbs) the pool buys less and yields it to the
	// working population — the reserve subsists on the surplus rather than out-bidding
	// the workforce into starvation. Sized so that at a normal necessity price the
	// pool can buy roughly the relief ration.
	private static final double RELIEF_BUDGET_PER_PEASANT = 0.6;

	private final List<Member> peasants = new ArrayList<>();

	// the pool's larder: a stock of necessity it eats from and refills on the
	// market (assigned once in the constructor; not final only so the demandForN
	// field initializer below may reference it)
	private Necessity necessity;
	private final ConsumerGoodMarket nMkt;

	// the builder's dedicated labor market, if the colony has a builder (else null):
	// the pool supplies its peasants here, with their wages routed to the ruler
	private final LaborMarket builderLaborMkt;

	// necessity eaten last step, peasants lost to starvation last step, and the
	// cumulative relief cost billed to the Ruler — all for reporting
	@Getter
	private double lastConsumed;
	@Getter
	private long lastStarved;
	@Getter
	private double totalBilledToRuler;

	// peasants promoted out of the pool into laborer households over its life
	@Getter
	private long promotedCount;

	// peasants wed out of the pool into households as spouses over its life
	@Getter
	private long marriedOutCount;

	// immigrants recruited into the pool (gold-funded, when wedding demand goes
	// unmet) over its life
	@Getter
	private long immigrantCount;

	// buy relief food for the pool: refill the larder toward a BUFFER_DAYS buffer at
	// the reduced relief ration, but only up to a price-sensitive money budget, so
	// the pool defers to the working population when food is scarce (see
	// RELIEF_BUDGET_PER_PEASANT)
	private final Demand demandForN = price -> {
		// refill toward a full BUFFER_DAYS-per-peasant larder (the ration a promoted
		// peasant carries out), so the pool buys daily to replace what is eaten...
		double larderRoom = Math.max(0,
				peasants.size() * BUFFER_DAYS - necessity.getQuantity());
		// ...but only up to a price-sensitive money budget, so it defers to the
		// working population when food is scarce
		double reliefBudget = peasants.size() * RELIEF_BUDGET_PER_PEASANT;
		return Math.min(reliefBudget / price, larderRoom);
	};

	/**
	 * Create the pool, open its (copper) account, and seed it with
	 * {@code initialSize} peasants plus a {@value #BUFFER_DAYS}-day larder per
	 * peasant so they can eat from step 0 (and carry their ration with them when
	 * promoted).
	 *
	 * @param initialSize
	 *            number of peasants to seed
	 * @param bank
	 *            the (copper) bank the pool transacts through
	 * @param colony
	 *            the colony this pool belongs to
	 */
	public PeasantPool(int initialSize, Bank bank, Settlement colony) {
		super(bank, colony);
		setName("Peasant Pool");
		bank.openAcct(getID(), 0, 0);
		this.nMkt = (ConsumerGoodMarket) colony.getMarket("Necessity");
		// the builder hires peasants from this market (null if the colony has no
		// builder, in which case the pool supplies no labor)
		this.builderLaborMkt =
				(LaborMarket) colony.getMarket(BuilderFirm.LABOR_MARKET);
		// the wedding market draws its spouses from this pool; register so it can
		// (a no-op for a colony without one)
		eos.market.WeddingMarket weddingMkt =
				(eos.market.WeddingMarket) colony.getMarket("Wedding");
		if (weddingMkt != null)
			weddingMkt.setPool(this);
		// seed the larder with a full per-peasant buffer, so the pool can feed its
		// peasants from step 0 and ride out the necessity sector's adjustment to the
		// new demand (and a promoted peasant takes its BUFFER_DAYS ration with it)
		this.necessity = new Necessity((double) initialSize * BUFFER_DAYS);
		seed(initialSize);
	}

	private void seed(int n) {
		for (int i = 0; i < n; i++)
			peasants.add(newPeasant(false));
	}

	// build one pooled peasant (not yet added to the list): roll its gender, age,
	// skills and given name on the demographic / naming RNGs (never the economic
	// stream). A founding/relief peasant draws a founding-age spread; an immigrant
	// recruit (young == true) draws a young working age (see addImmigrant). The
	// draw order — gender, age, skills, name — matches the original seed loop, so
	// the founding pool stays reproducible.
	private Member newPeasant(boolean young) {
		Settlement colony = getColony();
		Demography demography = colony.getDemography();
		Gender gender = demography.sampleGender();
		int ageDays = young
				? demography.sampleYoungAdultAgeDays()
				: demography.sampleInitialAgeDays(colony.getMeanInitAgeYears());
		SkillTracker skills =
				demography.newSkillTracker(colony.getMeanSkill(gender));
		// surname-less while pooled; a dynasty surname is drawn at promotion (or the
		// household surname at marriage). The given name matches the rolled gender.
		String givenName = gender == Gender.FEMALE
				? colony.getNames().nextFemaleName()
				: colony.getNames().nextMaleName();
		Person p = new Person(givenName, "", gender, skills);
		return new Member(p, colony.getDate().minusDays(ageDays));
	}

	/**
	 * Recruit one <b>immigrant</b> into the pool — a young, fresh adult (random
	 * gender, skills around the gendered colony mean) drawn on the demographic /
	 * naming RNGs. Called by the {@link eos.market.WeddingMarket} when a weekend's
	 * wedding demand goes unmet, so the pool has an opposite-gender candidate next
	 * time; the gold that pays for it leaves the colony (see the market).
	 *
	 * @return the recruited peasant, now in the pool
	 */
	public Member addImmigrant() {
		Member m = newPeasant(true);
		peasants.add(m);
		immigrantCount++;
		return m;
	}

	/**
	 * Remove up to {@code requested} units of necessity from the pool's larder — the
	 * ration a peasant carries with it when promoted into a laborer household — and
	 * return the amount actually drawn (less than requested only if the larder is
	 * short). Conserves food: the promoted laborer's opening stock comes out of the
	 * pool rather than being created.
	 *
	 * @param requested
	 *            necessity units the promoted peasant should take
	 * @return the amount actually removed from the larder
	 */
	public double drawPromotionStock(double requested) {
		double drawn = Math.min(Math.max(0, requested), necessity.getQuantity());
		necessity.decrease(drawn);
		return drawn;
	}

	/** Called by Settlement.newDay() in each step. */
	public void act() {
		Settlement colony = getColony();
		// the ruler may have died of old age earlier in this step's agent loop (the
		// pool acts after it) and not yet been succeeded; with no live patron the
		// pool neither bills nor supplies labor this step (deficit/work carry over)
		Ruler ruler = colony.getRuler();
		boolean rulerLive = ruler != null && ruler.isAlive();

		// the ruler reimburses the pool's necessity spend so far (borrowing as
		// needed), landing the relief cost on it and returning the pool's account to
		// ~0 before interest is assessed this step
		if (rulerLive)
			billRuler(ruler);
		// old-age mortality, then skill decay for the survivors
		peasants.removeIf(
				m -> m.rollOldAgeDeath(colony.getDemography(), colony.getDate()));
		for (Member m : peasants)
			m.skills().tick();
		// eat one necessity per peasant; the unfed starve
		feed();
		// supply the surviving peasants to the builder as its exclusive workforce,
		// routing their wages to the ruler (their patron); the builder hires as many
		// as its budget allows and none when idle
		if (builderLaborMkt != null && rulerLive)
			for (Member m : peasants)
				builderLaborMkt.addEmployee(ruler.getID(), ruler.getBank(), 1.0,
						m.skills());
		// buy enough to feed the pool next step (funded by the overdraft billRuler
		// reconciles); peasants never buy enjoyment
		if (!peasants.isEmpty())
			nMkt.addBuyOffer(this, demandForN);
	}

	private void feed() {
		int alive = peasants.size();
		// peasants eat the relief ration, not a full unit each
		double ration = RELIEF_RATION.perDay();
		double wanted = alive * ration;
		lastConsumed = necessity.decrease(wanted);
		// a peasant starves only when even its relief ration is missing; fractional
		// shortfalls are absorbed (the larder/buffer carries them)
		double shortfall = wanted - lastConsumed;
		lastStarved = Math.max(0, (long) Math.floor(shortfall / ration));
		if (lastStarved > 0) {
			// the least skilled starve first; the abler are kept for promotion
			peasants.sort(Comparator.comparingInt(m -> m.skills().overallLevel()));
			for (long i = 0; i < lastStarved && !peasants.isEmpty(); i++)
				peasants.remove(0);
			log.info(lastStarved + " peasant(s) starved (pool now "
					+ peasants.size() + ")");
		}
	}

	private void billRuler(Ruler ruler) {
		Bank bank = getBank();
		double deficit = -(bank.getChecking(getID()) + bank.getSavings(getID()));
		if (deficit > 1e-9) {
			// the Ruler pays (overdrawing into a loan if its treasury is short);
			// the pool is reimbursed and the overdraft cleared (checking -> savings)
			ruler.getBank().withdraw(ruler.getID(), deficit);
			bank.credit(getID(), deficit, Bank.OTHER);
			bank.deposit(getID(), deficit);
			totalBilledToRuler += deficit;
		}
	}

	@Override
	public Good getGood(String goodName) {
		return "Necessity".equals(goodName) ? necessity : null;
	}

	/**
	 * Remove and return the highest-overall-skill peasant — the one a {@link
	 * eos.agent.ruler.Ruler} promotes into a laborer household (merit-based social
	 * mobility). Returns {@code null} when the pool is empty (then no replacement is
	 * produced and the labor force shrinks — the pool drains with no inflow yet).
	 *
	 * @return the ablest peasant, removed from the pool, or {@code null} if empty
	 */
	public Member promoteHighestSkilled() {
		Member best = null;
		for (Member m : peasants)
			if (best == null
					|| m.skills().overallLevel() > best.skills().overallLevel())
				best = m;
		if (best != null) {
			peasants.remove(best);
			promotedCount++;
		}
		return best;
	}

	/**
	 * The best peasant of the given gender to take as a spouse — the highest
	 * {@linkplain SkillTracker#overallLevel() overall skill}, the younger one (the
	 * more recent birth date) breaking a tie — without removing it. Returns {@code
	 * null} when no peasant of that gender remains. Used by the {@link
	 * eos.market.WeddingMarket} to choose (and price) a match before committing.
	 *
	 * @param gender
	 *            the gender of spouse sought (the opposite of the head's)
	 * @return the ablest, then youngest, peasant of that gender, or {@code null}
	 */
	public Member bestSpouseCandidate(Gender gender) {
		Member best = null;
		for (Member m : peasants) {
			if (m.gender() != gender)
				continue;
			if (best == null) {
				best = m;
				continue;
			}
			int level = m.skills().overallLevel();
			int bestLevel = best.skills().overallLevel();
			if (level > bestLevel || (level == bestLevel
					&& m.getBirthDate().isAfter(best.getBirthDate())))
				best = m;
		}
		return best;
	}

	/**
	 * Remove a peasant wed out of the pool as a spouse (see {@link
	 * #bestSpouseCandidate(Gender)}); the household keeps the {@link Member}.
	 *
	 * @param peasant
	 *            the peasant to remove
	 * @return true if it was in the pool and removed
	 */
	public boolean removeForMarriage(Member peasant) {
		boolean removed = peasants.remove(peasant);
		if (removed)
			marriedOutCount++;
		return removed;
	}

	/** @return the number of peasants currently in the pool */
	public int size() {
		return peasants.size();
	}

	/** @return the average overall skill of the pooled peasants (0 if empty) */
	public double avgSkill() {
		if (peasants.isEmpty())
			return 0;
		double sum = 0;
		for (Member m : peasants)
			sum += m.skills().overallLevel();
		return sum / peasants.size();
	}

	/** @return the average age in years of the pooled peasants (0 if empty) */
	public double avgAgeYears() {
		if (peasants.isEmpty())
			return 0;
		double sum = 0;
		for (Member m : peasants)
			sum += m.getAgeYears(getColony().getDate());
		return sum / peasants.size();
	}
}
