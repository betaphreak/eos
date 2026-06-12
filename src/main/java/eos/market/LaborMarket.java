package eos.market;

import java.util.ArrayList;
import java.util.Collections;

import eos.bank.Bank;
import eos.agent.firm.Firm;
import eos.agent.laborer.Laborer;
import eos.settlement.Settlement;
import eos.good.Labor;

/**
 * A labor market
 * 
 * @author zhihongx
 * 
 */
public class LaborMarket extends Market {

	/* employer */
	private class Employer {
		private Labor labor;
		private double wageBudget; // total wage budget
		private String name; // name of the employer
		private int bankID; // account number of the employer
		private Bank bank; // bank of the employer
	}

	/* employee */
	private class Employee {
		private int bankID; // account number of the employee
		private Bank bank; // bank of the employee
		private double productivity; // labor produced when employed (by skill)
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
		employers.add(employer);
		totalBudget += wageBudget;
	}

	/**
	 * Add a laborer to the market as an employee, supplying its skill-scaled
	 * productivity.
	 *
	 * @param laborer
	 *            the laborer seeking employment
	 */
	public void addEmployee(Laborer laborer) {
		addEmployee(laborer.getID(), laborer.getBank(),
				laborer.getProductivity());
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
	 */
	public void addEmployee(int bankID, Bank bank, double productivity) {
		Employee employee = new Employee();
		employee.bankID = bankID;
		employee.bank = bank;
		employee.productivity = productivity;
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
