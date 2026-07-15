package com.civstudio.agent;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import com.civstudio.agent.firm.BuilderFirm;
import com.civstudio.agent.ruler.Ruler;
import com.civstudio.market.WeddingMarket;
import com.civstudio.race.Race;
import com.civstudio.bank.Bank;
import com.civstudio.good.Good;
import com.civstudio.good.Necessity;
import com.civstudio.good.RationSize;
import com.civstudio.market.ConsumerGoodMarket;
import com.civstudio.market.Demand;
import com.civstudio.market.LaborMarket;
import com.civstudio.mortality.Demography;
import com.civstudio.name.Gender;
import com.civstudio.name.Person;
import com.civstudio.settlement.Settlement;
import com.civstudio.skill.ColumnSkillTracker;
import com.civstudio.skill.SkillColumns;
import com.civstudio.skill.SkillTracker;
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
public class Retinue extends Agent implements MarchFollowing {

	// the pool's tunable parameters: larder depth, relief budget, and relief ration
	// (see RetinueConfig). These together decide whether the reserve can feed itself
	// off the market once its opening larder is gone — the difference between a colony
	// that holds its population and one that starves its reserve into collapse.
	// (Not final only so the demandForN field initializer below may reference it, the
	// same reason `necessity` is not final.)
	private RetinueConfig config;

	private final List<Member> peasants = new ArrayList<>();

	// the pool's skills, stored struct-of-arrays (one row per peasant) rather than
	// twelve scattered objects per person: the daily decay sweep (tickAll) and the
	// skill reductions run over contiguous memory. Each peasant's Person carries a
	// ColumnSkillTracker view over its row; on the peasant leaving the pool the view
	// materializes into a standalone record-backed copy (see SkillColumns.remove),
	// so its skills travel intact into the household it is promoted or wed into. The
	// store stays in lockstep with `peasants`: every add to one adds to the other,
	// every removal frees the row.
	private final SkillColumns skillStore;

	// how this retinue is sustained each step — swapped on detach()/settle(). Composed
	// (Strategy) so the settled-relief and wandering behaviours stay separate rather
	// than branching a mode flag through act(); defaults to the settled colony reserve.
	private Provisioning provisioning = new Relief();

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

	// buy relief food for the pool: refill the larder toward the per-peasant buffer at
	// the reduced relief ration, but only up to a price-sensitive money budget, so
	// the pool defers to the working population when food is scarce (see RetinueConfig)
	private final Demand demandForN = price -> {
		// size the buy off the peasants actually present (drafted peasants are away on an
		// expedition and fed by their caravan, not the pool — docs/explorer-caravan.md)
		int present = presentSize();
		// refill toward a full bufferDays-per-peasant larder, so the pool buys daily
		// to replace what is eaten...
		double larderRoom = Math.max(0,
				present * config.bufferDays() - necessity.getQuantity());
		// ...but only up to a price-sensitive money budget, so it defers to the
		// working population when food is scarce
		double reliefBudget = present * config.reliefBudgetPerPeasant();
		return Math.min(reliefBudget / price, larderRoom);
	};

	/**
	 * Create the pool with the {@link RetinueConfig#DEFAULT default parameters}, open
	 * its (copper) account, and seed it with {@code initialSize} peasants plus a full
	 * per-peasant larder so they can eat from step 0.
	 *
	 * @param initialSize
	 *            number of peasants to seed
	 * @param bank
	 *            the (copper) bank the pool transacts through
	 * @param colony
	 *            the colony this pool belongs to
	 */
	public Retinue(int initialSize, Bank bank, Settlement colony) {
		this(initialSize, bank, colony, RetinueConfig.DEFAULT);
	}

	/**
	 * Create the pool with explicit {@code config}, open its (copper) account, and seed
	 * it with {@code initialSize} peasants plus a full per-peasant larder
	 * ({@link RetinueConfig#bufferDays()} days each) so they can eat from step 0.
	 *
	 * @param initialSize
	 *            number of peasants to seed
	 * @param bank
	 *            the (copper) bank the pool transacts through
	 * @param colony
	 *            the colony this pool belongs to
	 * @param config
	 *            the pool's tunable parameters
	 */
	public Retinue(int initialSize, Bank bank, Settlement colony,
			RetinueConfig config) {
		// seed the larder with a full per-peasant buffer, so the pool opens
		// well-provisioned and can feed its peasants from step 0 while the necessity
		// sector adjusts to the new demand
		this(bank, colony, (double) initialSize * config.bufferDays(), initialSize,
				config);
		seed(initialSize);
	}

