package eos.market;

import java.util.ArrayList;
import java.util.Collections;

import eos.bank.Bank;
import eos.agent.firm.Firm;
import eos.agent.laborer.Laborer;
import eos.good.Labor;
import eos.util.StdRandom;

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
		private int bankID; // ID of the employer
	}

	/* employee */
	private class Employee {
		private int bankID;
	}

	private ArrayList<Employer> employers;
	private ArrayList<Employee> employees;

	private double totalBudget; // sum of wage budgets of all employers

	/**
	 * Create a new labor market
	 */
	public LaborMarket() {
		super("Labor");
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
		employers.add(employer);
		totalBudget += wageBudget;
	}

	/**
	 * Add an employee to the market
	 * 
	 * @param laborer
	 */
	public void addEmployee(Laborer laborer) {
		Employee employee = new Employee();
		employee.bankID = laborer.getID();
		employees.add(employee);
	}

	/**
	 * Clear the market.
	 */
	public void clear() {
		Collections.shuffle(employers, StdRandom.getRandom());
		Collections.shuffle(employees, StdRandom.getRandom());
		int low = 0;
		double sum = 0;
		for (Employer employer : employers) {
			sum += employer.wageBudget;
			int high = (int) (Math.min(1, sum / totalBudget) * employees.size());

			double wage = employer.wageBudget / (high - low);
			for (int i = low; i < high; i++) {
				Bank.pay(employer.bankID, employees.get(i).bankID, wage,
						Bank.PRIIC);
				employer.labor.increase(1);
			}
			low = high;
		}
		employers.clear();
		employees.clear();
		totalBudget = 0;
	}
}
