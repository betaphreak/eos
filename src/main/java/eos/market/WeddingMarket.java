package eos.market;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import eos.agent.Agent;
import eos.agent.Household;
import eos.agent.Member;
import eos.agent.PeasantPool;
import eos.agent.ruler.Ruler;
import eos.bank.Bank;
import eos.calendar.DayType;
import eos.name.Gender;
import eos.name.Person;
import eos.settlement.Settlement;
import lombok.Getter;
import lombok.extern.java.Log;

/**
 * The <b>wedding market</b>: where an unmarried household takes a spouse out of
 * the {@link PeasantPool}. Unlike the goods markets it clears <b>only on the
 * weekly day of rest</b> ({@link DayType#WEEKEND}); on any other day the seekers
 * it collected simply disperse (they re-post each step they remain single).
 * <p>
 * Each step a single household posts itself as a seeker (see {@link
 * eos.agent.AbstractHousehold#seekSpouseIfSingle()}). On a weekend the market
 * marries up to {@link WeddingConfig#capacity()} of them, in <b>priority order</b>
 * — the ruler first, then nobles, then laborers (and, within a rank, the richer
 * household first; see {@link Household#weddingPriority()}) — so the higher-ranked
 * get first pick of the ablest spouses. For each seeker it finds the <b>best</b>
 * available peasant of the opposite gender to the head — highest overall skill,
 * the younger one breaking a tie — and, if the household can afford the
 * (non-linear) bride-price, weds them: the peasant leaves the pool, takes the
 * household's surname and joins it as a member, and the bride-price flows to the
 * {@link Ruler} (the peasants' patron). A household that cannot afford the best
 * available match simply waits for another weekend.
 * <p>
 * The match is fully deterministic (a skill/age sort, no RNG), so a colony
 * without a pool or a living ruler — the bare analytical sims — clears this market
 * to nothing and is left undisturbed.
 */
@Log
public class WeddingMarket extends Market {

	private final WeddingConfig config;

	// the pool spouses are drawn from; set when the pool registers itself (null
	// for a colony with no pool, in which case no wedding can occur)
	private PeasantPool pool;

	// households seeking a spouse this step (collected in act(), matched in clear())
	private final List<Household> seekers = new ArrayList<Household>();

	// the weddings solemnized in the latest clear(), for the WeddingPrinter to
	// emit; cleared and repopulated each clear()
	@Getter
	private final List<Wedding> lastWeddings = new ArrayList<Wedding>();

	/**
	 * Create the colony's wedding market.
	 *
	 * @param colony
	 *            the colony this market belongs to
	 * @param config
	 *            the wedding parameters (capacity, bride-price curve)
	 */
	public WeddingMarket(Settlement colony, WeddingConfig config) {
		super("Wedding", colony);
		this.config = config;
	}

	/**
	 * Register the peasant pool spouses are drawn from. Called by the pool's
	 * constructor; until set, the market has no source of spouses and clears to
	 * nothing.
	 *
	 * @param pool
	 *            the colony's peasant pool
	 */
	public void setPool(PeasantPool pool) {
		this.pool = pool;
	}

	/**
	 * Post an unmarried household as a seeker for this step's weddings.
	 *
	 * @param household
	 *            the single household seeking a spouse
	 */
	public void addSeeker(Household household) {
		seekers.add(household);
	}

	/**
	 * Clear the market: on a weekend, marry up to the capacity in priority order.
	 */
	@Override
	public void clear() {
		List<Household> queue = new ArrayList<Household>(seekers);
		seekers.clear();
		lastWeddings.clear();

		// weddings happen only on the weekly day of rest, only in a live colony
		// with a pool to draw from and a living ruler to receive the bride-price
		if (!colony.isStarted() || colony.getDayType() != DayType.WEEKEND
				|| pool == null)
			return;
		Ruler ruler = colony.getRuler();
		if (ruler == null || !ruler.isAlive())
			return;

		// a seeker may have died earlier this step (its account is closed, so
		// reading its wealth would throw) — drop the dead before sorting
		queue.removeIf(h -> !((Agent) h).isAlive());
		// highest rank first, then the richer household within a rank
		queue.sort(Comparator.comparingInt(Household::weddingPriority)
				.thenComparingDouble(Household::getWealth).reversed());

		int weddings = 0;
		for (Household h : queue) {
			if (weddings >= config.capacity())
				break;
			// skip any that married since posting (defensive — each posts once)
			if (h.getMemberCount() != 1)
				continue;

			Gender wanted = h.getHead().gender().opposite();
			Member candidate = pool.bestSpouseCandidate(wanted);
			if (candidate == null)
				continue; // no peasant of the needed gender remains

			double cost = config.costFor(candidate.skills().overallLevel());
			// the ruler weds from its own wards at no charge; everyone else must
			// afford the best available match or wait for another weekend
			boolean isRuler = h == ruler;
			if (!isRuler && h.getWealth() < cost)
				continue;

			marry(h, candidate, ruler, isRuler ? 0 : cost);
			weddings++;
		}
	}

	// finalize a wedding: take the peasant out of the pool, give it the household
	// surname and add it as a member, move the bride-price to the ruler, and
	// record the event for the printer
	private void marry(Household h, Member candidate, Ruler ruler, double charge) {
		pool.removeForMarriage(candidate);
		Member spouse = new Member(
				new Person(candidate.person().givenName(), h.getHead().surname(),
						candidate.gender(), candidate.skills()),
				candidate.getBirthDate());

		// snapshot both parties' stats before the head's age/skill could change
		lastWeddings.add(new Wedding(colony.getDate(), h.getHead().fullName(),
				h.role(), h.getHead().gender(), h.getSkill(), h.getAgeYears(),
				spouse.fullName(), spouse.gender(), spouse.skills().overallLevel(),
				spouse.getAgeYears(colony.getDate()), charge));

		h.addMember(spouse);

		// the bride-price flows to the ruler, the peasants' patron (the seeker
		// overdraws into a loan only if short — but affordability was checked, so
		// it has the funds; the ruler pays nothing when it is itself the seeker)
		if (charge > 0) {
			h.getBank().withdraw(h.getID(), charge);
			ruler.getBank().credit(ruler.getID(), charge, Bank.OTHER);
		}
	}

	/**
	 * A solemnized wedding, snapshotting both parties' statistics for the {@link
	 * eos.io.printer.WeddingPrinter}. {@code cost} is the bride-price actually
	 * charged (0 for a ruler's own wedding).
	 */
	public record Wedding(LocalDate date, String headName, String headRole,
			Gender headGender, int headSkill, int headAge, String spouseName,
			Gender spouseGender, int spouseSkill, int spouseAge, double cost) {
	}
}
