package eos.agent;

import eos.good.Good;

/**
 * Parent class of all agents
 * 
 * @author zhihongx
 * 
 */
public abstract class Agent {

	// ID for the next agent
	private static int nextAvailableID = 1;

	// each agent has an unique ID that is also used as the bank account number
	private final int ID;

	// is the agent alive?
	private boolean isAlive;

	// name of the agent's class
	private String className = null;

	/**
	 * Create a new agent
	 */
	public Agent() {
		isAlive = true;
		ID = nextAvailableID++;
	}

	/**
	 * Return a reference to a good given <tt>goodName</tt>
	 * 
	 * @param goodName
	 * @return a reference to a good given <tt>goodName</tt>
	 */
	public abstract Good getGood(String goodName);

	/**
	 * Return the ID of the agent
	 * 
	 * @return the ID of the agent
	 */
	public final int getID() {
		return ID;
	}

	/**
	 * Is the agent alive?
	 * 
	 * @return whether the agent is alive
	 */
	public final boolean isAlive() {
		return isAlive;
	}

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
	 * Called by Economy.step() in each simulation step.
	 */
	public abstract void act();

}
