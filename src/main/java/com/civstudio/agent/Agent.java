package com.civstudio.agent;

import com.civstudio.bank.Bank;
import com.civstudio.settlement.Settlement;
import com.civstudio.settlement.PlotOccupant;
import com.civstudio.good.Good;
import lombok.Getter;

/**
 * Parent class of all agents. Agents are the only things that occupy build plots
 * today (on-plot firms do), so {@code Agent} is the model's sole {@link
 * PlotOccupant} — see that interface for the seam future non-agent occupants will use.
 *
 * @author zhihongx
 *
 */
public abstract class Agent implements PlotOccupant {

	// each agent has an unique ID that is also used as the bank account number
	@Getter
	private final int ID;

	// the bank at which this agent holds its accounts
	@Getter
	private final Bank bank;

	// the colony this agent belongs to
	@Getter
	private final Settlement colony;

	// is the agent alive?
	@Getter
	private boolean isAlive;

	// display name; defaults to the class simple name, overridable via setName
	private String name = null;

	/**
	 * Create a new agent holding its accounts at <tt>bank</tt>
	 *
	 * @param bank
	 *            the bank at which this agent holds its accounts
	 * @param colony
	 *            the colony this agent belongs to
	 */
	public Agent(Bank bank, Settlement colony) {
		isAlive = true;
		this.bank = bank;
		this.colony = colony;
		ID = colony.nextAgentID();
	}

	/**
	 * Return a reference to a good given <tt>goodName</tt>
	 * 
	 * @param goodName
	 * @return a reference to a good given <tt>goodName</tt>
	 */
	public abstract Good getGood(String goodName);

	/**
	 * Return the agent's display name, which defaults to the class simple name
	 * unless a subclass set a friendlier one via {@link #setName(String)}.
	 *
	 * @return the agent's display name
	 */
	public final String getName() {
		if (name == null)
			name = this.getClass().getSimpleName();
		return name;
	}

	/**
	 * Set the agent's display name, overriding the class-name default.
	 *
	 * @param name
	 *            the display name
	 */
	protected final void setName(String name) {
		this.name = name;
	}

	/**
	 * Make the agent die.
	 */
	protected void die() {
		isAlive = false;
	}

	/**
	 * Called by Settlement.newDay() in each simulation step.
	 */
	public abstract void act();

}