	/**
	 * Create the pool by <b>adopting an existing band's people and larder</b> — the
	 * re-founding seam: a wandering {@link Caravan}'s following becomes a fresh
	 * settlement's labour reserve (see {@code docs/caravan.md}). The {@link Member}s
	 * carry over with their skills and ages; the larder is the band's carried food. A
	 * fresh {@code Retinue} is built (rather than re-binding the band's, whose colony
	 * is fixed at construction), so the people thread across the settle/unsettle hinge
	 * at the data level.
	 *
	 * @param members
	 *            the band's following, adopted as the new pool's peasants
	 * @param larder
	 *            the band's carried necessity, the new pool's opening larder
	 * @param bank
	 *            the (copper) bank the pool transacts through
	 * @param colony
	 *            the colony this pool belongs to
	 */
	public Retinue(List<Member> members, double larder, Bank bank,
			Settlement colony) {
		this(members, larder, bank, colony, RetinueConfig.DEFAULT);
	}

	/**
	 * Adopt an existing band's people and larder (see the {@link #Retinue(List, double,
	 * Bank, Settlement) default-config overload}) with explicit {@code config}.
	 *
	 * @param members
	 *            the band's following, adopted as the new pool's peasants
	 * @param larder
	 *            the band's carried necessity, the new pool's opening larder
	 * @param bank
	 *            the (copper) bank the pool transacts through
	 * @param colony
	 *            the colony this pool belongs to
	 * @param config
	 *            the pool's tunable parameters
	 */
	public Retinue(List<Member> members, double larder, Bank bank,
			Settlement colony, RetinueConfig config) {
		this(bank, colony, larder, members.size(), config);
		// re-home each adopted person's skills in this pool's columnar store
		for (Member m : members)
			peasants.add(adopt(m));
	}

	// shared construction: open the account, look up the markets the pool uses, size
	// the larder, and create the columnar skill store. The two public constructors
	// differ only in how the pool is peopled (fresh draws vs. an adopted band) and
	// its larder.
	private Retinue(Bank bank, Settlement colony, double larder, int capacity,
			RetinueConfig config) {
		super(bank, colony);
		this.config = config;
		this.skillStore = new SkillColumns(capacity);
		setName("Retinue");
		bank.openAcct(getID(), 0, 0);
		this.nMkt = (ConsumerGoodMarket) colony.getMarket("Necessity");
		// the builder hires peasants from this market (null if the colony has no
		// builder, in which case the pool supplies no labor)
		this.builderLaborMkt =
				(LaborMarket) colony.getMarket(BuilderFirm.LABOR_MARKET);
		// the wedding market draws its spouses from this pool; register so it can
		// (a no-op for a colony without one)
		WeddingMarket weddingMkt =
				(WeddingMarket) colony.getMarket("Wedding");
		if (weddingMkt != null)
			weddingMkt.setRetinue(this);
		this.necessity = new Necessity(larder);
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
		// roll the peasant's ancestry against the colony's race-mix; a mono-cultural
		// colony (every current scenario) has a degenerate mix, so this draws no RNG
		// and returns HUMAN — the gender/age/skills/name draw order is unchanged
		Race race = demography.sampleRace(colony.getRaceMix());
		int ageDays = young
				? demography.sampleYoungAdultAgeDays(race)
				: demography.samplePoolFoundingAgeDays(colony.getMeanInitAgeYears(), race);
		// draw the skills, then move them into the columnar store (consuming no RNG,
		// so the gender/age/skills/name draw order is unchanged and reproducible)
		SkillTracker seed = demography.newSkillTracker(colony.getMeanSkill(gender));
		ColumnSkillTracker view = skillStore.add(seed);
		// surname-less while pooled; a dynasty surname is drawn at promotion (or the
		// household surname at marriage). The given name matches the rolled gender and
		// is drawn from the peasant's own race's table.
		String givenName = gender == Gender.FEMALE
				? colony.getNames().nextFemaleName(race)
				: colony.getNames().nextMaleName(race);
		Person p = new Person(givenName, "", gender, view, race);
		return new Member(p, colony.getDate().minusDays(ageDays));
	}

