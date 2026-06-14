package eos.agent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import eos.agent.ruler.Ruler;
import eos.bank.Bank;
import eos.good.Good;
import eos.good.Necessity;
import eos.market.ConsumerGoodMarket;
import eos.market.Demand;
import eos.mortality.Demography;
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

	// days of food the pool tries to keep in its larder, so a transient necessity
	// shortfall (the sector takes a few steps to raise output/price for the new
	// mouths) draws the buffer down rather than starving peasants outright
	private static final int BUFFER_DAYS = 30;

	private final List<Member> peasants = new ArrayList<>();

	// the pool's larder: a stock of necessity it eats from and refills on the
	// market (assigned once in the constructor; not final only so the demandForN
	// field initializer below may reference it)
	private Necessity necessity;
	private final ConsumerGoodMarket nMkt;

	// necessity eaten last step, peasants lost to starvation last step, and the
	// cumulative relief cost billed to the Ruler — all for reporting
	@Getter
	private double lastConsumed;
	@Getter
	private long lastStarved;
	@Getter
	private double totalBilledToRuler;

	// buy enough to refill the larder toward BUFFER_DAYS of food for the current
	// head-count (price-inelastic: peasant food is essential and Ruler-funded)
	private final Demand demandForN = price -> Math
			.max(0, peasants.size() * BUFFER_DAYS - necessity.getQuantity());

	/**
	 * Create the pool, open its (copper) account, and seed it with
	 * {@code initialSize} peasants plus a day's food so they can eat on step 0.
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
		// seed the larder with a full buffer (like a laborer's starting necessity
		// stock), so the pool can feed its peasants from step 0 and ride out the
		// necessity sector's adjustment to the new demand
		this.necessity = new Necessity((double) initialSize * BUFFER_DAYS);
		seed(initialSize);
	}

	private void seed(int n) {
		Settlement colony = getColony();
		Demography demography = colony.getDemography();
		for (int i = 0; i < n; i++) {
			// all on the demographic / naming RNGs (never the economic stream)
			int ageDays =
					demography.sampleInitialAgeDays(colony.getMeanInitAgeYears());
			SkillTracker skills = demography.newSkillTracker(colony.getMeanSkill());
			// surname-less while pooled; a dynasty surname is drawn at promotion
			Person p = new Person(colony.getNames().nextMaleName(), "")
					.withSkills(skills);
			peasants.add(new Member(p, colony.getDate().minusDays(ageDays)));
		}
	}

	/** Called by Settlement.newDay() in each step. */
	public void act() {
		Settlement colony = getColony();
		// the Ruler reimburses the pool's necessity spend so far (borrowing as
		// needed), landing the relief cost on the Ruler and returning the pool's
		// account to ~0 before interest is assessed this step
		billRuler();
		// old-age mortality, then skill decay for the survivors
		peasants.removeIf(
				m -> m.rollOldAgeDeath(colony.getDemography(), colony.getDate()));
		for (Member m : peasants)
			m.skills().tick();
		// eat one necessity per peasant; the unfed starve
		feed();
		// buy enough to feed the pool next step (funded by the overdraft billRuler
		// reconciles); peasants never buy enjoyment
		if (!peasants.isEmpty())
			nMkt.addBuyOffer(this, demandForN);
	}

	private void feed() {
		int alive = peasants.size();
		lastConsumed = necessity.decrease(alive);
		// a peasant starves only when its whole ration is missing; fractional
		// shortfalls are absorbed (the larder/buffer carries them)
		lastStarved = Math.max(0, (long) Math.floor(alive - lastConsumed));
		if (lastStarved > 0) {
			// the least skilled starve first; the abler are kept for promotion
			peasants.sort(Comparator.comparingInt(m -> m.skills().overallLevel()));
			for (long i = 0; i < lastStarved && !peasants.isEmpty(); i++)
				peasants.remove(0);
			log.info(lastStarved + " peasant(s) starved (pool now "
					+ peasants.size() + ")");
		}
	}

	private void billRuler() {
		Ruler ruler = getColony().getRuler();
		// the ruler may have died of old age earlier in this step's agent loop (the
		// pool acts after it) and not yet been succeeded; skip billing — the deficit
		// carries to next step, when the heir holds the colony's ruler reference
		if (ruler == null || !ruler.isAlive())
			return;
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
