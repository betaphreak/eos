package com.civstudio.market;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Set;

import com.civstudio.agent.firm.StrategicFirm;
import com.civstudio.agent.noble.Noble;
import com.civstudio.bank.Bank;
import com.civstudio.agent.Household;
import com.civstudio.agent.Member;
import com.civstudio.agent.firm.Firm;
import com.civstudio.agent.laborer.Laborer;
import com.civstudio.settlement.Settlement;
import com.civstudio.settlement.TravelLadder;
import com.civstudio.good.Labor;
import com.civstudio.skill.Skill;
import com.civstudio.skill.SkillTracker;

/**
 * A labor market
 * 
 * @author zhihongx
 * 
 */
public class LaborMarket extends Market {

	// hours of daylight at which a laborer delivers 100% of its skill-scaled
	// output; longer days scale output up proportionally, shorter days down
	private static final double FULL_OUTPUT_DAYLIGHT_HOURS = 8;

	// raw experience a worker gains in each of its employer's labor skills for
	// each step it is employed (one "labor performed")
	private static final double XP_PER_LABOR = 1;

	// the daylight effect is penalized at high latitudes: no penalty up to this
	// latitude (full sensitivity), ramping to a 100% penalty (no sensitivity) at
	// the poles
	private static final double PENALTY_START_LATITUDE = 45;
	private static final double POLE_LATITUDE = 90;

	// how strongly daylight modulates this colony's output (1 + sensitivity*(ratio
	// - 1), so output is always 100% at the reference daylight): full below
	// PENALTY_START_LATITUDE, falling linearly to 0 at the poles
	private static double daylightSensitivity(Settlement colony) {
		double absLat = Math.abs(colony.getLatitude());
		double penalty = (absLat - PENALTY_START_LATITUDE)
				/ (POLE_LATITUDE - PENALTY_START_LATITUDE);
		penalty = Math.min(1, Math.max(0, penalty));
		return 1 - penalty;
	}

	/**
	 * The colony's laborer <b>daylight factor</b> for the current day: 1.0 at
	 * {@value #FULL_OUTPUT_DAYLIGHT_HOURS} hours of daylight, scaled up on longer days
	 * and down on shorter ones, with the sensitivity fading to none toward the poles
	 * (see {@link #daylightSensitivity}). The one day-length scaling shared by market
	 * labor ({@link #addEmployee(Laborer)}) and home plot-working
	 * ({@code settlement.BuildEconomy} — the full-parity rule of
	 * {@code docs/build-queue-plan.md} B1), so neither occupation has a seasonal
	 * loophole over the other.
	 *
	 * @param colony the colony whose day is being scaled
	 * @return the daylight factor (1.0 when daylight is undefined — polar day/night)
	 */
	public static double daylightFactor(Settlement colony) {
		double ratio = colony.getDaylightHours() / FULL_OUTPUT_DAYLIGHT_HOURS;
		double factor = 1 + daylightSensitivity(colony) * (ratio - 1);
		// polar day/night leaves daylight undefined; fall back to unscaled output
		return Double.isFinite(factor) ? factor : 1;
	}

	/* employer */
	private class Employer {
		private Labor labor;
		private double wageBudget; // total wage budget
		private String name; // name of the employer
		private int bankID; // account number of the employer
		private Bank bank; // bank of the employer
		private Set<Skill> skills; // skills this employer's labor trains
		private boolean operating; // whether the firm hires today (see Firm.operatesOn)
		private double commute; // round-trip travel to its plot in seconds (0 = in-town)
	}

	/* employee */
	private class Employee {
		private int bankID; // account number of the employee
		private Bank bank; // bank of the employee
		private SkillTracker skills; // worker's skills: drive labor + gain XP (may be null)
		private double laborMultiplier; // non-skill scaling (day length); 1 for nobles
	}

	private ArrayList<Employer> employers;
	private ArrayList<Employee> employees;

	private double totalBudget; // sum of wage budgets of all employers

	// the account ids credited a wage in the LAST clear() — i.e. the households at
	// least one of whose members an operating firm actually hired. Read by the build
	// economy's unhired fallback (docs/build-queue-plan.md B1) after clearing; a
	// household that offered labor but appears nowhere here was left unhired and
	// works its plot instead. Pure bookkeeping: no RNG, no behavior change.
	private final Set<Integer> lastHired = new java.util.HashSet<>();

	/**
	 * Create a new labor market for the default {@code "Labor"} good.
	 *
	 * @param colony
	 *            the colony this market belongs to
	 */
	public LaborMarket(Settlement colony) {
		this("Labor", colony);
	}