	// re-home an existing person's skills into this pool's columnar store, returning
	// a Member identical in name, gender and age but whose skills are now a
	// column-backed view (its prior tracker is snapshotted in, so its level/XP carry
	// over). Used when a person enters the pool already formed — an adopted band at
	// re-founding, or a disbanding household's member at dissolution.
	private Member adopt(Member m) {
		ColumnSkillTracker view = skillStore.add(m.skills());
		Person p = new Person(m.person().givenName(), m.surname(), m.gender(), view,
				m.race());
		return new Member(p, m.getBirthDate());
	}

	// the column-backed skill view of a pooled peasant (every pooled peasant has
	// one); used to free its row from the store when it leaves the pool
	private static ColumnSkillTracker viewOf(Member m) {
		return (ColumnSkillTracker) m.skills();
	}

	/**
	 * Recruit one <b>immigrant</b> into the pool — a young, fresh adult (random
	 * gender, skills around the gendered colony mean) drawn on the demographic /
	 * naming RNGs. Called by the {@link WeddingMarket} when a weekend's
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
		// before feeding: settled mode bills the ruler for last step's relief spend;
		// wandering mode has no patron, so nothing (see the Provisioning strategies)
		provisioning.beforeFeeding(this);
		// old-age mortality (freeing each dead peasant's row), then a single columnar
		// decay sweep over the survivors' skills (both modes)
		Iterator<Member> it = peasants.iterator();
		while (it.hasNext()) {
			Member m = it.next();
			if (m.rollOldAgeDeath(colony.getDemography(), colony.getDate())) {
				skillStore.remove(viewOf(m));
				it.remove();
			}
		}
		skillStore.tickAll();
		// eat the current ration; the unfed starve
		feed();
		// after feeding: settled mode lends the builder its corvée labor and posts the
		// market buy offer for next step; wandering mode is marketless and idle
		provisioning.afterFeeding(this);
	}

	/**
	 * Switch this retinue to the detached <b>wandering</b> mode — a {@link Caravan} on
	 * the move: it eats the lean {@link MarchingCaravan#WANDERING_RATION} from its carried
	 * larder, with no market to restock on, no patron to bill, and no labor to lend
	 * (see {@code docs/caravan.md}). A decaying asset until it settles or trades.
	 */
	public void detach() {
		this.provisioning = new Foraging();
	}

	/** Switch this retinue back to the settled <b>relief</b> mode (a colony's reserve). */
	public void settle() {
		this.provisioning = new Relief();
	}

	/**
	 * Switch this retinue to the <b>camp</b> mode — the foraging band of a sub-ruler
	 * {@link com.civstudio.settlement.SettlementTier tier} ({@code CAMP}/{@code COTTAGE}/{@code
	 * HAMLET}, led by a {@link Captain}): the pooled peasants are the camp's workers, foraging
	 * the site for food. Each step they eat a lean camp ration from the larder and forage a
	 * yield back into it (see {@link com.civstudio.settlement.Settlement#campForageYield(int)});
	 * there is no market to restock on, no patron to bill, no builder to lend to. When the camp
	 * grows to {@code SMALLHOLDING} the harness reforms it into the settled {@link #settle()
	 * relief} reserve. See {@code docs/settlement-tier-ladder-plan.md} Phase D.
	 */
	public void camp() {
		this.provisioning = new Camp();
	}

	/** Whether this retinue is in the detached wandering mode. */
	public boolean isWandering() {
		return provisioning instanceof Foraging;
	}

	/** Whether this retinue is in the camp (settled-foraging) mode. */
	public boolean isCamped() {
		return provisioning instanceof Camp;
	}

	/** The daily ration each member currently eats (settled relief vs. wandering). */
	public RationSize getRation() {
		return provisioning.ration(this);
	}

