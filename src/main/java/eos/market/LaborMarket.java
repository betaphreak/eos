package eos.market;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Set;

import eos.bank.Bank;
import eos.agent.firm.Firm;
import eos.agent.laborer.Laborer;
import eos.settlement.Settlement;
import eos.good.Labor;
import eos.skill.Skill;
import eos.skill.SkillTracker;

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
	private double daylightSensitivity() {
		double absLat = Math.abs(colony.getLatitude());
		double penalty = (absLat - PENALTY_START_LATITUDE)
				/ (POLE_LATITUDE - PENALTY_START_LATITUDE);
		penalty = Math.min(1, Math.max(0, penalty));
		return 1 - penalty;
	}

	/* employer */
	private class Employer {
		private Labor labor;
		private double wageBudget; // total wage budget
		private String name; // name of the employer
		private int bankID; // account number of the employer
		private Bank bank; // bank of the employer
		private Set<Skill> skills; // skills this employer's labor trains
	}

	/* employee */
	private class Employee {
		private int bankID; // account number of the employee
		private Bank bank; // bank of the employee
		private double productivity; // labor produced when employed (by skill)
		private SkillTracker skills; // the worker's skills (gains XP), may be null
	}

	private ArrayList<Employer> employers;
	private ArrayList<Employee> employees;

	private double totalBudget; // sum of wage budgets of all employers

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
	 * {@link eos.agent.firm.StrategicFirm} — so the two pools never mix.
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
	 * Add an employer to the market
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
		employers.add(employer);
		totalBudget += wageBudget;
	}

	/**
	 * Add a laborer to the market as an employee, supplying its skill-scaled
	 * productivity adjusted for the length of the day: the laborer delivers 100%
	 * of its output at {@value #FULL_OUTPUT_DAYLIGHT_HOURS} hours of daylight, more
	 * on longer days and less on shorter ones (so output rises in summer and falls
	 * in winter). The strength of that adjustment is itself penalized at high
	 * latitudes (see {@link #daylightSensitivity()}): full effect up to
	 * {@value #PENALTY_START_LATITUDE}°, fading to none at the poles, which keeps
	 * the extreme high-latitude daylight swings from destabilizing the economy.
	 * This applies only to laborers — nobles supplying labor to the strategic
	 * sector use the {@link #addEmployee(int, Bank, double)} primitive directly and
	 * are unaffected.
	 *
	 * @param laborer
	 *            the laborer seeking employment
	 */
	public void addEmployee(Laborer laborer) {
		double ratio = colony.getDaylightHours() / FULL_OUTPUT_DAYLIGHT_HOURS;
		double daylightFactor = 1 + daylightSensitivity() * (ratio - 1);
		// polar day/night leaves daylight undefined; fall back to unscaled output
		if (!Double.isFinite(daylightFactor))
			daylightFactor = 1;
		addEmployee(laborer.getID(), laborer.getBank(),
				laborer.getProductivity() * daylightFactor,
				laborer.getHead().skills());
	}

	/**
	 * Add an employee to the market by its account details and the skill-scaled
	 * labor it supplies when employed. The general primitive behind {@link
	 * #addEmployee(Laborer)}, it lets non-laborer households — e.g. a
	 * {@link eos.agent.noble.Noble} supplying labor to the strategic sector —
	 * join a labor market while delivering labor scaled by their own
	 * productivity.
	 *
	 * @param bankID
	 *            the employee's account number
	 * @param bank
	 *            the bank at which the employee holds its accounts
	 * @param productivity
	 *            labor delivered to the employer per head when employed
	 * @param skills
	 *            the worker's skills, which gain experience for the labor
	 *            performed (may be null to gain none)
	 */
	public void addEmployee(int bankID, Bank bank, double productivity,
			SkillTracker skills) {
		Employee employee = new Employee();
		employee.bankID = bankID;
		employee.bank = bank;
		employee.productivity = productivity;
		employee.skills = skills;
		employees.add(employee);
	}

	/**
	 * Clear the market.
	 */
	public void clear() {
		Collections.shuffle(employers, colony.getRng().getRandom());
		Collections.shuffle(employees, colony.getRng().getRandom());
		int low = 0;
		double sum = 0;
		for (Employer employer : employers) {
			sum += employer.wageBudget;
			int high = (int) (Math.min(1, sum / totalBudget) * employees.size());

			double wage = employer.wageBudget / (high - low);
			for (int i = low; i < high; i++) {
				Employee employee = employees.get(i);
				employer.bank.withdraw(employer.bankID, wage);
				employee.bank.credit(employee.bankID, wage, Bank.PRIIC);
				// the firm gets this worker's skill-scaled labor (a skill-10
				// worker produces 1, as in the old homogeneous case); the wage,
				// though, is still split per head, not by skill
				employer.labor.increase(employee.productivity);
				// performing this labor trains the worker: one unit of experience
				// in each skill the employer's work develops
				if (employee.skills != null)
					for (Skill skill : employer.skills)
						employee.skills.learn(skill, XP_PER_LABOR);
			}
			low = high;
		}
		employers.clear();
		employees.clear();
		totalBudget = 0;
	}

	@Override
	public String toString() {
		return String.format(
				"%s [employers=%d employees=%d budget=%.1f]",
				name, employers.size(), employees.size(), totalBudget);
	}
}