	/**
	 * Create a new labor market trading <tt>good</tt>. A colony may run more than
	 * one labor market over distinct goods — e.g. the general {@code "Labor"}
	 * market and a separate {@code "NobleLabor"} market whose employee pool is the
	 * nobles and whose sole employer is the
	 * {@link StrategicFirm} — so the two pools never mix.
	 *
	 * @param good
	 *            name of the labor good this market trades
	 * @param colony
	 *            the colony this market belongs to
	 */
	public LaborMarket(String good, Settlement colony) {
		super(good, colony);
		employers = new ArrayList<Employer>();
		employees = new ArrayList<Employee>();
		totalBudget = 0;
	}

	/**
	 * Add an employer to the market. The firm is <b>always registered</b> — so its
	 * wage budget counts toward the total against which every firm's share of the
	 * workforce is sized — but it only actually hires on the days it {@link
	 * Firm#operatesOn operates}: every firm on workdays, on the weekly day of rest
	 * only enjoyment firms, and on feast days only the noble-staffed export firm. A
	 * firm closed today still <em>reserves</em> its budget-proportional share of
	 * the workforce, but is allocated no one (see {@link #clear()}); those workers
	 * simply rest, rather than flooding into the firms that are open. The operating
	 * calendar applies only once the colony is {@link Settlement#isStarted() live}
	 * — the pre-run seeding clear (before {@code start()}) hires every firm so step
	 * 0 has a workforce no matter what kind of day it falls on.
	 *
	 * @param firm
	 *            <p>
	 * @param labor
	 *            a reference to the labor good owned by <tt>firm</tt>
	 * @param wageBudget
	 *            total wage budget of <tt>firm</tt>
	 */
	public void addEmployer(Firm firm, Labor labor, double wageBudget) {
		Employer employer = new Employer();
		employer.labor = labor;
		employer.wageBudget = wageBudget;
		employer.name = firm.getName();
		employer.bankID = firm.getID();
		employer.bank = firm.getBank();
		employer.skills = firm.laborSkills();
		employer.operating = !colony.isStarted()
				|| firm.operatesOn(colony.getDayType());
		// the round-trip commute its workers walk to its plot (0 for a center-grouped
		// firm, or any firm in a province-less colony — see Settlement.plotTravelTime)
		employer.commute = colony.plotTravelTime(firm);
		employers.add(employer);
		totalBudget += wageBudget;
	}

	/**
	 * Add a laborer to the market as an employee. The labor it delivers is
	 * computed when the market clears, from its proficiency in the employer's work
	 * (see {@link #clear()}), then adjusted for the length of the day: a laborer
	 * delivers 100% of its output at {@value #FULL_OUTPUT_DAYLIGHT_HOURS} hours of
	 * daylight, more on longer days and less on shorter ones (so output rises in
	 * summer and falls in winter). The strength of that adjustment is itself
	 * penalized at high latitudes (see {@link #daylightSensitivity()}): full effect
	 * up to {@value #PENALTY_START_LATITUDE}°, fading to none at the poles, which
	 * keeps the extreme high-latitude daylight swings from destabilizing the
	 * economy. This day-length scaling applies only to laborers — nobles supplying
	 * labor to the strategic sector use the {@link #addEmployee(int, Bank, double,
	 * SkillTracker)} primitive with a multiplier of 1 and are unaffected.
	 *
	 * @param laborer
	 *            the laborer seeking employment
	 */
	public void addEmployee(Laborer laborer) {
		double daylightFactor = daylightFactor(colony);
		// every working adult member of the household is a separate earner: each is
		// placed on the market with its own skills (so head and spouse may end up
		// at different firms and train different skills), but all wages credit the
		// one household account. Children (members below working age) deliver no
		// labour and earn no wage, so they are skipped (see docs/births.md). At
		// founding a household has only its head.
		for (Member member : laborer.getMembers())
			// a drafted member is away on an expedition (not at the center plot where the
			// labor market lives), so it supplies no labor until it returns
			// (docs/explorer-caravan.md)
			if (member.isAdult(colony.getDate()) && !member.isDrafted())
				addEmployee(laborer.getID(), laborer.getBank(), daylightFactor,
						member.skills());
	}

	/**
	 * Add an employee to the market by its account details, the skills that both
	 * drive its delivered labor and gain experience, and a non-skill labor
	 * multiplier. The general primitive behind {@link #addEmployee(Laborer)}, it
	 * lets non-laborer households — e.g. a {@link Noble} supplying
	 * labor to the strategic sector — join a labor market; the actual labor is
	 * {@code productivityOf(level in the employer's skills) × laborMultiplier},
	 * computed when the market clears.
	 *
	 * @param bankID
	 *            the employee's account number
	 * @param bank
	 *            the bank at which the employee holds its accounts
	 * @param laborMultiplier
	 *            a non-skill scaling of delivered labor (e.g. day length); pass 1
	 *            for no scaling
	 * @param skills
	 *            the worker's skills — drive its delivered labor (via its level in
	 *            the employer's skills) and gain experience for the labor
	 *            performed; may be null (then it delivers {@code laborMultiplier}
	 *            and learns nothing)
	 */
	public void addEmployee(int bankID, Bank bank, double laborMultiplier,
			SkillTracker skills) {
		Employee employee = new Employee();
		employee.bankID = bankID;
		employee.bank = bank;
		employee.laborMultiplier = laborMultiplier;
		employee.skills = skills;
		employees.add(employee);
	}