	private void feed() {
		LocalDate today = getColony().getDate();
		// adults eat the mode's ration (settled relief, or the leaner wandering ration);
		// a pool child (an orphan ward the lord feeds) eats the smaller colony child
		// ration, never more than the adult ration under a lean mode
		double adultRation = provisioning.ration(this).perDay();
		double childRation = Math.min(adultRation,
				getColony().getFertilityConfig().childRation().perDay());
		double wanted = 0;
		for (Member m : peasants)
			// a drafted peasant is away on the expedition and fed by the caravan, not
			// the pool, so its ration is not bought here (docs/explorer-caravan.md)
			if (!m.isDrafted())
				wanted += rationFor(m, adultRation, childRation, today);
		lastConsumed = necessity.decrease(wanted);
		double shortfall = wanted - lastConsumed;

		// poor relief from the colony's strategic store: before any peasant starves, draw
		// the shortfall the larder could not cover from the granary — the ever-normal food
		// bank the crown filled cheaply in plenty (see docs/granary.md §4.8). This feeds
		// the reserve from stored grain rather than the pool out-bidding the workforce on a
		// tight market, and is subsidized (the draw moves only food; its cost was borne
		// when the granary bought the grain, reconciled to the ruler). Settled mode only —
		// a wandering band is detached from the colony and forages from its carried larder.
		if (shortfall > 1e-9 && !isWandering()) {
			Granary granary = getColony().getGranary();
			if (granary != null) {
				double drawn = granary.drawStock(shortfall);
				lastConsumed += drawn;
				shortfall -= drawn;
			}
		}

		// a peasant starves only when even its ration is missing; fractional shortfalls
		// are absorbed (the larder/buffer carries them). With mixed adult/child rations
		// the least skilled starve first, each freeing its own ration, until the missing
		// food is covered (the generalization of "floor(shortfall / ration)")
		long starved = 0;
		if (shortfall > 1e-9) {
			// the least skilled starve first; the abler are kept for promotion. A drafted
			// peasant is away with (and fed by) the expedition, so it is skipped — it can
			// neither be counted toward the shortfall nor starved off here
			// (docs/explorer-caravan.md); with no drafted peasant this is the prior loop.
			peasants.sort(Comparator.comparingInt(m -> m.skills().overallLevel()));
			double freed = 0;
			int i = 0;
			while (i < peasants.size()) {
				Member m = peasants.get(i);
				if (m.isDrafted()) {
					i++;
					continue;
				}
				double r = rationFor(m, adultRation, childRation, today);
				if (freed + r > shortfall) // remaining shortfall is sub-ration: absorbed
					break;
				freed += r;
				skillStore.remove(viewOf(m));
				peasants.remove(i); // removal shifts the next candidate into i
				starved++;
			}
			if (starved > 0)
				log.finer(starved + " peasant(s) starved (pool now "
						+ peasants.size() + ")");
		}
		lastStarved = starved;
	}

