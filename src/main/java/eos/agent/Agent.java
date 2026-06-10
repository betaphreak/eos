package eos.agent;

import eos.bank.Bank;
import eos.economy.Economy;
import eos.good.Good;
import lombok.Getter;

/**
 * Parent class of all agents
 *
 * @author zhihongx
 *
 */
public abstract class Agent {

	// each agent has an unique ID that is also used as the bank account number
	@Getter
	private final int ID;

	// the bank at which this agent holds its accounts
	@Getter
	private final Bank bank;

	// the economy this agent belongs to
	@Getter
	private final Economy economy;

	// is the agent alive?
	@Getter
	private boolean isAlive;

	// name of the agent's class
	private String className = null;

	/**
	 * Create a new agent holding its accounts at <tt>bank</tt>
	 *
	 * @param bank
	 *            the bank at which this agent holds its accounts
	 * @param economy
	 *            the economy this agent belongs to
	 */
	public Agent(Bank bank, Economy economy) {
		isAlive = true;
		this.bank = bank;
		this.economy = economy;
		ID = economy.nextAgentID();
	}

	/**
	 * Return a reference to a good given <tt>goodName</tt>
	 * 
	 * @param goodName
	 * @return a reference to a good given <tt>goodName</tt>
	 */
	public abstract Good getGood(String goodName);

	/**
	 * Return the class name of the agent
	 * 
	 * @return the class name of the agent
	 */
	public final String getName() {
		if (className == null)
			className = this.getClass().getSimpleName();
		return className;
	}

	/**
	 * Make the agent die.
	 */
	protected void die() {
		isAlive = false;
	}

	/**
	 * Called by Economy.newDay() in each simulation step.
	 */
	public abstract void act();

}