	/**
	 * Clear the market.
	 */
	public void clear() {
		lastHired.clear();
		Collections.shuffle(employers, colony.getRng().getRandom());
		Collections.shuffle(employees, colony.getRng().getRandom());
		// the travel-time coupling: each worker loses the market's clearing overhead N
		// (one second per participant) plus its firm's round-trip commute, out of the
		// day's work window D (sunrise→sunset). A province-less colony bypasses it
		// entirely (workFactor == 1), staying byte-identical. Both N and D are
		// per-day, so this is recomputed every clear. See docs/plots.md.
		boolean coupled = colony.getProvince() != null;
		double n = employees.size();
		double d = coupled ? colony.getWorkWindowSeconds() : 0;
		int low = 0;
		double sum = 0;
		for (Employer employer : employers) {
			sum += employer.wageBudget;
			// each firm's contiguous slice of the (shuffled) workforce is sized by
			// its share of the total wage budget — including firms that are closed
			// today, so an open firm gets only its own share instead of the whole
			// pool. With no budget at all (an empty/all-zero market) no one is hired.
			int high = totalBudget > 0
					? (int) (Math.min(1, sum / totalBudget) * employees.size())
					: low;

			// a firm closed today reserves its slice but hires no one: those workers
			// rest (no wage, no labor delivered, no experience gained)
			if (employer.operating && high > low) {
				double wage = employer.wageBudget / (high - low);
				// the fraction of each worker's labor that survives the day's overheads
				// (market clearing N + this firm's commute, out of the work window D);
				// 1 for a province-less colony (the coupling is off)
				double workFactor = coupled
						? TravelLadder.workFactor(employer.commute, n, d) : 1.0;
				for (int i = low; i < high; i++) {
					Employee employee = employees.get(i);
					// a worker whose account has been closed since it posted this offer — its household
					// died or DEPARTED (drafted into an expedition, emigrated as a settler caravan,
					// dissolved) between act() and this clear — is simply not hired: no wage paid, no
					// labor delivered, no training. Its reserved slice is forfeit. (Latent since the
					// deferred-settlement model; a household that posts labor then leaves the same step
					// left a stale offer. Surfaced by the village-larder flip changing departure timing.)
					if (employee.bank.getAcct(employee.bankID) == null)
						continue;
					employer.bank.withdraw(employer.bankID, wage);
					employee.bank.credit(employee.bankID, wage, Bank.PRIIC);
					lastHired.add(employee.bankID);
					// the labor the firm gets is the worker's proficiency in the
					// firm's own work — productivityOf its level in the employer's
					// skills (a skill-10 worker produces 1, as in the old homogeneous
					// case) — times any non-skill scaling (day length) and the
					// travel/market work factor. The wage, though, is still split per
					// head, not by skill.
					double base = employee.skills != null
							? Household.productivityOf(
									relevantLevel(employee.skills, employer.skills))
							: 1.0;
					employer.labor.increase(
							base * employee.laborMultiplier * workFactor);
					// performing this labor trains the worker: one unit of experience
					// in each skill the employer's work develops
					if (employee.skills != null)
						for (Skill skill : employer.skills)
							employee.skills.learn(skill, XP_PER_LABOR);
				}
			}
			low = high;
		}
		employers.clear();
		employees.clear();
		totalBudget = 0;
	}

	/**
	 * Whether the household with account id {@code bankID} had at least one member
	 * hired (credited a wage) in the <b>last</b> {@link #clear()}. Read by the build
	 * economy's unhired fallback: a household that offered labor but was not hired
	 * falls back to working its home plot that day (docs/build-queue-plan.md B1).
	 *
	 * @param bankID the household's account id
	 * @return whether it was hired in the last clearing
	 */
	public boolean wasHiredLastClear(int bankID) {
		return lastHired.contains(bankID);
	}

	// the worker's effective skill level for an employer: the (rounded) mean of
	// its levels in the employer's labor skills — so a necessity firm reads SURVIVAL,
	// a capital firm PRODUCTION, etc. — or its overall level when the employer trains
	// no specific skill. Package-private for direct unit testing.
	static int relevantLevel(SkillTracker skills, Set<Skill> firmSkills) {
		if (firmSkills == null || firmSkills.isEmpty())
			return skills.overallLevel();
		int sum = 0;
		for (Skill skill : firmSkills)
			sum += skills.level(skill);
		return Math.round((float) sum / firmSkills.size());
	}

	@Override
	public String toString() {
		return String.format(
				"%s [employers=%d employees=%d budget=%.1f]",
				name, employers.size(), employees.size(), totalBudget);
	}
}