	// the daily ration a pooled peasant eats: an adult its mode ration, a child the
	// (smaller) colony child ration
	private double rationFor(Member m, double adultRation, double childRation,
			LocalDate today) {
		return m.isAdult(today) ? adultRation : childRation;
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
	 * Fold a person into the following as a peasant — the <b>inverse of promotion</b>
	 * (a disbanding household's member joining the pool). Used at <b>dissolution</b>,
	 * when a failing colony's surviving households collapse back into the band's
	 * following (see {@code docs/caravan.md}).
	 *
	 * @param person
	 *            the person to add to the pool
	 */
	public void absorb(Member person) {
		peasants.add(adopt(person));
	}

	/**
	 * Add {@code amount} of necessity to the pool's larder — e.g. a disbanding
	 * household's food folded into the band's carried larder at dissolution (the food
	 * side of {@link #absorb(Member)}).
	 *
	 * @param amount
	 *            necessity units to add to the larder
	 */
	public void stockLarder(double amount) {
		necessity.increase(amount);
	}

	/**
	 * Remove and return the highest-overall-skill <b>working-age</b> peasant — the one
	 * a {@link Ruler} promotes into a laborer household (merit-based social
	 * mobility). Children are skipped (they cannot head a working household until they
	 * mature). Returns {@code null} when no working-age peasant remains (then no
	 * replacement is produced and the labor force shrinks — the pool drains with no
	 * inflow yet).
	 *
	 * @return the ablest adult peasant, removed from the pool, or {@code null} if none
	 */
	public Member promoteHighestSkilled() {
		LocalDate today = getColony().getDate();
		Member best = null;
		// only working-age peasants are promotable; children stay in the pool until
		// they mature (food-balance.md item 4), and a drafted peasant is away on an
		// expedition (not at the center plot), so it cannot be promoted until it returns
		// (docs/explorer-caravan.md)
		for (Member m : peasants) {
			if (!m.isAdult(today) || m.isDrafted())
				continue;
			if (best == null
					|| m.skills().overallLevel() > best.skills().overallLevel())
				best = m;
		}
		if (best != null) {
			// frees its row and materializes its skills into a standalone copy, which
			// travels with the Member into the laborer household it heads
			skillStore.remove(viewOf(best));
			peasants.remove(best);
			promotedCount++;
		}
		return best;
	}

	/**
	 * Remove a <b>specific</b> peasant from the pool — the seam a returned {@link
	 * ExplorerCaravan explorer} draftee uses to leave the pool and found its own household. Unlike
	 * {@link #promoteHighestSkilled()} (which picks the ablest), this releases a <em>named</em>
	 * returnee, freeing its skill row so its skills travel into the household/noble it heads. A
	 * no-op if the member is not in the pool. See {@code docs/explorer-caravan.md} (the renewal loop).
	 *
	 * @param member the peasant to release
	 * @return {@code true} if it was in the pool and is now removed
	 */
	public boolean release(Member member) {
		if (!peasants.contains(member))
			return false;
		skillStore.remove(viewOf(member));
		peasants.remove(member);
		promotedCount++;
		return true;
	}

	/**
	 * Remove and return the {@code k} highest-overall-skill <b>working-age</b>
	 * peasants, in descending skill order — the merit-based cohort a {@link Ruler}
	 * promotes into laborer households when the colony is founded. Children are
	 * excluded (they cannot head a working household until they mature). Equivalent to
	 * calling {@link #promoteHighestSkilled()} {@code k} times (the same members, in
	 * the same order, with the same skill-store removals), but found with a single
	 * stable sort rather than {@code k} linear scans of the pool: no skill changes
	 * during founding, so the {@code k} sequential maxima are exactly the top {@code k}
	 * adults by overall level, ties broken toward the earlier pool position (the "first
	 * maximum in list order" each scan picks). Returns fewer than {@code k} only if
	 * fewer working-age peasants remain.
	 *
	 * @param k the number of peasants to promote
	 * @return the promoted adult peasants, highest skill first, removed from the pool
	 */
	public List<Member> promoteHighestSkilled(int k) {
		LocalDate today = getColony().getDate();
		// only working-age peasants are promotable; children stay in the pool until they
		// mature (food-balance.md item 4), so the cohort is drawn from the adults alone
		int size = peasants.size();
		int[] level = new int[size];
		List<Integer> adults = new ArrayList<>(size);
		for (int i = 0; i < size; i++) {
			level[i] = peasants.get(i).skills().overallLevel();
			// drafted peasants are away on an expedition and cannot be promoted
			// (docs/explorer-caravan.md)
			if (peasants.get(i).isAdult(today) && !peasants.get(i).isDrafted())
				adults.add(i);
		}
		int n = Math.min(Math.max(0, k), adults.size());
		List<Member> promoted = new ArrayList<>(n);
		if (n == 0)
			return promoted;
		// stably sort adult positions by level descending; equal levels keep their
		// original (ascending) pool order, matching the first-maximum-in-list-order the
		// repeated single scan picks
		adults.sort((a, b) -> Integer.compare(level[b], level[a]));
		Set<Member> chosen = Collections.newSetFromMap(new IdentityHashMap<>());
		for (int i = 0; i < n; i++) {
			Member m = peasants.get(adults.get(i));
			promoted.add(m);
			chosen.add(m);
		}
		// free each chosen peasant's skill-store row in the same highest-first order
		// the single-promote path uses, then drop them all in one O(size) pass
		for (Member m : promoted)
			skillStore.remove(viewOf(m));
		peasants.removeAll(chosen);
		promotedCount += n;
		return promoted;
	}

	/**
	 * The best <b>working-age</b> peasant of the given gender to take as a spouse — the
	 * highest {@linkplain SkillTracker#overallLevel() overall skill}, the younger one
	 * (the more recent birth date) breaking a tie — without removing it. Children are
	 * skipped (they cannot wed until they mature). Returns {@code null} when no
	 * working-age peasant of that gender remains. Used by the {@link WeddingMarket} to
	 * choose (and price) a match before committing.
	 *
	 * @param gender
	 *            the gender of spouse sought (the opposite of the head's)
	 * @return the ablest, then youngest, adult peasant of that gender, or {@code null}
	 */
	public Member bestSpouseCandidate(Gender gender) {
		LocalDate today = getColony().getDate();
		Member best = null;
		for (Member m : peasants) {
			// only working-age peasants of the sought gender are weddable; children
			// stay in the pool until they mature (food-balance.md item 4), and a drafted
			// peasant is away on an expedition (not at the center plot's wedding market)
			// so it cannot wed until it returns (docs/explorer-caravan.md)
			if (m.gender() != gender || !m.isAdult(today) || m.isDrafted())
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
		if (removed) {
			// materialize its skills so they survive into the household as a spouse
			skillStore.remove(viewOf(peasant));
			marriedOutCount++;
		}
		return removed;
	}

	/**
	 * Up to {@code k} of the pool's <b>least-skilled undrafted adults</b> — the cohort a colony
	 * drafts into an {@link com.civstudio.agent.ExplorerCaravan} levy, keeping the ablest home
	 * for promotion and labour (see {@code docs/explorer-caravan.md}). The returned members
	 * <b>stay in the pool</b>: the muster only references and flags them {@link Member#setDrafted
	 * drafted} (Phase 1's exclusions then keep them off the markets and the pool's table); they
	 * are not removed. Children and already-drafted peasants are skipped.
	 *
	 * @param k the most draftees wanted
	 * @return up to {@code k} least-skilled adult peasants, still pooled (empty if none free)
	 */
	public List<Member> draftableAdults(int k) {
		if (k <= 0)
			return List.of();
		LocalDate today = getColony().getDate();
		List<Member> adults = new ArrayList<>();
		for (Member m : peasants)
			if (m.isAdult(today) && !m.isDrafted())
				adults.add(m);
		// least skilled first — draft the ablest last, so promotion/labour keep the best
		adults.sort(Comparator.comparingInt(m -> m.skills().overallLevel()));
		return new ArrayList<>(adults.subList(0, Math.min(k, adults.size())));
	}

	/** @return the number of peasants currently in the pool */
	public int size() {
		return peasants.size();
	}

	// the number of peasants actually PRESENT in the pool — not drafted away on an
	// expedition. Drafted peasants stay accounted in the pool but are fed by their
	// caravan and lend no labour, so the pool sizes its relief buy off the present count
	// (docs/explorer-caravan.md). Equal to size() when none is drafted.
	private int presentSize() {
		int n = 0;
		for (Member m : peasants)
			if (!m.isDrafted())
				n++;
		return n;
	}

	/**
	 * The number of pooled peasants below working age (children) as of {@code today} —
	 * the not-yet-promotable young who stagger the pool's age structure (see {@code
	 * docs/food-balance.md} item 4). Reported in the colony's annual digest.
	 *
	 * @param today
	 *            the colony's current date
	 * @return the count of child peasants in the pool
	 */
	public int childCount(LocalDate today) {
		int children = 0;
		for (Member m : peasants)
			if (!m.isAdult(today))
				children++;
		return children;
	}

	/**
	 * The pool's people — an unmodifiable snapshot, for re-seeding a fresh pool when a
	 * band re-founds (see the adopting {@link #Retinue(List, double, Bank, Settlement)
	 * constructor} and {@code docs/caravan.md}).
	 *
	 * @return an unmodifiable copy of the pooled peasants
	 */
	public List<Member> getMembers() {
		return List.copyOf(peasants);
	}

	/** @return the pool's current larder (necessity units it carries) */
	public double getLarder() {
		return necessity.getQuantity();
	}

	/** @return the necessity the pool actually ate last {@link #act() step} */
	public double getLastConsumed() {
		return lastConsumed;
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

	/**
	 * How a {@link Retinue} is sustained each step — the slice of behavior that differs
	 * between a settled relief reserve and a detached wandering band. The Retinue
	 * <b>composes</b> one of these and swaps it on {@link #detach()}/{@link #settle()},
	 * so the two modes stay cleanly separated instead of a flag branched through
	 * {@link #act()} (composition over a settled-vs-wandering subclass).
	 */
	private interface Provisioning {

		/** The daily ration each member eats in this mode (may depend on the pool's config). */
		RationSize ration(Retinue r);

		/** Hook run before the members are fed. */
		void beforeFeeding(Retinue r);

		/** Hook run after the members are fed. */
		void afterFeeding(Retinue r);
	}

	/**
	 * Settled mode (the colony's <b>relief reserve</b>, and the default): the ruler
	 * funds its market food — reconciled each step by {@link Retinue#billRuler} — and
	 * its peasants serve as the builder's corvée labor; it eats the
	 * {@link RationSize#SIMPLE} relief ration. This is exactly the behavior the
	 * Retinue had before the wandering mode was introduced.
	 */
	private static final class Relief implements Provisioning {

		public RationSize ration(Retinue r) {
			return r.config.reliefRation();
		}

		public void beforeFeeding(Retinue r) {
			// the ruler reimburses the relief spend so far (borrowing as needed),
			// returning the account to ~0 before interest is assessed this step; the
			// ruler may have died earlier this step and not yet been succeeded, in
			// which case the deficit simply carries over
			Ruler ruler = r.getColony().getRuler();
			if (ruler != null && ruler.isAlive())
				r.billRuler(ruler);
		}

		public void afterFeeding(Retinue r) {
			Ruler ruler = r.getColony().getRuler();
			boolean rulerLive = ruler != null && ruler.isAlive();
			// supply the survivors to the builder as its exclusive workforce, wages
			// routed to the ruler (their patron); none when there is no builder/ruler
			if (r.builderLaborMkt != null && rulerLive)
				for (Member m : r.peasants)
					// a drafted peasant is away on an expedition (not at the center
					// plot), so it lends no corvée labour until it returns
					// (docs/explorer-caravan.md)
					if (!m.isDrafted())
						r.builderLaborMkt.addEmployee(ruler.getID(), ruler.getBank(), 1.0,
								m.skills());
			// buy enough to feed next step (funded by the overdraft billRuler reconciles)
			if (!r.peasants.isEmpty())
				r.nMkt.addBuyOffer(r, r.demandForN);
		}
	}

	/**
	 * Detached <b>wandering</b> mode (a {@link Caravan} on the move): the band eats the
	 * lean {@link MarchingCaravan#WANDERING_RATION} from its carried larder and does nothing
	 * else — no market to restock on, no patron to bill, no labor to lend. A decaying
	 * asset that must settle (or trade) before the larder runs out.
	 */
	private static final class Foraging implements Provisioning {

		public RationSize ration(Retinue r) {
			return MarchingCaravan.WANDERING_RATION;
		}

		public void beforeFeeding(Retinue r) {
			// no patron while wandering — nothing to bill
		}

		public void afterFeeding(Retinue r) {
			// marketless and idle — consume the carried larder, restock nothing
		}
	}

	/**
	 * Camp mode (a {@link Captain}-led foraging band on a sub-ruler tier): the pooled peasants
	 * <b>are</b> the camp's workforce — they eat a lean camp ration and forage a food yield back
	 * into the larder each step (the site's {@linkplain com.civstudio.settlement.Settlement#campForageYield(int)
	 * forage yield}). Marketless like {@link Foraging}, but — unlike a decaying wandering band —
	 * it <b>restocks</b> from the land it sits on, so a camp on good ground net-grows its larder
	 * and the settlement's food box, climbing the tier ladder (see {@code
	 * docs/settlement-tier-ladder-plan.md} Phase D).
	 */
	private static final class Camp implements Provisioning {

		public RationSize ration(Retinue r) {
			return Settlement.CAMP_RATION;
		}

		public void beforeFeeding(Retinue r) {
			// no patron while camped — nothing to bill
		}

		public void afterFeeding(Retinue r) {
			// forage the site: the peasants' labour returns a food yield to the larder, so a
			// camp on good ground sustains and grows (marketless — no offer posted)
			double foraged = r.getColony().campForageYield(r.size());
			if (foraged > 0)
				r.necessity.increase(foraged);
		}
	}
}
